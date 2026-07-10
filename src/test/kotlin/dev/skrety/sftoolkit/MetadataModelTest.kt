package dev.skrety.sftoolkit

import com.google.gson.JsonParser
import dev.skrety.sftoolkit.metadata.META_TYPES
import dev.skrety.sftoolkit.metadata.MetaRow
import dev.skrety.sftoolkit.metadata.localNameFor
import dev.skrety.sftoolkit.metadata.mergeRows
import dev.skrety.sftoolkit.metadata.orgListNames
import dev.skrety.sftoolkit.metadata.scanLocalComponents
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetadataModelTest {

    private fun rule(type: String) = META_TYPES.first { it.type == type }

    @Test
    fun `local names strip the primary extension and skip companions`() {
        assertEquals("Foo", localNameFor(rule("ApexClass"), "Foo.cls"))
        assertNull(localNameFor(rule("ApexClass"), "Foo.cls-meta.xml"))
        assertEquals("My_Flow", localNameFor(rule("Flow"), "My_Flow.flow-meta.xml"))
        assertEquals("Logo", localNameFor(rule("StaticResource"), "Logo.resource-meta.xml"))
        assertNull(localNameFor(rule("StaticResource"), "Logo.gif"))
        assertEquals("Account", localNameFor(rule("Settings"), "Account.settings-meta.xml"))
        // Layout names may contain spaces/dashes — pass through untouched
        assertEquals(
            "Account-Account Layout",
            localNameFor(rule("Layout"), "Account-Account Layout.layout-meta.xml"),
        )
    }

    @Test
    fun `percent-encoded names decode for display only`() {
        assertEquals(
            "Case-Case (Support) Layout",
            dev.skrety.sftoolkit.metadata.decodeMetaName("Case-Case %28Support%29 Layout"),
        )
        // '+' must stay literal (URLDecoder would eat it)
        assertEquals("A+B", dev.skrety.sftoolkit.metadata.decodeMetaName("A+B"))
        assertEquals("Plain Name", dev.skrety.sftoolkit.metadata.decodeMetaName("Plain Name"))
        assertEquals("Bad%GG", dev.skrety.sftoolkit.metadata.decodeMetaName("Bad%GG"))
    }

    @Test
    fun `org list result handles array, single object and junk`() {
        val arr = JsonParser.parseString(
            """[{"fullName":"A"},{"fullName":"B"},{"noName":true}]""",
        )
        assertEquals(listOf("A", "B"), orgListNames(arr))
        // single-component orgs return a bare object (SOAP artifact)
        assertEquals(listOf("Solo"), orgListNames(JsonParser.parseString("""{"fullName":"Solo"}""")))
        assertEquals(emptyList(), orgListNames(null))
        assertEquals(emptyList(), orgListNames(JsonParser.parseString("\"oops\"")))
    }

    @Test
    fun `merge classifies local-only, org-only and both`() {
        val rows = mergeRows(
            local = mapOf("ApexClass" to mapOf("LocalOnly" to "/x/LocalOnly.cls", "Shared" to "/x/Shared.cls")),
            org = mapOf("ApexClass" to listOf("Shared", "OrgOnly"), "Flow" to listOf("F1")),
        )
        val byKey = rows.associateBy { it.key }
        assertEquals(4, rows.size)
        assertEquals("Local only", byKey.getValue("ApexClass:LocalOnly").location)
        assertEquals("Both", byKey.getValue("ApexClass:Shared").location)
        assertEquals("Org only", byKey.getValue("ApexClass:OrgOnly").location)
        assertEquals("Org only", byKey.getValue("Flow:F1").location)
        assertEquals("/x/Shared.cls", byKey.getValue("ApexClass:Shared").localPath)
        // sorted by type then name
        assertEquals(rows.sortedWith(compareBy(MetaRow::type, MetaRow::name)), rows)
    }

    @Test
    fun `scanner finds files and bundle dirs, skips node_modules`() {
        val pkg: Path = Files.createTempDirectory("sf-toolkit-scan")
        try {
            val classes = Files.createDirectories(pkg.resolve("main/default/classes"))
            Files.writeString(classes.resolve("Foo.cls"), "public class Foo {}")
            Files.writeString(classes.resolve("Foo.cls-meta.xml"), "<x/>")
            val lwc = Files.createDirectories(pkg.resolve("main/default/lwc"))
            Files.createDirectories(lwc.resolve("myCmp"))
            val hidden = Files.createDirectories(pkg.resolve("node_modules/dep/classes"))
            Files.writeString(hidden.resolve("Evil.cls"), "x")

            val scanned = scanLocalComponents(listOf(pkg))
            assertEquals(mapOf("Foo" to classes.resolve("Foo.cls").toString()), scanned["ApexClass"])
            assertEquals(setOf("myCmp"), scanned["LightningComponentBundle"]?.keys)
            assertTrue(scanned["ApexClass"]?.containsKey("Evil") != true)
        } finally {
            pkg.toFile().deleteRecursively()
        }
    }
}
