package dev.skrety.sftoolkit.apex

import com.google.gson.JsonObject
import dev.skrety.sftoolkit.str

data class ApexTestFailure(val fullName: String, val message: String?, val stackTrace: String?)

data class ApexTestRunSummary(
    val outcome: String,
    val testsRan: Int,
    val passing: Int,
    val failing: Int,
    val skipped: Int,
    /** e.g. "180 ms" — the CLI emits unit-suffixed STRINGS; display raw, never do math on it. */
    val totalTime: String?,
    val failures: List<ApexTestFailure>,
)

/**
 * `sf apex run test --synchronous --json` envelope, verified live: result.summary +
 * result.tests[]. A failing run exits with code 100 while `result` stays fully
 * populated — exit code alone must not be treated as a hard error.
 */
fun parseApexTestRun(envelope: JsonObject?): ApexTestRunSummary? {
    val payload = envelope?.get("result")?.takeIf { it.isJsonObject }?.asJsonObject
        ?: envelope?.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        ?: return null
    val summary = payload.get("summary")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null

    fun count(key: String): Int =
        summary.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull() ?: 0

    val tests = payload.get("tests")?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { it.takeIf { el -> el.isJsonObject }?.asJsonObject }
        .orEmpty()
    val failures = tests
        .filter { it.str("Outcome").equals("Fail", ignoreCase = true) }
        .map {
            ApexTestFailure(
                fullName = it.str("FullName") ?: "?",
                message = it.str("Message")?.takeIf { m -> m.isNotBlank() },
                stackTrace = it.str("StackTrace")?.takeIf { s -> s.isNotBlank() },
            )
        }
    return ApexTestRunSummary(
        outcome = summary.str("outcome") ?: "?",
        testsRan = count("testsRan"),
        passing = count("passing"),
        failing = count("failing"),
        skipped = count("skipped"),
        totalTime = summary.str("testTotalTime"),
        failures = failures,
    )
}
