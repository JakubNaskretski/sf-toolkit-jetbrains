package dev.skrety.sftoolkit

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.search.scope.packageSet.CustomScopesProvider
import com.intellij.psi.search.scope.packageSet.NamedScope
import com.intellij.psi.search.scope.packageSet.PackageSetFactory

/**
 * Predefined Salesforce search scopes (Find in Files → Scope, usages, inspections,
 * TODO view) — the IC-style "search only Apex" filter. Plain file masks (*.cls in the
 * File mask box) work natively; these are the named, reusable variant.
 */
class SfSearchScopes : CustomScopesProvider {

    override fun getCustomScopes(): List<NamedScope> = SCOPES

    companion object {
        private val LOG = Logger.getInstance(SfSearchScopes::class.java)

        // Base-name patterns match at any directory depth.
        private fun extPattern(extensions: List<String>): String =
            extensions.joinToString("||") { "file:*.$it" }

        private const val META_PATTERN = "file:*-meta.xml"

        private val SCOPES: List<NamedScope> by lazy {
            listOfNotNull(
                scope("SF: Apex", extPattern(listOf("cls", "trigger", "apex"))),
                scope("SF: SOQL", extPattern(listOf("soql"))),
                scope("SF: Metadata XML", META_PATTERN),
                scope(
                    "SF: All Salesforce Source",
                    extPattern(listOf("cls", "trigger", "apex", "soql", "page", "component", "cmp", "evt", "app")) +
                        "||" + META_PATTERN,
                ),
            )
        }

        private fun scope(name: String, pattern: String): NamedScope? = try {
            NamedScope(name, AllIcons.Nodes.Folder, PackageSetFactory.getInstance().compile(pattern))
        } catch (e: Exception) {
            LOG.warn("Failed to compile search scope '$name'", e)
            null
        }
    }
}
