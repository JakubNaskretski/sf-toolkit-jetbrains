package dev.skrety.sftoolkit

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import dev.skrety.sftoolkit.schema.OrgSchemaService

/**
 * Tools → SF Toolkit → Run Health Check: one report of every integration that can
 * silently fail in the field. Full detail lands in the SF Log for copy/paste.
 */
class HealthCheckAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        object : Task.Backgroundable(project, "SF Toolkit health check…", false) {
            override fun run(indicator: ProgressIndicator) {
                val lines = buildList {
                    add("SF Toolkit: " + SfHealth.pluginState("dev.skrety.sf-toolkit"))
                    add("sf CLI: " + (SfCli.get(project).resolveBinary() ?: "NOT FOUND (set in Settings → Tools → SF Toolkit)"))
                    add("TextMate Bundles plugin: " + SfHealth.pluginState("org.jetbrains.plugins.textmate"))
                    add("Apex grammar (.cls highlighting): " + if (SfHealth.grammarActive("Probe.cls")) "ACTIVE" else "NOT RESOLVED")
                    add("SOQL grammar (.soql highlighting): " + if (SfHealth.grammarActive("Probe.soql")) "ACTIVE" else "NOT RESOLVED")
                    add("Search scopes: ${SfHealth.scopesCount()}/4 registered")
                    add("LSP4IJ plugin: " + SfHealth.pluginState("com.redhat.devtools.lsp4ij"))
                    add("Apex Language Server jar: " + (safeLspJar() ?: "NOT FOUND (install the VS Code Apex extension or set the path in settings)"))
                    val org = OrgService.get(project).current
                    add("Current org: " + (org ?: "none selected"))
                    if (org != null) {
                        val objects = OrgSchemaService.get(project).objectNames(org)?.size
                        add("Schema cache: " + (objects?.let { "$it objects" } ?: "empty — click Sync Schema in the SOQL window"))
                    }
                }
                val log = SfLog.get(project)
                log.log("===== SF Toolkit health check =====")
                lines.forEach { if (it.contains("NOT") || it.contains("DISABLED")) log.warn(it) else log.ok(it) }
                val problems = lines.count { it.contains("NOT") || it.contains("DISABLED") }
                NotificationGroupManager.getInstance().getNotificationGroup("SF Toolkit")
                    .createNotification(
                        if (problems == 0) "Health check: all good" else "Health check: $problems problem(s)",
                        lines.joinToString("<br/>"),
                        if (problems == 0) NotificationType.INFORMATION else NotificationType.WARNING,
                    )
                    .notify(project)
            }
        }.queue()
    }

    private fun safeLspJar(): String? = try {
        dev.skrety.sftoolkit.lsp.findApexLspJar()?.toString()
    } catch (_: Throwable) {
        null
    }
}
