package dev.skrety.sftoolkit

import com.google.gson.JsonParser
import dev.skrety.sftoolkit.soql.applyAutoLimit
import dev.skrety.sftoolkit.soql.columnsOf
import dev.skrety.sftoolkit.soql.flattenRecord
import dev.skrety.sftoolkit.soql.hasTopLevelLimit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoqlSupportTest {

    @Test
    fun `limit detection is depth and string aware`() {
        assertFalse(hasTopLevelLimit("SELECT Id FROM Account"))
        assertTrue(hasTopLevelLimit("SELECT Id FROM Account LIMIT 5"))
        assertTrue(hasTopLevelLimit("select id from account limit 5"))
        // subquery LIMIT is not a top-level LIMIT
        assertFalse(hasTopLevelLimit("SELECT Id, (SELECT Id FROM Contacts LIMIT 3) FROM Account"))
        assertTrue(hasTopLevelLimit("SELECT Id, (SELECT Id FROM Contacts) FROM Account LIMIT 7"))
        // LIMIT inside a string literal doesn't count
        assertFalse(hasTopLevelLimit("SELECT Id FROM Account WHERE Name = 'limit 5'"))
        assertFalse(hasTopLevelLimit("SELECT Id FROM Account WHERE Name = 'O\\'Neil LIMIT'"))
        // identifier containing "limit" doesn't count
        assertFalse(hasTopLevelLimit("SELECT RateLimit__c FROM Acme_Config__c"))
        assertFalse(hasTopLevelLimit("SELECT Id FROM Account WHERE Limits__c = 3".replace("LIMIT", "Limit")))
    }

    @Test
    fun `auto limit appends only when needed`() {
        assertEquals("SELECT Id FROM Account LIMIT 200", applyAutoLimit("SELECT Id FROM Account", true))
        assertEquals("SELECT Id FROM Account LIMIT 200", applyAutoLimit("  SELECT Id FROM Account ; ", true))
        assertEquals("SELECT Id FROM Account LIMIT 5", applyAutoLimit("SELECT Id FROM Account LIMIT 5", true))
        assertEquals("SELECT Id FROM Account", applyAutoLimit("SELECT Id FROM Account", false))
    }

    @Test
    fun `flatten handles lookups, subqueries and nulls`() {
        val record = JsonParser.parseString(
            """
            {
              "attributes": {"type": "Account"},
              "Id": "001000000000001AAA",
              "Name": "Acme",
              "AnnualRevenue": 12.5,
              "Owner": {"attributes": {"type": "User"}, "Name": "Jane Placeholder", "IsActive": true},
              "Contacts": {"totalSize": 2, "records": [{"Id": "1"}, {"Id": "2"}]},
              "Description": null
            }
            """.trimIndent(),
        ).asJsonObject
        val flat = flattenRecord(record)
        assertEquals("001000000000001AAA", flat["Id"])
        assertEquals("Acme", flat["Name"])
        assertEquals("12.5", flat["AnnualRevenue"])
        assertEquals("Jane Placeholder", flat["Owner.Name"])
        assertEquals("true", flat["Owner.IsActive"])
        assertEquals("[2 rows]", flat["Contacts"])
        assertEquals("", flat["Description"])
        assertNull(flat["attributes"])
        assertEquals(listOf("Id", "Name", "AnnualRevenue", "Owner.Name", "Owner.IsActive", "Contacts", "Description"), columnsOf(listOf(flat)))
    }
}

class OrgParseTest {

    @Test
    fun `walks all group arrays and dedupes by username`() {
        // Shape observed from a real `sf org list --json` (values fictional).
        val result = JsonParser.parseString(
            """
            {
              "other": [
                {"username": "dev@acme.example", "alias": "AcmeDev", "connectedStatus": "Connected",
                 "isScratch": false, "isDefaultUsername": true}
              ],
              "sandboxes": [],
              "nonScratchOrgs": [
                {"username": "dev@acme.example", "alias": "AcmeDev", "connectedStatus": "Connected"}
              ],
              "scratchOrgs": [
                {"username": "scratch@acme.example", "isScratch": true, "connectedStatus": "Unknown"}
              ],
              "devHubs": []
            }
            """.trimIndent(),
        ).asJsonObject
        val orgs = parseOrgList(result)
        assertEquals(2, orgs.size)
        val dev = orgs.first { it.username == "dev@acme.example" }
        assertEquals("AcmeDev", dev.alias)
        assertTrue(dev.connected)
        assertTrue(dev.isDefault)
        assertEquals("AcmeDev", dev.display)
        val scratch = orgs.first { it.username == "scratch@acme.example" }
        assertNull(scratch.alias)
        assertTrue(scratch.isScratch)
        assertEquals("scratch@acme.example", scratch.display)
    }

    @Test
    fun `empty or missing result yields no orgs`() {
        assertEquals(emptyList(), parseOrgList(null))
        assertEquals(emptyList(), parseOrgList(JsonParser.parseString("{}").asJsonObject))
    }
}

class ToolingRefTest {

    @Test
    fun `maps the four supported single-file types`() {
        assertEquals(ToolingRef(ToolingType("ApexClass", "Body"), "Foo"), toolingRefFor("Foo.cls", "classes"))
        assertEquals(ToolingRef(ToolingType("ApexTrigger", "Body"), "Bar"), toolingRefFor("Bar.trigger", "triggers"))
        assertEquals(ToolingRef(ToolingType("ApexPage", "Markup"), "Page1"), toolingRefFor("Page1.page", "pages"))
        assertEquals(ToolingRef(ToolingType("ApexComponent", "Markup"), "Cmp"), toolingRefFor("Cmp.component", "components"))
    }

    @Test
    fun `rejects meta files, unknown dirs and non-identifier names`() {
        assertNull(toolingRefFor("Foo.cls-meta.xml", "classes"))
        assertNull(toolingRefFor("Foo.cls", "lwc"))
        assertNull(toolingRefFor("Foo.cls", null))
        // SOQL-injection guard: names must be plain identifiers
        assertNull(toolingRefFor("Foo' OR Name != '.cls", "classes"))
        assertNull(toolingRefFor("1Foo.cls", "classes"))
    }
}
