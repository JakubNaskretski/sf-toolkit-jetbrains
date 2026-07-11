package dev.skrety.sftoolkit.lsp

import com.google.gson.JsonParser
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.util.io.HttpRequests
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * Fetches Salesforce's Apex Language Server (the VS Code extension's
 * apex-jorje-lsp.jar) from open-vsx into the plugin's managed location, so Apex
 * completion works without VS Code ever being installed. User-initiated download.
 */
class DownloadApexLsAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        object : Task.Backgroundable(project, "Downloading Apex Language Server…", true) {
            override fun run(indicator: ProgressIndicator) {
                val group = NotificationGroupManager.getInstance().getNotificationGroup("SF Toolkit")
                try {
                    indicator.text = "Resolving latest version…"
                    val meta = HttpRequests.request(API_URL).readString(indicator)
                    val json = JsonParser.parseString(meta).asJsonObject
                    val version = json.get("version")?.asString ?: "?"
                    val downloadUrl = json.getAsJsonObject("files")?.get("download")?.asString
                        ?: throw IllegalStateException("open-vsx metadata has no download URL")

                    indicator.text = "Downloading VS Code Apex extension $version…"
                    val vsix = Files.createTempFile("sf-toolkit-apex-ext-", ".vsix")
                    try {
                        HttpRequests.request(downloadUrl).saveToFile(vsix, indicator)
                        indicator.text = "Extracting apex-jorje-lsp.jar…"
                        val target = managedApexLspJar()
                        Files.createDirectories(target.parent)
                        ZipFile(vsix.toFile()).use { zip ->
                            val entry = zip.getEntry(JAR_ENTRY)
                                ?: throw IllegalStateException("$JAR_ENTRY missing from the extension package")
                            zip.getInputStream(entry).use { input ->
                                Files.newOutputStream(target).use { input.copyTo(it) }
                            }
                        }
                        val started = tryStartApexLs(project)
                        group.createNotification(
                            "Apex Language Server installed ($version)",
                            if (started) "Apex completion is active — open a .cls file."
                            else "Reopen the project (or restart the IDE) to activate Apex completion.",
                            NotificationType.INFORMATION,
                        ).notify(project)
                    } finally {
                        Files.deleteIfExists(vsix)
                    }
                } catch (t: Throwable) {
                    if (indicator.isCanceled) return
                    group.createNotification(
                        "Apex Language Server download failed",
                        t.message ?: t.javaClass.simpleName,
                        NotificationType.ERROR,
                    ).notify(project)
                }
            }
        }.queue()
    }

    /**
     * Start the LS right away instead of demanding a restart. Reflective: LSP4IJ is an
     * optional dependency, so its classes may be absent from this classloader at runtime.
     */
    private fun tryStartApexLs(project: com.intellij.openapi.project.Project): Boolean = try {
        val mgr = Class.forName("com.redhat.devtools.lsp4ij.LanguageServerManager")
        val instance = mgr.getMethod("getInstance", com.intellij.openapi.project.Project::class.java)
            .invoke(null, project)
        mgr.getMethod("start", String::class.java).invoke(instance, "apexLsp")
        true
    } catch (_: Throwable) {
        false
    }

    companion object {
        private const val API_URL = "https://open-vsx.org/api/salesforce/salesforcedx-vscode-apex/latest"
        private const val JAR_ENTRY = "extension/dist/apex-jorje-lsp.jar"
    }
}
