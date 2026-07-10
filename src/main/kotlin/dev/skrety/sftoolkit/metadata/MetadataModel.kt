package dev.skrety.sftoolkit.metadata

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.skrety.sftoolkit.str
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/**
 * Source-format directory rules for the metadata browser. Folder name ↔ metadata type,
 * mirroring the VS Code family's scanner (incl. the verified Settings:<Name> addressing).
 */
data class MetaTypeRule(
    val type: String,
    val folder: String,
    /** File suffix that identifies one component; empty for bundle-dir types. */
    val primaryExts: List<String>,
    val isBundleDir: Boolean = false,
)

val META_TYPES: List<MetaTypeRule> = listOf(
    MetaTypeRule("ApexClass", "classes", listOf(".cls")),
    MetaTypeRule("ApexTrigger", "triggers", listOf(".trigger")),
    MetaTypeRule("ApexPage", "pages", listOf(".page")),
    MetaTypeRule("ApexComponent", "components", listOf(".component")),
    MetaTypeRule("CustomObject", "objects", emptyList(), isBundleDir = true),
    MetaTypeRule("LightningComponentBundle", "lwc", emptyList(), isBundleDir = true),
    MetaTypeRule("AuraDefinitionBundle", "aura", emptyList(), isBundleDir = true),
    MetaTypeRule("Flow", "flows", listOf(".flow-meta.xml")),
    MetaTypeRule("Layout", "layouts", listOf(".layout-meta.xml")),
    MetaTypeRule("PermissionSet", "permissionsets", listOf(".permissionset-meta.xml")),
    MetaTypeRule("Profile", "profiles", listOf(".profile-meta.xml")),
    MetaTypeRule("StaticResource", "staticresources", listOf(".resource-meta.xml")),
    MetaTypeRule("CustomTab", "tabs", listOf(".tab-meta.xml")),
    MetaTypeRule("FlexiPage", "flexipages", listOf(".flexipage-meta.xml")),
    MetaTypeRule("Settings", "settings", listOf(".settings-meta.xml")),
)

/** Component name from a file name per rule, or null when the file isn't a component. */
fun localNameFor(rule: MetaTypeRule, fileName: String): String? {
    for (ext in rule.primaryExts) {
        if (fileName.endsWith(ext)) return fileName.removeSuffix(ext)
    }
    return null
}

data class MetaRow(
    val type: String,
    val name: String,
    val local: Boolean,
    val org: Boolean,
    val localPath: String?,
) {
    val key: String get() = "$type:$name"
    val location: String
        get() = when {
            local && org -> "Both"
            local -> "Local only"
            else -> "Org only"
        }
}

/**
 * Scans package directories for source-format components.
 * Returns type → (name → absolute file/dir path).
 */
fun scanLocalComponents(packageDirs: List<Path>): Map<String, Map<String, String>> {
    val byFolder = META_TYPES.associateBy { it.folder }
    val out = LinkedHashMap<String, LinkedHashMap<String, String>>()
    for (pkg in packageDirs) {
        if (!Files.isDirectory(pkg)) continue
        val typeDirs = Files.walk(pkg, 6).use { stream ->
            stream.asSequence()
                .filter { Files.isDirectory(it) && byFolder.containsKey(it.fileName.toString()) }
                .filterNot { p -> p.any { it.fileName.toString() in SKIP_DIRS } }
                .toList()
        }
        for (typeDir in typeDirs) {
            val rule = byFolder.getValue(typeDir.fileName.toString())
            val bucket = out.getOrPut(rule.type) { LinkedHashMap() }
            Files.list(typeDir).use { children ->
                for (child in children) {
                    val childName = child.fileName.toString()
                    if (rule.isBundleDir) {
                        if (Files.isDirectory(child) && !childName.startsWith(".")) {
                            bucket.putIfAbsent(childName, child.toString())
                        }
                    } else {
                        val name = localNameFor(rule, childName) ?: continue
                        bucket.putIfAbsent(name, child.toString())
                    }
                }
            }
        }
    }
    return out
}

private val SKIP_DIRS = setOf("node_modules", ".git", ".sfdx", ".sf", "__tests__")

/**
 * Normalizes `sf org list metadata --json` output: `result` is an array normally,
 * but a bare object when the org has exactly one component of the type (SOAP artifact).
 */
fun orgListNames(resultEl: JsonElement?): List<String> {
    val objects: List<JsonObject> = when {
        resultEl == null -> emptyList()
        resultEl.isJsonArray -> resultEl.asJsonArray.mapNotNull { it.takeIf { el -> el.isJsonObject }?.asJsonObject }
        resultEl.isJsonObject -> listOf(resultEl.asJsonObject)
        else -> emptyList()
    }
    return objects.mapNotNull { it.str("fullName")?.takeIf { n -> n.isNotBlank() } }
}

/**
 * Display form of percent-encoded fullNames (`Case-Case %28Support%29 Layout` →
 * `Case-Case (Support) Layout`). Only %XX runs are decoded ('+' stays literal —
 * URLDecoder would corrupt it); raw names remain the CLI/merge keys.
 */
fun decodeMetaName(raw: String): String {
    if (!raw.contains('%')) return raw
    return try {
        Regex("(?:%[0-9A-Fa-f]{2})+").replace(raw) { match ->
            val bytes = match.value.split('%').filter { it.isNotEmpty() }
                .map { it.toInt(16).toByte() }.toByteArray()
            String(bytes, Charsets.UTF_8)
        }
    } catch (_: Exception) {
        raw
    }
}

/** Merges local scan + org listings into browser rows, sorted by type then name. */
fun mergeRows(
    local: Map<String, Map<String, String>>,
    org: Map<String, List<String>>,
): List<MetaRow> {
    val rows = ArrayList<MetaRow>()
    val types = (local.keys + org.keys).distinct()
    for (type in types) {
        val localNames = local[type].orEmpty()
        val orgNames = org[type].orEmpty().toSet()
        val allNames = (localNames.keys + orgNames).distinct()
        for (name in allNames) {
            rows += MetaRow(
                type = type,
                name = name,
                local = name in localNames,
                org = name in orgNames,
                localPath = localNames[name],
            )
        }
    }
    rows.sortWith(compareBy({ it.type }, { it.name }))
    return rows
}
