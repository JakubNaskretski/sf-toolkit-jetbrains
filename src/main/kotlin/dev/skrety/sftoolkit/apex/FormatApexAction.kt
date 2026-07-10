package dev.skrety.sftoolkit.apex

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.SystemInfo
import java.nio.charset.StandardCharsets

/**
 * Formats the current Apex file with Prettier's Apex plugin via npx — the LS doesn't
 * advertise formatting, so this fills IC2's formatter gap without writing one.
 * First run downloads the packages (npx --yes); later runs hit the npx cache.
 */
class FormatApexAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null &&
            e.getData(CommonDataKeys.EDITOR) != null &&
            file?.isWritable == true &&
            (file.extension == "cls" || file.extension == "trigger")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val group = NotificationGroupManager.getInstance().getNotificationGroup("SF Toolkit")

        val npx = sequenceOf(if (SystemInfo.isWindows) "npx.cmd" else "npx", "npx")
            .distinct()
            .mapNotNull { PathEnvironmentVariableUtil.findInPath(it) }
            .firstOrNull()
        if (npx == null) {
            group.createNotification(
                "npx not found",
                "Formatting uses Prettier via npx — install Node.js and retry.",
                NotificationType.WARNING,
            ).notify(project)
            return
        }

        val source = editor.document.text
        val sourceStamp = editor.document.modificationStamp
        object : Task.Backgroundable(project, "Formatting ${file.name} with Prettier…", true) {
            override fun run(indicator: ProgressIndicator) {
                val cmd = GeneralCommandLine(npx.absolutePath)
                    .withParameters(
                        "--yes",
                        "--package", "prettier@3",
                        "--package", "prettier-plugin-apex@2",
                        "prettier", "--plugin=prettier-plugin-apex",
                        "--parser=apex", "--stdin-filepath", file.name,
                    )
                    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                    .withCharset(StandardCharsets.UTF_8)
                file.parent?.path?.let { cmd.withWorkDirectory(it) }
                val handler = CapturingProcessHandler(cmd)
                // Stdin on its own thread: the output readers only start inside
                // runProcessWithProgressIndicator — writing first can deadlock on a
                // full pipe with the timeout never ticking (review finding).
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        handler.processInput.use { it.write(source.toByteArray(StandardCharsets.UTF_8)) }
                    } catch (_: java.io.IOException) {
                        // process died early — runProcess reports it
                    }
                }
                // Generous timeout: the first run downloads prettier + the apex plugin.
                val output = handler.runProcessWithProgressIndicator(indicator, 300_000, true)
                when {
                    output.isCancelled -> return
                    output.exitCode != 0 || output.stdout.isBlank() -> {
                        group.createNotification(
                            "Prettier failed",
                            output.stderr.lineSequence().firstOrNull { it.isNotBlank() }
                                ?: "exit ${output.exitCode}",
                            NotificationType.ERROR,
                        ).notify(project)
                    }
                    output.stdout != source -> ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        when {
                            // User typed while Prettier ran — never clobber (review finding).
                            editor.document.modificationStamp != sourceStamp ->
                                group.createNotification(
                                    "File changed while formatting — result discarded",
                                    "Run Format with Prettier Apex again.",
                                    NotificationType.WARNING,
                                ).notify(project)
                            !editor.document.isWritable ->
                                group.createNotification(
                                    "${file.name} is read-only — formatting skipped",
                                    NotificationType.WARNING,
                                ).notify(project)
                            else -> WriteCommandAction.runWriteCommandAction(project, "Format Apex", null, {
                                editor.document.setText(output.stdout)
                            })
                        }
                    }
                }
            }
        }.queue()
    }
}
