package dev.skrety.sftoolkit.schema

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path

data class FieldInfo(
    val name: String,
    val type: String,
    val referenceTo: List<String>,
    val relationshipName: String?,
)

data class ChildRel(val relationshipName: String, val childSObject: String)

data class ObjectSchema(
    val name: String,
    val fields: List<FieldInfo>,
    val childRelationships: List<ChildRel>,
)

/**
 * Per-org disk cache: `objects.json` (the org's sObject name list) + `describes/<Name>.json`.
 * Freshness = file mtime vs [maxAgeMs].
 *
 * Poisoning lesson from the soql-editor family: `objects.json` is written ONLY by an
 * explicit full sync ([writeObjectNames]) — describe writes must never create or touch
 * the object list, or a stale tiny list self-perpetuates.
 */
class SchemaStore(private val root: Path, private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS) {

    private val gson = Gson()
    private val objectsFile: Path get() = root.resolve("objects.json")
    // Lowercased so a `from account` lookup hits the canonical Account.json describe
    // on case-sensitive filesystems too.
    private fun describeFile(objectName: String): Path =
        root.resolve("describes").resolve(sanitizeFileName(objectName).lowercase() + ".json")

    fun readObjectNames(): List<String>? {
        val file = objectsFile
        if (!isFresh(file)) return null
        return memoized(file) { text ->
            gson.fromJson(text, Array<String>::class.java)?.toList()?.takeIf { it.isNotEmpty() }
        }
    }

    fun writeObjectNames(names: List<String>) {
        Files.createDirectories(root)
        Files.writeString(objectsFile, gson.toJson(names.filter { it.isNotBlank() }))
    }

    fun readDescribe(objectName: String): ObjectSchema? {
        val file = describeFile(objectName)
        if (!isFresh(file)) return null
        return memoized(file) { text ->
            gson.fromJson(text, ObjectSchema::class.java)
                ?.takeIf { it.name.isNotBlank() && it.fields.isNotEmpty() }
        }
    }

    fun writeDescribe(schema: ObjectSchema) {
        Files.createDirectories(root.resolve("describes"))
        Files.writeString(describeFile(schema.name), gson.toJson(schema))
    }

    /** Every fresh cached describe (faux-class generation input). */
    fun allCachedDescribes(): List<ObjectSchema> {
        val dir = root.resolve("describes")
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".json") && isFresh(it) }
                .map { file ->
                    memoized(file) { text ->
                        gson.fromJson(text, ObjectSchema::class.java)
                            ?.takeIf { it.name.isNotBlank() && it.fields.isNotEmpty() }
                    }
                }
                .filter { it != null }
                .map { it!! }
                .toList()
        }
    }

    private fun isFresh(file: Path): Boolean = try {
        Files.isRegularFile(file) &&
            System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() < maxAgeMs
    } catch (_: Exception) {
        false
    }

    companion object {
        const val DEFAULT_MAX_AGE_MS: Long = 7L * 24 * 3600 * 1000

        fun sanitizeFileName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

        // Completion reads these on every keystroke — parse each file once per mtime.
        private val memo = java.util.concurrent.ConcurrentHashMap<Path, Pair<Long, Any>>()

        @Suppress("UNCHECKED_CAST")
        private fun <T : Any> memoized(file: Path, parse: (String) -> T?): T? = try {
            val mtime = Files.getLastModifiedTime(file).toMillis()
            val hit = memo[file]
            if (hit != null && hit.first == mtime) hit.second as T
            else parse(Files.readString(file))?.also {
                if (memo.size > 512) memo.clear() // ponytail: crude cap; LRU if it ever matters
                memo[file] = mtime to it
            }
        } catch (_: Exception) {
            null
        }
    }
}
