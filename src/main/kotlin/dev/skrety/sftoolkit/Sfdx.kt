package dev.skrety.sftoolkit

import com.intellij.openapi.vfs.VirtualFile

/** Walks up from [file] looking for sfdx-project.json; stops after [stopAt] (usually the IDE project root). */
fun findSfdxRoot(file: VirtualFile, stopAt: VirtualFile?): VirtualFile? {
    var dir: VirtualFile? = if (file.isDirectory) file else file.parent
    while (dir != null) {
        if (dir.findChild("sfdx-project.json") != null) return dir
        if (dir == stopAt) return null
        dir = dir.parent
    }
    return null
}

/** Tooling API addressing for single-file types Compare supports. */
data class ToolingType(val sObject: String, val bodyField: String)

// ponytail: the four single-file body types only; bundles (lwc/aura) and XML types need
// a retrieve-to-temp compare — add when actually missed.
private val TOOLING_BY_DIR = mapOf(
    "classes" to ToolingType("ApexClass", "Body"),
    "triggers" to ToolingType("ApexTrigger", "Body"),
    "pages" to ToolingType("ApexPage", "Markup"),
    "components" to ToolingType("ApexComponent", "Markup"),
)

// Trust boundary: component name is spliced into a SOQL string — allow identifier chars only.
private val NAME_RE = Regex("^[A-Za-z][A-Za-z0-9_]*$")

data class ToolingRef(val type: ToolingType, val name: String)

fun toolingRefFor(fileName: String, parentDirName: String?): ToolingRef? {
    if (fileName.endsWith("-meta.xml")) return null
    val type = TOOLING_BY_DIR[parentDirName ?: return null] ?: return null
    val name = fileName.substringBeforeLast('.')
    if (!NAME_RE.matches(name)) return null
    return ToolingRef(type, name)
}

/** Same guardrails, addressed by metadata type name (used by the metadata browser). */
fun toolingRefForTypeName(metadataType: String, name: String): ToolingRef? {
    val type = TOOLING_BY_DIR.values.firstOrNull { it.sObject == metadataType } ?: return null
    if (!NAME_RE.matches(name)) return null
    return ToolingRef(type, name)
}
