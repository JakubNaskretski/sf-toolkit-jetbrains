package dev.skrety.sftoolkit.soql

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.openapi.project.Project
import com.intellij.ui.TextFieldWithAutoCompletionListProvider
import dev.skrety.sftoolkit.schema.OrgSchemaService

/**
 * Bridges the pure soqlContextAt/soqlSuggestions logic into the platform's native
 * lookup (auto-popup while typing, arrow keys, type-to-filter). Reads are cache-only —
 * the CLI is never called from here (family rule).
 */
class SoqlCompletionProvider(
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
        val org = orgProvider() ?: return emptyList()
        val text = params.editor.document.charsSequence.toString()
        val ctx = soqlContextAt(text, params.offset)
        val schema = OrgSchemaService.get(project)
        val objectNames = schema.objectNames(org)
        if (objectNames == null) {
            hint("Caching org schema for completion — retry in a few seconds")
            return emptyList()
        }
        val out = soqlSuggestions(ctx, objectNames, { schema.describe(org, it) })
        // never log user-typed query text (family rule) — clause + count only, debug-gated
        if (LOG.isDebugEnabled) LOG.debug("soql completion: clause=${ctx.clause} items=${out.size}")
        return out
    }

    /** Dotted chains (Owner.Al) must be treated as one prefix, not split at the dot. */
    override fun getPrefix(text: String, offset: Int): String =
        soqlContextAt(text, offset).prefix

    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(SoqlCompletionProvider::class.java)

    private fun hint(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastHintAt > 5000) {
            lastHintAt = now
            onHint(message)
        }
    }
}
