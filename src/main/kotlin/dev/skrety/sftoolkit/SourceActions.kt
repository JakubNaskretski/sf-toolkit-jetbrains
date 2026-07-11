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
import dev.skrety.sftoolkit.results.DeployRunner
import dev.skrety.sftoolkit.results.RunKind
import dev.skrety.sftoolkit.results.RunResult
import dev.skrety.sftoolkit.results.SfResultsService
import dev.skrety.sftoolkit.results.SourceFileResult
import dev.skrety.sftoolkit.results.toSourceFileResults

/** Base for retrieve / deploy / validate over the Project-view or editor selection. */
abstract class SfSelectionAction(private val progressTitle: String) : DumbAwareAction() {

    protected abstract fun perform(
        project: Project,
        org: String,
        root: VirtualFile,
        files: List<VirtualFile>,
        indicator: ProgressIndicator,
    )

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

        object : Task.Backgroundable(project, progressTitle, true) {
            override fun run(indicator: ProgressIndicator) {
                perform(project, org, root, files, indicator)
            }
        }.queue()
    }

    protected fun refreshVfs(files: List<VirtualFile>) {
        ApplicationManager.getApplication().invokeLater {
            VfsUtil.markDirtyAndRefresh(true, true, true, *files.toTypedArray())
        }
    }

    protected fun sourceDirArgs(files: List<VirtualFile>): List<String> =
        files.flatMap { listOf("--source-dir", it.path) }
}

/** Results window + balloon, for every deploy/validate/retrieve surface. */
fun publishAndNotify(
    project: Project,
    kind: RunKind,
    org: String,
    baseDir: String?,
    files: List<SourceFileResult>,
    durationMs: Long,
    hardError: String?,
) {
    val run = RunResult(kind, org, baseDir, files, durationMs, hardError)
    SfResultsService.get(project).publish(run) // auto-shows the window on failure only
    notifySourceOutcome(
        project, org, kind.verbed, files.size,
        files.filter { it.failed }.map { it.failureLine() }, hardError,
    )
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

/** Balloon summary + per-component failures to the SF Log (also used for aggregated batches). */
fun notifySourceOutcome(
    project: Project,
    org: String,
    pastVerb: String,
    fileCount: Int,
    failureLines: List<String>,
    fallbackError: String? = null,
) {
    val group = NotificationGroupManager.getInstance().getNotificationGroup("SF Toolkit")
    if (failureLines.isEmpty() && fallbackError == null) {
        val summary = if (fileCount > 0) "$fileCount component file(s)" else "done"
        group.createNotification("$pastVerb $summary — $org", NotificationType.INFORMATION).notify(project)
        return
    }
    val log = SfLog.get(project)
    val allLines = failureLines.ifEmpty { listOf(fallbackError ?: "unknown error") }
    allLines.forEach { log.error(it) }
    group.createNotification(
        "$pastVerb failed — $org",
        allLines.take(3).joinToString("<br/>") +
            (if (allLines.size > 3) "<br/>…and ${allLines.size - 3} more (see SF Log)" else ""),
        NotificationType.ERROR,
    ).notify(project)
}

/** Retrieve stays BLOCKING — `sf project retrieve` has no async/report/resume (verified). */
class RetrieveAction : SfSelectionAction("Retrieving from org…") {
    override fun perform(
        project: Project, org: String, root: VirtualFile,
        files: List<VirtualFile>, indicator: ProgressIndicator,
    ) {
        val t0 = System.currentTimeMillis()
        val res = SfCli.get(project).execute(
            listOf("project", "retrieve", "start", "-o", org) + sourceDirArgs(files),
            indicator,
            timeoutMs = 600_000,
            workDir = root.path,
        )
        if (res.cancelled) return
        val results = toSourceFileResults(sourceFiles(res.resultObj()))
        val hardError = if (!res.ok && results.none { it.failed }) res.errorMessage() else null
        publishAndNotify(
            project, RunKind.RETRIEVE, org, root.path, results,
            System.currentTimeMillis() - t0, hardError,
        )
        if (res.ok) refreshVfs(files)
    }
}

/** Deploy/validate go async: live component/test counts and a cancel that cancels the job. */
abstract class AsyncDeployAction(
    title: String,
    private val kind: RunKind,
    private val dryRun: Boolean,
) : SfSelectionAction(title) {
    override fun perform(
        project: Project, org: String, root: VirtualFile,
        files: List<VirtualFile>, indicator: ProgressIndicator,
    ) {
        val t0 = System.currentTimeMillis()
        val outcome = DeployRunner.runAsyncDeploy(
            project, org, root.path, sourceDirArgs(files), dryRun, indicator,
        )
        if (outcome.cancelled) return
        publishAndNotify(
            project, kind, org, root.path, outcome.report?.files.orEmpty(),
            System.currentTimeMillis() - t0, outcome.hardError,
        )
    }
}

class DeployAction : AsyncDeployAction("Deploying to org…", RunKind.DEPLOY, dryRun = false)

class ValidateAction : AsyncDeployAction("Validating deploy (dry run)…", RunKind.VALIDATE, dryRun = true)
