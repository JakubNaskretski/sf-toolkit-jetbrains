package dev.skrety.sftoolkit.soql

import dev.skrety.sftoolkit.schema.ObjectSchema

enum class SoqlClause { SELECT, FROM, WHERE, ORDER_BY, GROUP_BY, HAVING, OTHER }

data class SoqlCompletionContext(
    val clause: SoqlClause,
    /** FROM object of the caret's own (sub)query scope, if present anywhere in that scope. */
    val objectName: String?,
    /** Identifier text (may contain dots) immediately before the caret. */
    val prefix: String,
    val replaceStart: Int,
)

/**
 * Masks string literals and deeper-nested paren contents with spaces so keyword/object
 * detection can use plain regex on the caret's own query scope. Same string/paren
 * awareness family as [hasTopLevelLimit].
 */
private fun maskScope(text: String, scopeStart: Int, scopeEnd: Int): String {
    val sb = StringBuilder(scopeEnd - scopeStart)
    var depth = 0
    var inString = false
    var i = scopeStart
    while (i < scopeEnd) {
        val c = text[i]
        when {
            inString -> {
                if (c == '\\') {
                    sb.append("  "); i += 2; continue
                }
                if (c == '\'') inString = false
                sb.append(' ')
            }
            c == '\'' -> {
                inString = true; sb.append(' ')
            }
            c == '(' -> {
                depth++; sb.append(' ')
            }
            c == ')' -> {
                depth = (depth - 1).coerceAtLeast(0); sb.append(' ')
            }
            depth > 0 -> sb.append(' ')
            else -> sb.append(c)
        }
        i++
    }
    return sb.toString()
}

/** Index of the ')' matching the '(' at [openIdx], or text.length when unbalanced. */
private fun findClose(text: String, openIdx: Int): Int {
    var depth = 1
    var inString = false
    var j = openIdx + 1
    while (j < text.length) {
        val c = text[j]
        when {
            inString -> {
                if (c == '\\') j++
                else if (c == '\'') inString = false
            }
            c == '\'' -> inString = true
            c == '(' -> depth++
            c == ')' -> {
                depth--
                if (depth == 0) return j
            }
        }
        j++
    }
    return text.length
}

/**
 * Paren scopes containing [caret], innermost first, always ending with the whole text.
 * A scope is (startAfterOpenParen, endBeforeCloseParen).
 */
private fun scopesAt(text: String, caret: Int): List<Pair<Int, Int>> {
    var inString = false
    val openStack = ArrayDeque<Int>()
    var i = 0
    while (i < caret && i < text.length) {
        val c = text[i]
        when {
            inString -> {
                if (c == '\\') i++
                else if (c == '\'') inString = false
            }
            c == '\'' -> inString = true
            c == '(' -> openStack.addLast(i)
            c == ')' -> openStack.removeLastOrNull()
        }
        i++
    }
    val scopes = ArrayList<Pair<Int, Int>>(openStack.size + 1)
    for (open in openStack.reversed()) {
        scopes += (open + 1) to findClose(text, open)
    }
    scopes += 0 to text.length
    return scopes
}

private val QUERY_MARKER_RE = Regex("""\b(select|from)\b""", RegexOption.IGNORE_CASE)

private val FROM_RE = Regex("""\bfrom\s+([A-Za-z][A-Za-z0-9_]*)""", RegexOption.IGNORE_CASE)

private val CLAUSE_KEYWORDS = listOf(
    "group by" to SoqlClause.GROUP_BY,
    "order by" to SoqlClause.ORDER_BY,
    "having" to SoqlClause.HAVING,
    "select" to SoqlClause.SELECT,
    "where" to SoqlClause.WHERE,
    "from" to SoqlClause.FROM,
    "limit" to SoqlClause.OTHER,
    "offset" to SoqlClause.OTHER,
)

fun soqlContextAt(text: String, caret: Int): SoqlCompletionContext {
    val at = caret.coerceIn(0, text.length)
    var prefixStart = at
    while (prefixStart > 0 && (text[prefixStart - 1].isLetterOrDigit() || text[prefixStart - 1] == '_' || text[prefixStart - 1] == '.')) {
        prefixStart--
    }
    val prefix = text.substring(prefixStart, at)

    // Grouping/function parens are NOT query boundaries (review finding): walk outward
    // until the scope actually contains a SELECT/FROM, else fall back to the whole text.
    var scopeStart = 0
    var masked = ""
    for ((s, e) in scopesAt(text, at)) {
        val m = maskScope(text, s, e)
        if (QUERY_MARKER_RE.containsMatchIn(m)) {
            scopeStart = s
            masked = m
            break
        }
        scopeStart = s
        masked = m // keep last (outermost) as fallback
    }
    val caretInScope = at - scopeStart
    val beforeCaret = masked.substring(0, caretInScope.coerceIn(0, masked.length))

    // Last clause keyword before the caret (word-boundary, on masked text).
    var clause = SoqlClause.OTHER
    var best = -1
    for ((kw, cl) in CLAUSE_KEYWORDS) {
        val re = Regex("""\b${kw.replace(" ", "\\s+")}\b""", RegexOption.IGNORE_CASE)
        val idx = re.findAll(beforeCaret).lastOrNull()?.range?.first ?: continue
        if (idx > best) {
            best = idx
            clause = cl
        }
    }
    // Directly after "FROM " the user is typing the object — but if the prefix itself
    // starts right after FROM, clause is FROM even when the keyword search said so already.
    val objectName = FROM_RE.find(masked)?.groupValues?.get(1)

    return SoqlCompletionContext(
        clause = clause,
        objectName = objectName,
        prefix = prefix,
        replaceStart = prefixStart,
    )
}

/**
 * Cache-only suggestions. [describeProvider] must never hit the network/CLI
 * (family lesson: no describe calls from the typing path).
 */
fun soqlSuggestions(
    ctx: SoqlCompletionContext,
    objectNames: List<String>?,
    describeProvider: (String) -> ObjectSchema?,
    limit: Int = 300,
): List<String> {
    fun rank(candidates: List<String>, needle: String): List<String> {
        val n = needle.lowercase()
        return candidates
            .filter { it.lowercase().contains(n) }
            .sortedWith(compareBy({ !it.lowercase().startsWith(n) }, { it.lowercase() }))
            .take(limit)
    }

    return when (ctx.clause) {
        SoqlClause.FROM -> rank(objectNames.orEmpty(), ctx.prefix)
        SoqlClause.SELECT, SoqlClause.WHERE, SoqlClause.ORDER_BY, SoqlClause.GROUP_BY, SoqlClause.HAVING -> {
            val base = ctx.objectName ?: return emptyList()
            val segments = ctx.prefix.split('.')
            val path = segments.dropLast(1)
            val last = segments.last()
            var current = describeProvider(base) ?: return emptyList()
            for (hop in path) {
                val rel = current.fields.firstOrNull {
                    it.relationshipName?.equals(hop, ignoreCase = true) == true && it.referenceTo.isNotEmpty()
                } ?: return emptyList()
                // Polymorphic lookups (Owner, What…): prefer User (family lesson G3).
                val target = rel.referenceTo.firstOrNull { it.equals("User", ignoreCase = true) }
                    ?: rel.referenceTo.first()
                current = describeProvider(target) ?: return emptyList()
            }
            val candidates = buildList {
                for (f in current.fields) {
                    add(f.name)
                    if (f.relationshipName != null && f.referenceTo.isNotEmpty()) add(f.relationshipName + ".")
                }
            }
            rank(candidates, last).map { (path + it).joinToString(".") }
        }
        SoqlClause.OTHER -> emptyList()
    }
}
