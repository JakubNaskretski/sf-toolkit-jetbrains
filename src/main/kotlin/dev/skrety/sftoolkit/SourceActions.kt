package dev.skrety.sftoolkit

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

/** Base for retrieve / deploy / validate over the Project-view or editor selection. */
abstract class SourceCliAction(
    private val progressTitle: String,
    private val pastVerb: String,
) : DumbAwareAction() {

    protected abstract fun cliArgs(org: String, sourceDirArgs: List<String>): List<String>
    protected open val refreshVfsAfter: Boolean = false

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        e.presentation.isEnabledAndVisible = project != null && !files.isNullOrEmpty() &&
            files.all { it.isInLocalFileSystem && findSfdxRoot(it, project.guessProjectDir()) != null }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList() ?: return
        if (files.isEmpty()) return
        val org = OrgService.get(project).requireCurrent() ?: return
        val root = findSfdxRoot(files.first(), project.guessProjectDir()) ?: return
        val sourceDirArgs = files.flatMap { listOf("--source-dir", it.path) }

        object : Task.Backgroundable(project, progressTitle, true) {
            override fun run(indicator: ProgressIndicator) {
                val res = SfCli.get(project).execute(
                    cliArgs(org, sourceDirArgs),
                    indicator,
                    timeoutMs = 600_000,
                    workDir = root.path,
                )
                if (res.cancelled) return
                reportSourceResult(project, res, org, pastVerb)
                if (refreshVfsAfter && res.ok) refreshVfs(files)
            }
        }.queue()
    }

    private fun refreshVfs(files: List<VirtualFile>) {
        ApplicationManager.getApplication().invokeLater {
            VfsUtil.markDirtyAndRefresh(true, true, true, *files.toTypedArray())
        }
    }
}

/** Pure: component files from a deploy/retrieve result envelope. */
fun sourceFiles(resultObj: com.google.gson.JsonObject?): List<com.google.gson.JsonObject> =
    resultObj?.get("files")?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { it.takeIf { el -> el.isJsonObject }?.asJsonObject }
        .orEmpty()

/**
 * Pure: one line per failed component. Field names verified against a live
 * `sf project deploy start --dry-run --json` failure envelope (state/type/fullName/error).
 */
fun sourceFailureLines(files: List<com.google.gson.JsonObject>): List<String> =
    files.filter { it.str("state").equals("Failed", ignoreCase = true) }
        .map { "${it.str("type") ?: "?"} ${it.str("fullName") ?: "?"}: ${it.str("error") ?: "unknown error"}" }

/** Shared result reporting: balloon summary, per-component failures to the SF Log. */
fun reportSourceResult(project: Project, res: SfResult, org: String, pastVerb: String) {
    val group = NotificationGroupManager.getInstance().getNotificationGroup("SF Toolkit")
    val log = SfLog.get(project)
    val files = sourceFiles(res.resultObj())
    val failureLines = sourceFailureLines(files)

    if (res.ok && failureLines.isEmpty()) {
        val summary = if (files.isNotEmpty()) "${files.size} component file(s)" else "done"
        group.createNotification("$pastVerb $summary — $org", NotificationType.INFORMATION).notify(project)
        return
    }

    val allLines = failureLines.ifEmpty { listOf(res.errorMessage()) }
    allLines.forEach { log.error(it) }
    group.createNotification(
        "$pastVerb failed — $org",
        allLines.take(3).joinToString("<br/>") +
            (if (allLines.size > 3) "<br/>…and ${allLines.size - 3} more (see SF Log)" else ""),
        NotificationType.ERROR,
    ).notify(project)
}

class RetrieveAction : SourceCliAction("Retrieving from org…", "Retrieved") {
    override val refreshVfsAfter = true
    override fun cliArgs(org: String, sourceDirArgs: List<String>) =
        listOf("project", "retrieve", "start", "-o", org) + sourceDirArgs
}

class DeployAction : SourceCliAction("Deploying to org…", "Deployed") {
    override fun cliArgs(org: String, sourceDirArgs: List<String>) =
        listOf("project", "deploy", "start", "-o", org) + sourceDirArgs
}

class ValidateAction : SourceCliAction("Validating deploy (dry run)…", "Validated") {
    override fun cliArgs(org: String, sourceDirArgs: List<String>) =
        listOf("project", "deploy", "start", "--dry-run", "-o", org) + sourceDirArgs
}
