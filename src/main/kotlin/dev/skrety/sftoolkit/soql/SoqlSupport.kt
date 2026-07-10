package dev.skrety.sftoolkit.soql

import com.google.gson.JsonObject

/**
 * True when a LIMIT clause exists at paren depth 0 — a LIMIT inside a subquery or a
 * string literal doesn't count. Port of the soql-editor family's querySafety logic.
 */
fun hasTopLevelLimit(soql: String): Boolean {
    var depth = 0
    var inString = false
    var i = 0
    while (i < soql.length) {
        val c = soql[i]
        when {
            inString -> when (c) {
                '\\' -> i++ // skip escaped char
                '\'' -> inString = false
            }
            c == '\'' -> inString = true
            c == '(' -> depth++
            c == ')' -> depth = (depth - 1).coerceAtLeast(0)
            depth == 0 && (c == 'l' || c == 'L') -> {
                val before = if (i == 0) null else soql[i - 1]
                val afterIdx = i + 5
                val after = if (afterIdx < soql.length) soql[afterIdx] else null
                if (soql.regionMatches(i, "limit", 0, 5, ignoreCase = true) &&
                    (before == null || (!before.isLetterOrDigit() && before != '_' && before != '.')) &&
                    (after == null || (!after.isLetterOrDigit() && after != '_'))
                ) {
                    return true
                }
            }
        }
        i++
    }
    return false
}

/**
 * Family lesson (soql-editor v0.7.2): never gate big queries behind a buried prompt.
 * Instead a visible auto-LIMIT toggle appends `LIMIT n` when the query has none.
 */
fun applyAutoLimit(soql: String, auto: Boolean, limit: Int = 200): String {
    val q = soql.trim().trimEnd(';').trim()
    return if (!auto || hasTopLevelLimit(q)) q else "$q LIMIT $limit"
}

/**
 * Flattens one query record for table display: nested lookups become dot-columns
 * (Account.Owner.Name), child subqueries become "[N rows]", nulls become "".
 */
fun flattenRecord(record: JsonObject): LinkedHashMap<String, String> {
    val out = LinkedHashMap<String, String>()
    fun walk(obj: JsonObject, prefix: String) {
        for ((key, value) in obj.entrySet()) {
            if (key == "attributes") continue
            val col = if (prefix.isEmpty()) key else "$prefix.$key"
            when {
                value.isJsonNull -> out[col] = ""
                value.isJsonPrimitive -> out[col] = value.asString
                value.isJsonObject -> {
                    val o = value.asJsonObject
                    val records = o.get("records")
                    if (records != null && records.isJsonArray) out[col] = "[${records.asJsonArray.size()} rows]"
                    else walk(o, col)
                }
                value.isJsonArray -> out[col] = "[${value.asJsonArray.size()} items]"
            }
        }
    }
    walk(record, "")
    return out
}

/** Column order = first appearance across rows. */
fun columnsOf(rows: List<Map<String, String>>): List<String> {
    val cols = LinkedHashSet<String>()
    rows.forEach { cols += it.keys }
    return cols.toList()
}

/**
 * CSV with RFC-style quoting + spreadsheet formula-injection neutralized
 * (family rule: leading = + - @ get a ' prefix).
 */
fun toCsv(cols: List<String>, rows: List<Map<String, String>>): String {
    fun cell(value: String): String {
        val guarded = if (value.firstOrNull() in FORMULA_CHARS) "'$value" else value
        return "\"" + guarded.replace("\"", "\"\"") + "\""
    }
    return buildString {
        appendLine(cols.joinToString(",") { cell(it) })
        for (row in rows) {
            appendLine(cols.joinToString(",") { cell(row[it] ?: "") })
        }
    }
}

private val FORMULA_CHARS = setOf('=', '+', '-', '@')

/** 15/18-char Salesforce record id (family-validated pattern). */
fun looksLikeRecordId(value: String): Boolean =
    Regex("^[a-zA-Z0-9]{15}([a-zA-Z0-9]{3})?$").matches(value)
