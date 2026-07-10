package dev.skrety.sftoolkit.schema

import com.google.gson.JsonObject
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import dev.skrety.sftoolkit.SfCli
import dev.skrety.sftoolkit.isValidApiName
import dev.skrety.sftoolkit.str
import java.nio.file.Path

/**
 * Org schema ("offline symbol table"): object names + field describes, cached on disk
 * per org. Completion reads are CACHE-ONLY (family lesson C1: never shell out from the
 * typing path); the CLI is invoked only by the explicit sync and the post-query
 * background describe.
 */
@Service(Service.Level.PROJECT)
class OrgSchemaService(private val project: Project) {

    fun store(org: String): SchemaStore = SchemaStore(
        Path.of(
            PathManager.getSystemPath(), "sf-toolkit", "orgcache",
            SchemaStore.sanitizeFileName(org),
        ),
    )

    /** Cache-only — safe from any thread, never touches the CLI. */
    fun objectNames(org: String): List<String>? = store(org).readObjectNames()

    /** Cache-only — safe from any thread, never touches the CLI. */
    fun describe(org: String, objectName: String): ObjectSchema? = store(org).readDescribe(objectName)

    /** Blocking CLI call — background threads only. Returns object count, or null on failure. */
    fun syncObjects(org: String, indicator: ProgressIndicator?): Int? {
        val res = SfCli.get(project).execute(
            listOf("sobject", "list", "--sobject", "all", "-o", org),
            indicator,
        )
        if (!res.ok) return null
        val names = res.json?.get("result")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { el -> el.takeIf { it.isJsonPrimitive }?.asString?.takeIf { s -> s.isNotBlank() } }
            .orEmpty()
        if (names.isEmpty()) return null
        store(org).writeObjectNames(names)
        return names.size
    }

    /** Blocking CLI call on cache miss — background threads only. */
    fun ensureDescribe(org: String, objectName: String, indicator: ProgressIndicator?): ObjectSchema? {
        if (!isValidApiName(objectName)) return null
        store(org).readDescribe(objectName)?.let { return it }
        val res = SfCli.get(project).execute(
            listOf("sobject", "describe", "--sobject", objectName, "-o", org),
            indicator,
        )
        if (!res.ok) return null
        val schema = parseDescribe(res.resultObj()) ?: return null
        store(org).writeDescribe(schema)
        return schema
    }

    companion object {
        fun get(project: Project): OrgSchemaService = project.service()

        /** `sf sobject describe --json` → trimmed schema (shape verified live). */
        fun parseDescribe(result: JsonObject?): ObjectSchema? {
            val name = result?.str("name") ?: return null
            val fields = result.get("fields")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { el -> el.takeIf { it.isJsonObject }?.asJsonObject }
                ?.mapNotNull { f ->
                    val fieldName = f.str("name") ?: return@mapNotNull null
                    FieldInfo(
                        name = fieldName,
                        type = f.str("type") ?: "",
                        referenceTo = f.get("referenceTo")?.takeIf { it.isJsonArray }?.asJsonArray
                            ?.mapNotNull { r -> r.takeIf { it.isJsonPrimitive }?.asString }
                            .orEmpty(),
                        relationshipName = f.str("relationshipName")?.takeIf { it.isNotBlank() },
                    )
                }
                .orEmpty()
            if (fields.isEmpty()) return null
            val childRels = result.get("childRelationships")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { el -> el.takeIf { it.isJsonObject }?.asJsonObject }
                ?.mapNotNull { c ->
                    val rel = c.str("relationshipName")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val child = c.str("childSObject") ?: return@mapNotNull null
                    ChildRel(rel, child)
                }
                .orEmpty()
            return ObjectSchema(name, fields, childRels)
        }
    }
}
