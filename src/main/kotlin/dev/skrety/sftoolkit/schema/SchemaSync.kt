package dev.skrety.sftoolkit.schema

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import dev.skrety.sftoolkit.OrgService

/**
 * Shared quiet schema sync behind SOQL/Apex completion. One flight per org across all
 * panes and tabs; an org whose sync failed stops re-queueing until a manual refresh
 * (field bug: silent forever-retry behind a status line that promised success).
 */
object SchemaSync {
    private val syncing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun autoSyncIfNeeded(project: Project, org: String?, onStatus: (String) -> Unit = {}) {
        if (org == null) return
        val schema = OrgSchemaService.get(project)
        if (schema.objectNames(org) != null || org in failed || !syncing.add(org)) return
        object : Task.Backgroundable(project, "Caching org schema for completion", true) {
            private var count: Int? = null

            override fun run(indicator: ProgressIndicator) {
                count = schema.syncObjects(org, indicator)
            }

            override fun onFinished() {
                syncing.remove(org)
                val n = count
                if (n != null) {
                    onStatus("Schema cached: $n objects — completion ready")
                } else {
                    failed.add(org)
                    onStatus(
                        "Schema sync failed for $org — completion unavailable " +
                            "(details in SF Log; Tools → SF Toolkit → Refresh Org Schema retries)",
                    )
                }
            }
        }.queue()
    }

    /** Manual: wipe the completion caches and resync, with a visible outcome either way. */
    fun refresh(project: Project, org: String) {
        failed.remove(org)
        if (!syncing.add(org)) return
        object : Task.Backgroundable(project, "Refreshing org schema for $org", true) {
            private var count: Int? = null

            override fun run(indicator: ProgressIndicator) {
                val service = OrgSchemaService.get(project)
                service.store(org).clearSchemaCache()
                count = service.syncObjects(org, indicator)
            }

            override fun onFinished() {
                syncing.remove(org)
                val n = count
                val group = NotificationGroupManager.getInstance().getNotificationGroup("SF Toolkit")
                if (n != null) {
                    group.createNotification(
                        "Org schema refreshed",
                        "$n objects cached for $org — completion is current.",
                        NotificationType.INFORMATION,
                    ).notify(project)
                } else {
                    group.createNotification(
                        "Org schema refresh failed",
                        "Could not list sObjects for $org — see SF Log.",
                        NotificationType.WARNING,
                    ).notify(project)
                }
            }
        }.queue()
    }
}

/** Tools → SF Toolkit → Refresh Org Schema (new custom objects/fields show up now, not in 7 days). */
class RefreshOrgSchemaAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val org = OrgService.get(project).requireCurrent() ?: return
        SchemaSync.refresh(project, org)
    }
}
