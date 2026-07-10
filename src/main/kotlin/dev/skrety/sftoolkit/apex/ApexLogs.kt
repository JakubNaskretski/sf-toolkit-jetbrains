package dev.skrety.sftoolkit.apex

import com.google.gson.JsonElement
import dev.skrety.sftoolkit.str

data class ApexLogEntry(
    val id: String,
    val startTime: String,
    val operation: String,
    val status: String,
    val lengthBytes: Int,
    val durationMs: Long,
    val user: String?,
)

/** `sf apex list log --json` → result array (shape verified live 2026-07-10). */
fun parseLogList(resultEl: JsonElement?): List<ApexLogEntry> {
    if (resultEl == null || !resultEl.isJsonArray) return emptyList()
    return resultEl.asJsonArray
        .mapNotNull { it.takeIf { el -> el.isJsonObject }?.asJsonObject }
        .mapNotNull { o ->
            val id = o.str("Id") ?: return@mapNotNull null
            ApexLogEntry(
                id = id,
                startTime = o.str("StartTime") ?: "",
                operation = o.str("Operation") ?: "",
                status = o.str("Status") ?: "",
                lengthBytes = o.get("LogLength")?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull() ?: 0,
                durationMs = o.get("DurationMilliseconds")?.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull() ?: 0,
                user = o.get("LogUser")?.takeIf { it.isJsonObject }?.asJsonObject?.str("Name"),
            )
        }
        .sortedByDescending { it.startTime }
}

/** `sf apex get log -i <id> --json` → result: [{ "log": "<text>" }] (verified live). */
fun parseLogBody(resultEl: JsonElement?): String? {
    val item = when {
        resultEl == null -> return null
        resultEl.isJsonArray -> resultEl.asJsonArray.firstOrNull()
        else -> resultEl
    } ?: return null
    return when {
        item.isJsonObject -> item.asJsonObject.str("log")
        item.isJsonPrimitive -> item.asString
        else -> null
    }?.takeIf { it.isNotBlank() }
}
