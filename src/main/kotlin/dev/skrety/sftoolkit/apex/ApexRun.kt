package dev.skrety.sftoolkit.apex

import com.google.gson.JsonObject
import dev.skrety.sftoolkit.bool
import dev.skrety.sftoolkit.str

data class ApexRunOutcome(
    val success: Boolean,
    val compiled: Boolean,
    val compileProblem: String?,
    val exceptionMessage: String?,
    val exceptionStackTrace: String?,
    val line: Int?,
    val column: Int?,
    val logs: String?,
)

/**
 * `sf apex run --json` envelope, verified live: on success the payload is in `result`
 * (line/column are NUMBERS, -1 when absent); on compile/runtime failure the CLI exits 1
 * and the payload moves to top-level `data` (line/column become STRINGS).
 */
fun parseApexRunOutcome(envelope: JsonObject?): ApexRunOutcome? {
    val payload = envelope?.get("result")
        ?.takeIf { it.isJsonObject && it.asJsonObject.entrySet().isNotEmpty() }?.asJsonObject
        ?: envelope?.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        ?: return null
    if (payload.get("success")?.isJsonPrimitive != true) return null

    fun position(key: String): Int? = payload.get(key)
        ?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull()?.takeIf { it >= 0 }

    return ApexRunOutcome(
        success = payload.bool("success") == true,
        compiled = payload.bool("compiled") == true,
        compileProblem = payload.str("compileProblem")?.takeIf { it.isNotBlank() },
        exceptionMessage = payload.str("exceptionMessage")?.takeIf { it.isNotBlank() },
        exceptionStackTrace = payload.str("exceptionStackTrace")?.takeIf { it.isNotBlank() },
        line = position("line"),
        column = position("column"),
        logs = payload.str("logs")?.takeIf { it.isNotBlank() },
    )
}

fun formatApexRunOutcome(o: ApexRunOutcome): String = buildString {
    val position = o.line?.let { l -> " (line $l" + (o.column?.let { ", col $it" } ?: "") + ")" } ?: ""
    when {
        o.success -> appendLine("Success")
        !o.compiled -> {
            appendLine("Compile error$position")
            o.compileProblem?.let { appendLine(it) }
        }
        else -> {
            appendLine("Runtime error$position")
            o.exceptionMessage?.let { appendLine(it) }
            o.exceptionStackTrace?.let { appendLine(it) }
        }
    }
    o.logs?.let {
        appendLine()
        appendLine("--- Debug log ---")
        append(it)
    }
}
