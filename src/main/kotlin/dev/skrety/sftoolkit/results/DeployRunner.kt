package dev.skrety.sftoolkit.results

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import dev.skrety.sftoolkit.SfCli

data class DeployOutcome(
    val report: DeployReport?,
    val cancelled: Boolean,
    val hardError: String?,
)

/**
 * The one shared async-deploy helper: `deploy start --async` → poll `deploy report`
 * every ~2.5s, driving the progress indicator; cancel maps to `deploy cancel`.
 * Used by both the file actions (SourceActions) and the metadata browser's chunked
 * path — the fraction window lets each chunk own its slice of the bar.
 *
 * All three CLI calls MUST run with workDir = the SFDX project root — report/cancel
 * return InvalidProjectWorkspaceError anywhere else (live-verified).
 */
object DeployRunner {
    private const val POLL_MS = 2_500L
    private const val TICK_MS = 200L
    private const val MAX_WAIT_MS = 1_800_000L // 30 min guard; report a timeout, never hang

    fun runAsyncDeploy(
        project: Project,
        org: String,
        workDir: String,
        deployArgs: List<String>,
        dryRun: Boolean,
        indicator: ProgressIndicator,
        fractionBase: Double = 0.0,
        fractionSpan: Double = 1.0,
    ): DeployOutcome {
        val cli = SfCli.get(project)
        val startArgs = listOf("project", "deploy", "start", "--async", "-o", org) +
            (if (dryRun) listOf("--dry-run") else emptyList()) + deployArgs
        val startRes = cli.execute(startArgs, indicator, timeoutMs = 120_000, workDir = workDir)
        if (startRes.cancelled) return DeployOutcome(null, cancelled = true, hardError = null)
        val jobId = parseDeployJobId(startRes.json)
            ?: return DeployOutcome(null, false, startRes.errorMessage())

        indicator.isIndeterminate = false
        var last: DeployReport? = null
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        while (true) {
            if (indicator.isCanceled) {
                cancelJob(cli, org, jobId, workDir)
                return DeployOutcome(last, true, null)
            }
            if (System.currentTimeMillis() > deadline) {
                return DeployOutcome(last, false, "Timed out after 30 min waiting for deploy $jobId")
            }
            val rep = cli.execute(
                listOf("project", "deploy", "report", "-i", jobId, "-o", org),
                indicator,
                timeoutMs = 60_000,
                workDir = workDir,
            )
            if (rep.cancelled) {
                cancelJob(cli, org, jobId, workDir)
                return DeployOutcome(last, true, null)
            }
            val report = parseDeployReport(rep.json)
            if (report != null) {
                last = report
                if (report.componentsTotal > 0) { // 0 while Pending — no fake progress
                    val frac = report.componentsDeployed.toDouble() / report.componentsTotal
                    indicator.fraction = fractionBase + fractionSpan * frac.coerceIn(0.0, 1.0)
                }
                indicator.text = statusText(report)
                if (report.done) {
                    // Failed with zero per-file rows (e.g. test failures on validate) must
                    // not read as success downstream.
                    val hard = if (!report.success && report.files.none { it.failed }) {
                        "${report.status}: ${report.componentErrors} component error(s), " +
                            "${report.testErrors} test error(s)"
                    } else null
                    return DeployOutcome(report, false, hard)
                }
            }
            var slept = 0L // interruptible sleep — cancel reacts within TICK_MS
            while (slept < POLL_MS) {
                if (indicator.isCanceled) {
                    cancelJob(cli, org, jobId, workDir)
                    return DeployOutcome(last, true, null)
                }
                Thread.sleep(TICK_MS)
                slept += TICK_MS
            }
        }
    }

    /** Best-effort — the org may already be committing; matches sf semantics. */
    private fun cancelJob(cli: SfCli, org: String, jobId: String, workDir: String) {
        cli.execute(
            listOf("project", "deploy", "cancel", "-i", jobId, "-o", org, "--async"),
            indicator = null,
            timeoutMs = 30_000,
            workDir = workDir,
            quiet = true,
        )
    }

    fun statusText(r: DeployReport): String {
        val comp = "${r.componentsDeployed}/${r.componentsTotal} components"
        val tests = if (r.testsTotal > 0) ", tests ${r.testsDone}/${r.testsTotal}" else ""
        val errs = if (r.componentErrors > 0) ", ${r.componentErrors} error(s)" else ""
        return "${r.status}: $comp$tests$errs"
    }
}
