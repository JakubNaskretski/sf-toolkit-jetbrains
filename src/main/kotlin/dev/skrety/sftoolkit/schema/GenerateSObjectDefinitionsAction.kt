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
import dev.skrety.sftoolkit.SfCli
import dev.skrety.sftoolkit.SfLog
import dev.skrety.sftoolkit.isValidApiName
import java.nio.file.Files
import java.nio.file.Path

/**
 * Generates Apex faux classes into <sfdx>/.sfdx/tools/sobjects so the Apex Language
 * Server completes org sObject fields inside .cls files — the VS Code mechanism,
 * fed from our describe cache. Scope: all custom objects + common standard objects +
 * everything already cached. .sfdx/ stays untracked (guarded below).
 */
class GenerateSObjectDefinitionsAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val org = OrgService.get(project).requireCurrent() ?: return
        val root = project.basePath?.let(Path::of)
            ?.takeIf { Files.isRegularFile(it.resolve("sfdx-project.json")) }
        val group = NotificationGroupManager.getInstance().getNotificationGroup("SF Toolkit")
        if (root == null) {
            group.createNotification(
                "Not an SFDX project",
                "sObject definitions must be generated inside an SFDX project.",
                NotificationType.WARNING,
            ).notify(project)
            return
        }

        object : Task.Backgroundable(project, "Generating Apex sObject definitions…", true) {
            override fun run(indicator: ProgressIndicator) {
                val schemaService = OrgSchemaService.get(project)
                val log = SfLog.get(project)

                indicator.text = "Listing custom objects…"
                val customRes = SfCli.get(project).execute(
                    listOf("sobject", "list", "--sobject", "custom", "-o", org),
                    indicator,
                )
                if (customRes.cancelled) return
                val customNames = customRes.json?.get("result")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { el -> el.takeIf { it.isJsonPrimitive }?.asString }
                    .orEmpty()

                val targets = (customNames + STANDARD_OBJECTS)
                    .filter { isValidApiName(it) }
                    .distinct()
                var described = 0
                var failed = 0
                for ((i, name) in targets.withIndex()) {
                    indicator.checkCanceled()
                    indicator.text = "Describing $name…"
                    indicator.fraction = i.toDouble() / targets.size
                    if (schemaService.ensureDescribe(org, name, indicator) != null) described++
                    else failed++
                }

                val schemas = schemaService.store(org).allCachedDescribes()
                indicator.text = "Writing faux classes…"
                val standardDir = root.resolve(".sfdx/tools/sobjects/standardObjects")
                val customDir = root.resolve(".sfdx/tools/sobjects/customObjects")
                // Regenerate cleanly — these two dirs are exclusively ours/VS Code's output.
                // Symlink guard: never follow a link out of the project (defense in depth).
                for (dir in listOf(standardDir, customDir)) {
                    if (Files.isSymbolicLink(dir)) {
                        log.error("$dir is a symlink — refusing to regenerate sObject definitions")
                        return
                    }
                    dir.toFile().deleteRecursively()
                }
                Files.createDirectories(standardDir)
                Files.createDirectories(customDir)
                for (schema in schemas) {
                    val dir = if (FauxClassGenerator.isCustom(schema.name)) customDir else standardDir
                    Files.writeString(dir.resolve("${schema.name}.cls"), FauxClassGenerator.generate(schema))
                }

                val ignoreNote = ensureSfdxIgnored(root)
                if (failed > 0) log.warn("$failed object(s) failed to describe (see above)")
                group.createNotification(
                    "Generated ${schemas.size} sObject definitions",
                    "Written to .sfdx/tools/sobjects (untracked)." +
                        (ignoreNote ?: "") +
                        "<br/>If Apex completion doesn't show org fields, reopen the project " +
                        "(the language server indexes on start).",
                    NotificationType.INFORMATION,
                ).notify(project)
            }
        }.queue()
    }

    /** User rule: nothing generated may enter VCS. Appends .sfdx/ to .gitignore if missing. */
    private fun ensureSfdxIgnored(root: Path): String? {
        val gitignore = root.resolve(".gitignore")
        return try {
            if (!Files.isRegularFile(gitignore)) return null
            val lines = Files.readAllLines(gitignore).map { it.trim() }
            if (lines.any { it == ".sfdx" || it == ".sfdx/" || it == "**/.sfdx/" }) null
            else {
                Files.writeString(gitignore, Files.readString(gitignore).trimEnd() + "\n.sfdx/\n")
                "<br/>Added .sfdx/ to .gitignore so definitions never enter version control."
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val STANDARD_OBJECTS = listOf(
            "Account", "Contact", "Lead", "Opportunity", "OpportunityLineItem", "Case",
            "User", "Task", "Event", "Campaign", "CampaignMember", "Product2",
            "Pricebook2", "PricebookEntry", "Quote", "QuoteLineItem", "Order", "OrderItem",
            "Asset", "Contract", "ContentDocument", "ContentVersion", "Note", "Group",
            "Profile", "RecordType",
        )
    }
}
