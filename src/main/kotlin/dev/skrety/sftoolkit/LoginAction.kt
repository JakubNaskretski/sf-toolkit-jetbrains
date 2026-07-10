package dev.skrety.sftoolkit

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

class LoginAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        run(e.project ?: return)
    }

    companion object {
        fun run(project: Project) {
            val url = Messages.showInputDialog(
                project,
                "Login URL (production/dev orgs: login.salesforce.com, sandboxes: test.salesforce.com)",
                "Log In to Salesforce Org",
                null,
                "https://login.salesforce.com",
                null,
            ) ?: return
            val alias = Messages.showInputDialog(
                project,
                "Alias for this org (optional)",
                "Log In to Salesforce Org",
                null,
                "",
                null,
            ) ?: return

            object : Task.Backgroundable(project, "Waiting for Salesforce login in browser…", true) {
                override fun run(indicator: ProgressIndicator) {
                    val args = mutableListOf("org", "login", "web", "--instance-url", url.trim())
                    if (alias.isNotBlank()) {
                        args += "--alias"
                        args += alias.trim()
                    }
                    // CLI opens the browser and blocks until the OAuth flow completes.
                    val res = SfCli.get(project).execute(args, indicator, timeoutMs = 600_000)
                    val group = NotificationGroupManager.getInstance().getNotificationGroup("SF Toolkit")
                    if (res.ok) {
                        val username = res.resultObj()?.str("username")
                        OrgService.get(project).current = alias.trim().ifBlank { username }
                        group.createNotification(
                            "Logged in to Salesforce org",
                            username ?: "",
                            NotificationType.INFORMATION,
                        ).notify(project)
                        refreshOrgsInBackground(project)
                    } else if (!res.cancelled) {
                        group.createNotification(
                            "Salesforce login failed",
                            res.errorMessage(),
                            NotificationType.ERROR,
                        ).notify(project)
                    }
                }
            }.queue()
        }
    }
}
