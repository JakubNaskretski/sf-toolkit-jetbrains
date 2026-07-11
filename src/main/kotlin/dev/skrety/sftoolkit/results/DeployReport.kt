package dev.skrety.sftoolkit.results

import com.google.gson.JsonObject
import dev.skrety.sftoolkit.bool
import dev.skrety.sftoolkit.sourceFiles
import dev.skrety.sftoolkit.str

/** One component file's per-run outcome. Line/col are CLI 1-based. */
data class SourceFileResult(
    val type: String,
    val fullName: String,
    val state: String, // Created/Changed/Unchanged/Deleted/Failed
    val filePath: String?,
    val error: String?,
    val line: Int?,
    val column: Int?,
) {
    val failed: Boolean get() = state.equals("Failed", ignoreCase = true)
    fun failureLine(): String = "$type $fullName: ${error ?: "unknown error"}"
}

/**
 * One `sf project deploy report` snapshot. Live-verified envelope (2.137.7):
 * result.{id,status,done,success,numberComponents*,numberTests*} + result.files[].
 * The report's process EXIT CODE is 0 even for a Failed deploy — outcome must be
 * read from these fields, never from SfResult.ok.
 */
data class DeployReport(
    val status: String, // Queued/Pending/InProgress/Succeeded/Failed/Canceled
    val done: Boolean,
    val success: Boolean,
    val componentsDeployed: Int,
    val componentsTotal: Int, // 0 while Pending — guard divisions
    val componentErrors: Int,
    val testsDone: Int,
    val testsTotal: Int,
    val testErrors: Int,
    val jobId: String?,
    val files: List<SourceFileResult>,
)

enum class RunKind(val verbed: String) {
    DEPLOY("Deployed"),
    VALIDATE("Validated"),
    RETRIEVE("Retrieved"),
}

/** Aggregate of one user action (may span several CLI calls / metadata-browser chunks). */
data class RunResult(
    val kind: RunKind,
    val org: String,
    val baseDir: String?, // DX root, for resolving relative filePaths
    val files: List<SourceFileResult>,
    val durationMs: Long,
    val hardError: String?, // CLI/transport/test failure with no per-file rows
) {
    val failed: Int get() = files.count { it.failed }
    val succeeded: Int get() = files.size - failed
    val hasFailures: Boolean get() = failed > 0 || hardError != null

    fun summaryLine(): String {
        val d = "%.1fs".format(durationMs / 1000.0)
        val verb = kind.verbed.lowercase()
        val head = if (kind == RunKind.RETRIEVE) "${files.size} file(s) $verb"
        else "$succeeded $verb, $failed failed"
        val err = if (hardError != null) " · ${hardError.take(120)}" else ""
        return "$head · $org · $d$err"
    }
}

/** Job id from `sf project deploy start --async --json` (live-verified: result.id). */
fun parseDeployJobId(startEnvelope: JsonObject?): String? {
    val result = startEnvelope?.get("result")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    return result.str("id") ?: result.str("deployId")
}

/**
 * `sf project deploy report --json` → [DeployReport], or null for error envelopes
 * (no `result` key — e.g. CannotCancelDeployError, InvalidProjectWorkspaceError).
 */
fun parseDeployReport(reportEnvelope: JsonObject?): DeployReport? {
    val r = reportEnvelope?.get("result")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    // The CLI mixes JSON types (numberFiles/zipSize are strings, counters numbers) —
    // str() stringifies any primitive, so this reads both.
    fun int(key: String) = r.str(key)?.toIntOrNull() ?: 0
    return DeployReport(
        status = r.str("status") ?: "Unknown",
        done = r.bool("done") ?: false,
        success = r.bool("success") ?: false,
        componentsDeployed = int("numberComponentsDeployed"),
        componentsTotal = int("numberComponentsTotal"),
        componentErrors = int("numberComponentErrors"),
        testsDone = int("numberTestsCompleted"),
        testsTotal = int("numberTestsTotal"),
        testErrors = int("numberTestErrors"),
        jobId = r.str("id"),
        files = toSourceFileResults(sourceFiles(r)),
    )
}

/**
 * files[] rows → [SourceFileResult]. One row per LOCAL FILE (.cls and .cls-meta.xml are
 * separate rows; one Failed row per compile error). filePath is absolute in live
 * captures; line/col were numbers but are read tolerantly (this CLI family has emitted
 * them as strings elsewhere).
 */
fun toSourceFileResults(files: List<JsonObject>): List<SourceFileResult> =
    files.map {
        SourceFileResult(
            type = it.str("type") ?: "",
            fullName = it.str("fullName") ?: "?",
            state = it.str("state") ?: "?",
            filePath = it.str("filePath"),
            error = it.str("error"),
            line = it.str("lineNumber")?.toIntOrNull(),
            column = it.str("columnNumber")?.toIntOrNull(),
        )
    }
