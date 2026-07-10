package dev.skrety.sftoolkit

import com.google.gson.JsonParser
import dev.skrety.sftoolkit.schema.ChildRel
import dev.skrety.sftoolkit.schema.FieldInfo
import dev.skrety.sftoolkit.schema.ObjectSchema
import dev.skrety.sftoolkit.schema.OrgSchemaService
import dev.skrety.sftoolkit.schema.SchemaStore
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SchemaStoreTest {

    private fun tempStore(maxAgeMs: Long = SchemaStore.DEFAULT_MAX_AGE_MS): Pair<SchemaStore, java.nio.file.Path> {
        val dir = Files.createTempDirectory("sf-toolkit-schema")
        return SchemaStore(dir, maxAgeMs) to dir
    }

    private val account = ObjectSchema(
        name = "Account",
        fields = listOf(
            FieldInfo("Name", "string", emptyList(), null),
            FieldInfo("OwnerId", "reference", listOf("User"), "Owner"),
        ),
        childRelationships = listOf(ChildRel("Contacts", "Contact")),
    )

    @Test
    fun `objects and describes round-trip`() {
        val (store, dir) = tempStore()
        try {
            assertNull(store.readObjectNames())
            store.writeObjectNames(listOf("Account", "Contact", ""))
            assertEquals(listOf("Account", "Contact"), store.readObjectNames())

            assertNull(store.readDescribe("Account"))
            store.writeDescribe(account)
            assertEquals(account, store.readDescribe("Account"))
            // lookups are case-insensitive even on case-sensitive filesystems
            assertEquals(account, store.readDescribe("ACCOUNT"))
            assertEquals(account, store.readDescribe("account"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `stale files read as null`() {
        val (store, dir) = tempStore(maxAgeMs = 1000)
        try {
            store.writeObjectNames(listOf("Account"))
            Files.setLastModifiedTime(
                dir.resolve("objects.json"),
                FileTime.fromMillis(System.currentTimeMillis() - 10_000),
            )
            assertNull(store.readObjectNames())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `describe writes never create the object list (poisoning guard)`() {
        val (store, dir) = tempStore()
        try {
            store.writeDescribe(account)
            assertNull(store.readObjectNames())
            assertEquals(false, Files.exists(dir.resolve("objects.json")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `corrupt files read as null`() {
        val (store, dir) = tempStore()
        try {
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("objects.json"), "{not json")
            assertNull(store.readObjectNames())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

class DescribeParseTest {

    @Test
    fun `live describe shape parses to trimmed schema`() {
        // Shape verified live vs `sf sobject describe --json` (values trimmed/fictional).
        val result = JsonParser.parseString(
            """
            {"name":"Account","custom":false,
             "fields":[
               {"name":"Name","type":"string","referenceTo":[],"relationshipName":null},
               {"name":"OwnerId","type":"reference","referenceTo":["User"],"relationshipName":"Owner"},
               {"name":null,"type":"broken"}
             ],
             "childRelationships":[
               {"relationshipName":null,"childSObject":"AIInsightValue"},
               {"relationshipName":"Contacts","childSObject":"Contact"}
             ]}
            """.trimIndent(),
        ).asJsonObject
        val schema = OrgSchemaService.parseDescribe(result)!!
        assertEquals("Account", schema.name)
        assertEquals(2, schema.fields.size)
        assertEquals(listOf("User"), schema.fields[1].referenceTo)
        assertEquals("Owner", schema.fields[1].relationshipName)
        // null relationshipName child rels are dropped (verified: most are null)
        assertEquals(listOf(ChildRel("Contacts", "Contact")), schema.childRelationships)
    }

    @Test
    fun `garbage describe parses to null`() {
        assertNull(OrgSchemaService.parseDescribe(null))
        assertNull(OrgSchemaService.parseDescribe(JsonParser.parseString("{}").asJsonObject))
        assertNull(
            OrgSchemaService.parseDescribe(
                JsonParser.parseString("""{"name":"X","fields":[]}""").asJsonObject,
            ),
        )
    }
}
