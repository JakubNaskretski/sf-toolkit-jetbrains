package dev.skrety.sftoolkit.apex

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import dev.skrety.sftoolkit.OrgService
import dev.skrety.sftoolkit.SfCli
import dev.skrety.sftoolkit.SfLog
import dev.skrety.sftoolkit.findSfdxRoot
import dev.skrety.sftoolkit.toolingRefForTypeName

/** Runs all tests in the selected .cls file via `sf apex run test --synchronous`. */
class RunApexTestsAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = project != null && file != null && !file.isDirectory &&
            file.extension == "cls" &&
            toolingRefForTypeName("ApexClass", file.nameWithoutExtension) != null &&
            findSfdxRoot(file, project.guessProjectDir()) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val className = file.nameWithoutExtension
        val org = OrgService.get(project).requireCurrent() ?: return
        val root = findSfdxRoot(file, project.guessProjectDir()) ?: return

        object : Task.Backgroundable(project, "Running Apex tests in $className…", true) {
            override fun run(indicator: ProgressIndicator) {
                val res = SfCli.get(project).execute(
                    listOf("apex", "run", "test", "--class-names", className, "--synchronous", "-o", org),
                    indicator,
                    timeoutMs = 900_000,
                    workDir = root.path,
                )
                if (res.cancelled) return
                val run = parseApexTestRun(res.json)
                reportTestRun(project, org, className, run, res.errorMessage())
            }
        }.queue()
    }
}

fun reportTestRun(
    project: Project,
    org: String,
    className: String,
    run: ApexTestRunSummary?,
    fallbackError: String,
) {
    val group = NotificationGroupManager.getInstance().getNotificationGroup("SF Toolkit")
    if (run == null) {
        group.createNotification("Apex tests failed to run — $org", fallbackError, NotificationType.ERROR)
            .notify(project)
        return
    }
    val time = run.totalTime?.let { " ($it)" } ?: ""
    if (run.failing == 0) {
        group.createNotification(
            "$className: ${run.passing}/${run.testsRan} tests passed$time — $org" +
                (if (run.skipped > 0) ", ${run.skipped} skipped" else ""),
            NotificationType.INFORMATION,
        ).notify(project)
        return
    }
    val log = SfLog.get(project)
    run.failures.forEach { f ->
        log.error("${f.fullName}: ${f.message ?: "failed"}")
        f.stackTrace?.let { log.error("    $it") }
    }
    val preview = run.failures.take(3).joinToString("<br/>") { "${it.fullName}: ${it.message ?: "failed"}" }
    group.createNotification(
        "$className: ${run.failing} of ${run.testsRan} tests failed$time — $org",
        preview + (if (run.failures.size > 3) "<br/>…and ${run.failures.size - 3} more (see SF Log)" else ""),
        NotificationType.ERROR,
    ).notify(project)
}
