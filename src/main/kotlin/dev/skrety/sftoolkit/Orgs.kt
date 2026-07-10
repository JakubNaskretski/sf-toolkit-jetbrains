package dev.skrety.sftoolkit

import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.WindowManager

data class SfOrg(
    val username: String,
    val alias: String?,
    val connected: Boolean,
    val isScratch: Boolean,
    val isDefault: Boolean,
) {
    val display: String get() = alias ?: username
}

/**
 * `sf org list --json` groups orgs under version-dependent keys (`other`, `sandboxes`,
 * `nonScratchOrgs`, `scratchOrgs`, `devHubs`, …) — walk every array and dedupe by username.
 */
fun parseOrgList(result: JsonObject?): List<SfOrg> {
    if (result == null) return emptyList()
    val seen = LinkedHashMap<String, SfOrg>()
    for ((_, value) in result.entrySet()) {
        if (!value.isJsonArray) continue
        for (el in value.asJsonArray) {
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val username = o.str("username") ?: continue
            seen.getOrPut(username) {
                SfOrg(
                    username = username,
                    alias = o.str("alias")?.takeIf { it.isNotBlank() },
                    connected = o.str("connectedStatus")?.equals("Connected", ignoreCase = true)
                        ?: (o.bool("isScratch") == true),
                    isScratch = o.bool("isScratch") == true,
                    isDefault = o.bool("isDefaultUsername") == true,
                )
            }
        }
    }
    return seen.values.toList()
}

@Service(Service.Level.PROJECT)
@State(name = "SfToolkitOrg", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class OrgService(private val project: Project) :
    SimplePersistentStateComponent<OrgService.MyState>(MyState()) {

    class MyState : BaseState() {
        var current by string()
    }

    @Volatile
    var orgs: List<SfOrg> = emptyList()
        private set

    private val changeListeners = java.util.concurrent.CopyOnWriteArrayList<Runnable>()

    /** Listener fires on the EDT whenever the org list or current org changes. */
    fun addChangeListener(listener: Runnable) {
        changeListeners += listener
    }

    fun removeChangeListener(listener: Runnable) {
        changeListeners -= listener
    }

    /** Alias (or username) passed to `sf -o`. Persisted per-project in workspace.xml. */
    var current: String?
        get() = state.current
        set(value) {
            state.current = value
            notifyChangedOnEdt()
        }

    /** Blocking — call from a background thread. [quiet] for unsolicited (startup) refreshes. */
    fun refreshOrgs(indicator: ProgressIndicator? = null, quiet: Boolean = false): List<SfOrg> {
        val res = SfCli.get(project).execute(listOf("org", "list"), indicator, quiet = quiet)
        if (res.ok) {
            orgs = parseOrgList(res.resultObj())
            notifyChangedOnEdt()
        }
        return orgs
    }

    /** Current org or null after telling the user how to pick one. */
    fun requireCurrent(): String? {
        current?.let { return it }
        NotificationGroupManager.getInstance().getNotificationGroup("SF Toolkit")
            .createNotification(
                "No Salesforce org selected",
                "Click the SF widget in the status bar to pick or log in to an org.",
                NotificationType.WARNING,
            )
            .notify(project)
        return null
    }

    private fun notifyChangedOnEdt() {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                WindowManager.getInstance().getStatusBar(project)?.updateWidget(ORG_WIDGET_ID)
                changeListeners.forEach { it.run() }
            }
        }
    }

    companion object {
        const val ORG_WIDGET_ID = "SfToolkitOrg"

        fun get(project: Project): OrgService = project.service()
    }
}

fun refreshOrgsInBackground(project: Project, quiet: Boolean = false, onDone: (List<SfOrg>) -> Unit = {}) {
    object : Task.Backgroundable(project, "Refreshing Salesforce orgs", true) {
        override fun run(indicator: ProgressIndicator) {
            val orgs = OrgService.get(project).refreshOrgs(indicator, quiet)
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) onDone(orgs)
            }
        }
    }.queue()
}

class SfStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Unsolicited: never nag non-Salesforce projects / machines without the CLI.
        refreshOrgsInBackground(project, quiet = true)
    }
}
