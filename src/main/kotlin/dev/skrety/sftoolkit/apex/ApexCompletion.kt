package dev.skrety.sftoolkit.apex

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.openapi.project.Project
import com.intellij.ui.TextFieldWithAutoCompletionListProvider
import dev.skrety.sftoolkit.schema.ObjectSchema
import dev.skrety.sftoolkit.schema.OrgSchemaService

/** Identifier (possibly dotted) immediately before [caret] — same walk as SOQL's. */
fun apexPrefixAt(text: String, caret: Int): String {
    val at = caret.coerceIn(0, text.length)
    var start = at
    while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_' || text[start - 1] == '.')) {
        start--
    }
    return text.substring(start, at)
}

val APEX_KEYWORDS: List<String> = listOf(
    "public", "private", "protected", "global", "static", "final", "abstract", "virtual", "override",
    "class", "interface", "enum", "extends", "implements", "new", "return", "void",
    "if", "else", "for", "while", "do", "try", "catch", "finally", "throw", "break", "continue",
    "insert", "update", "upsert", "delete", "undelete", "true", "false", "null", "this", "super",
    "String", "Integer", "Boolean", "Decimal", "Double", "Long", "Date", "Datetime", "Time", "Id",
    "Object", "Blob", "List", "Set", "Map", "Exception", "SObject",
)

/** Dotted helpers offered whole — the lookup filters them by the typed (dotted) prefix. */
val APEX_DOTTED: List<String> = listOf(
    "System.debug()", "System.now()", "System.today()", "System.assert()",
    "System.assertEquals()", "System.enqueueJob()", "System.schedule()",
    "Test.startTest()", "Test.stopTest()", "Test.isRunningTest()",
    "Database.insert()", "Database.update()", "Database.upsert()", "Database.delete()",
    "Database.query()", "Database.setSavepoint()", "Database.rollback()",
    "JSON.serialize()", "JSON.deserialize()", "Math.abs()", "Math.round()",
    "Limits.getQueries()", "Limits.getDmlStatements()", "UserInfo.getUserId()",
)

/**
 * Cache-only suggestions for the anonymous-Apex pane: keywords + org sObject names,
 * and after `Account.` the object's fields with relationship hops, exactly like SOQL
 * completion. [describeProvider] must never hit the CLI (family rule).
 */
fun apexSuggestions(
    prefix: String,
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

    if (!prefix.contains('.')) {
        return rank(APEX_KEYWORDS + APEX_DOTTED + objectNames.orEmpty(), prefix)
    }
    val segments = prefix.split('.')
    val head = segments.first()
    val base = objectNames.orEmpty().firstOrNull { it.equals(head, ignoreCase = true) }
        ?: return rank(APEX_DOTTED, prefix)
    var current = describeProvider(base) ?: return emptyList()
    for (hop in segments.drop(1).dropLast(1)) {
        val rel = current.fields.firstOrNull {
            it.relationshipName?.equals(hop, ignoreCase = true) == true && it.referenceTo.isNotEmpty()
        } ?: return emptyList()
        // Polymorphic lookups (Owner, What…): prefer User (family lesson G3).
        val target = rel.referenceTo.firstOrNull { it.equals("User", ignoreCase = true) }
            ?: rel.referenceTo.first()
        current = describeProvider(target) ?: return emptyList()
    }
    val last = segments.last()
    val path = segments.dropLast(1)
    val candidates = buildList {
        for (f in current.fields) {
            add(f.name)
            if (f.relationshipName != null && f.referenceTo.isNotEmpty()) add(f.relationshipName + ".")
        }
    }
    return rank(candidates, last).map { (path + it).joinToString(".") }
}

/** Same bridge shape as SoqlCompletionProvider — cache-only reads, rate-limited hint. */
class ApexCompletionProvider(
    private val project: Project,
    private val orgProvider: () -> String?,
    private val onHint: (String) -> Unit,
) : TextFieldWithAutoCompletionListProvider<String>(emptyList()) {

    @Volatile
    private var lastHintAt = 0L

    override fun getLookupString(item: String): String = item

    override fun getItems(
        prefix: String?,
        cached: Boolean,
        parameters: CompletionParameters?,
    ): Collection<String> {
        val params = parameters ?: return emptyList()
        val text = params.editor.document.charsSequence.toString()
        val typed = apexPrefixAt(text, params.offset)
        val org = orgProvider()
        val schema = OrgSchemaService.get(project)
        val names = org?.let { schema.objectNames(it) }
        if (org != null && names == null) {
            hint("Caching org schema for completion — sObject names appear shortly")
        }
        return apexSuggestions(typed, names, { obj -> org?.let { schema.describe(it, obj) } })
    }

    /** Dotted chains (Account.Owner.Na) are one prefix, not split at the dot. */
    override fun getPrefix(text: String, offset: Int): String = apexPrefixAt(text, offset)

    private fun hint(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastHintAt > 5000) {
            lastHintAt = now
            onHint(message)
        }
    }
}
