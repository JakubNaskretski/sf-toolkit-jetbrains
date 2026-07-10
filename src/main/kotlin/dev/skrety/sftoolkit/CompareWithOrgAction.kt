package dev.skrety.sftoolkit

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
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
import com.intellij.openapi.vfs.VirtualFile

/** Diffs the local file against the component body in the org (Tooling API query — no temp files). */
class CompareWithOrgAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = project != null && file != null && !file.isDirectory &&
            toolingRefFor(file.name, file.parent?.name) != null &&
            findSfdxRoot(file, project.guessProjectDir()) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val ref = toolingRefFor(file.name, file.parent?.name) ?: return
        val org = OrgService.get(project).requireCurrent() ?: return
        val root = findSfdxRoot(file, project.guessProjectDir()) ?: return
        compareFileWithOrgInBackground(project, file, ref, org, root.path)
    }
}

/** Shared by the context action and the metadata browser. */
fun compareFileWithOrgInBackground(
    project: Project,
    file: VirtualFile,
    ref: ToolingRef,
    org: String,
    sfdxRootPath: String,
    onFinished: (() -> Unit)? = null,
) {
    object : Task.Backgroundable(project, "Comparing ${ref.name} with $org…", true) {
        override fun onFinished() {
            onFinished?.invoke()
        }

        override fun run(indicator: ProgressIndicator) {
            val query = "SELECT ${ref.type.bodyField}, NamespacePrefix FROM ${ref.type.sObject} " +
                "WHERE Name = '${ref.name}' LIMIT 5"
            val res = SfCli.get(project).execute(
                listOf("data", "query", "--query", query, "--use-tooling-api", "-o", org),
                indicator,
                workDir = sfdxRootPath,
            )
            if (res.cancelled) return
            val records = res.resultObj()?.get("records")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { el -> el.isJsonObject }?.asJsonObject }
                .orEmpty()
            val group = NotificationGroupManager.getInstance().getNotificationGroup("SF Toolkit")
            if (!res.ok) {
                group.createNotification("Compare failed — $org", res.errorMessage(), NotificationType.ERROR)
                    .notify(project)
                return
            }
            // Prefer the org's own (namespace-less) component over managed-package ones.
            val record = records.firstOrNull { it.get("NamespacePrefix")?.isJsonNull != false }
                ?: records.firstOrNull()
            val body = record?.str(ref.type.bodyField)
            if (body == null) {
                group.createNotification(
                    "${ref.type.sObject} ${ref.name} not found in $org",
                    NotificationType.WARNING,
                ).notify(project)
                return
            }
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) showDiff(project, file, body, org, ref)
            }
        }
    }.queue()
}

private fun showDiff(project: Project, file: VirtualFile, orgBody: String, org: String, ref: ToolingRef) {
    val factory = DiffContentFactory.getInstance()
    val request = SimpleDiffRequest(
        "${ref.name}: local vs $org",
        factory.create(project, file),
        factory.create(project, orgBody, file.fileType),
        "Local: ${file.name}",
        "Org: $org",
    )
    DiffManager.getInstance().showDiff(project, request)
}
