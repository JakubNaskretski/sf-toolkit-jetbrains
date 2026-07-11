package dev.skrety.sftoolkit

import dev.skrety.sftoolkit.apex.APEX_DOTTED
import dev.skrety.sftoolkit.apex.apexPrefixAt
import dev.skrety.sftoolkit.apex.apexSuggestions
import dev.skrety.sftoolkit.schema.FieldInfo
import dev.skrety.sftoolkit.schema.ObjectSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApexCompletionTest {

    private val account = ObjectSchema(
        name = "Account",
        fields = listOf(
            FieldInfo("Id", "id", emptyList(), null),
            FieldInfo("Name", "string", emptyList(), null),
            FieldInfo("Active__c", "picklist", emptyList(), null),
            FieldInfo("OwnerId", "reference", listOf("User"), "Owner"),
        ),
        childRelationships = emptyList(),
    )
    private val user = ObjectSchema(
        name = "User",
        fields = listOf(
            FieldInfo("Id", "id", emptyList(), null),
            FieldInfo("Alias", "string", emptyList(), null),
        ),
        childRelationships = emptyList(),
    )
    private val describes = mapOf("Account" to account, "User" to user)
    private fun describe(name: String) = describes[name]

    @Test
    fun `prefix walk handles identifiers and dot chains`() {
        assertEquals("Sys", apexPrefixAt("x = Sys", 7))
        assertEquals("Account.Na", apexPrefixAt("insert Account.Na", 17))
        assertEquals("", apexPrefixAt("a + ", 4))
        assertEquals("debug", apexPrefixAt("System.debug('x'); debug", 24).substringAfterLast('.'))
    }

    @Test
    fun `keywords rank prefix matches first`() {
        val out = apexSuggestions("pu", listOf("Account"), ::describe)
        assertEquals("public", out.first())
    }

    @Test
    fun `sObject names from the org cache are offered`() {
        val out = apexSuggestions("Acc", listOf("Account", "AccountContactRole"), ::describe)
        assertTrue("Account" in out)
        assertTrue("AccountContactRole" in out)
    }

    @Test
    fun `dot on an sObject completes its fields`() {
        val out = apexSuggestions("Account.Na", listOf("Account"), ::describe)
        assertEquals(listOf("Account.Name"), out)
    }

    @Test
    fun `custom fields and relationship hops resolve like SOQL`() {
        assertTrue("Account.Active__c" in apexSuggestions("Account.Act", listOf("Account"), ::describe))
        assertEquals(
            listOf("Account.Owner.Alias"),
            apexSuggestions("Account.Owner.Al", listOf("Account"), ::describe),
        )
    }

    @Test
    fun `dotted helpers survive a non-sObject head`() {
        val out = apexSuggestions("System.deb", listOf("Account"), ::describe)
        assertEquals(listOf("System.debug()"), out)
        // and with no schema cache at all, keywords still work
        assertTrue(apexSuggestions("Test.start", null, { null }).contains("Test.startTest()"))
        assertTrue(APEX_DOTTED.contains("Test.startTest()"))
    }

    @Test
    fun `no suggestions for an unknown dotted variable`() {
        // "myVar" is not an sObject and matches no dotted helper — offer nothing, not noise
        assertEquals(emptyList(), apexSuggestions("myVar.Na", listOf("Account"), ::describe))
    }
}
