package dev.skrety.sftoolkit

import dev.skrety.sftoolkit.schema.FieldInfo
import dev.skrety.sftoolkit.schema.ObjectSchema
import dev.skrety.sftoolkit.soql.SoqlClause
import dev.skrety.sftoolkit.soql.soqlContextAt
import dev.skrety.sftoolkit.soql.soqlSuggestions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SoqlContextTest {

    private fun ctxAt(textWithCaret: String): Pair<String, Int> {
        val caret = textWithCaret.indexOf('|')
        return textWithCaret.replace("|", "") to caret
    }

    @Test
    fun `clause detection across the query`() {
        val cases = mapOf(
            "SELECT Id, | FROM Account" to SoqlClause.SELECT,
            "SELECT Id FROM Acc|" to SoqlClause.FROM,
            "SELECT Id FROM Account WHERE Na|" to SoqlClause.WHERE,
            "SELECT Id FROM Account ORDER BY N|" to SoqlClause.ORDER_BY,
            "SELECT Id FROM Account GROUP BY T|" to SoqlClause.GROUP_BY,
            "SELECT Id FROM Account LIMIT 1|" to SoqlClause.OTHER,
        )
        for ((text, expected) in cases) {
            val (q, caret) = ctxAt(text)
            assertEquals(expected, soqlContextAt(q, caret).clause, "in: $text")
        }
    }

    @Test
    fun `FROM object found even when it is after the caret`() {
        val (q, caret) = ctxAt("SELECT Na| FROM Account")
        val ctx = soqlContextAt(q, caret)
        assertEquals(SoqlClause.SELECT, ctx.clause)
        assertEquals("Account", ctx.objectName)
        assertEquals("Na", ctx.prefix)
    }

    @Test
    fun `subquery scope uses the inner FROM, outer query keeps its own`() {
        val (q, caret) = ctxAt("SELECT Id, (SELECT La| FROM Contacts) FROM Account")
        val inner = soqlContextAt(q, caret)
        assertEquals("Contacts", inner.objectName)
        assertEquals(SoqlClause.SELECT, inner.clause)

        val (q2, caret2) = ctxAt("SELECT Id, (SELECT LastName FROM Contacts) FROM Account WHERE Na|")
        val outer = soqlContextAt(q2, caret2)
        assertEquals("Account", outer.objectName)
        assertEquals(SoqlClause.WHERE, outer.clause)
    }

    @Test
    fun `keywords inside string literals are ignored`() {
        val (q, caret) = ctxAt("SELECT Id FROM Account WHERE Name = 'select from where' AND Ind|")
        val ctx = soqlContextAt(q, caret)
        assertEquals(SoqlClause.WHERE, ctx.clause)
        assertEquals("Account", ctx.objectName)
        assertEquals("Ind", ctx.prefix)
    }

    @Test
    fun `dotted prefix is captured whole`() {
        val (q, caret) = ctxAt("SELECT Owner.Na| FROM Account")
        assertEquals("Owner.Na", soqlContextAt(q, caret).prefix)
    }

    @Test
    fun `grouping parens in WHERE are not query boundaries`() {
        val (q, caret) = ctxAt("SELECT Id FROM Account WHERE (Rating = 'Hot' AND Na|)")
        val ctx = soqlContextAt(q, caret)
        assertEquals(SoqlClause.WHERE, ctx.clause)
        assertEquals("Account", ctx.objectName)
        assertEquals("Na", ctx.prefix)
    }

    @Test
    fun `function-call parens fall back to the enclosing query scope`() {
        val (q, caret) = ctxAt("SELECT Id FROM Account WHERE CALENDAR_YEAR(Crea|) = 2026")
        val ctx = soqlContextAt(q, caret)
        assertEquals(SoqlClause.WHERE, ctx.clause)
        assertEquals("Account", ctx.objectName)
        assertEquals("Crea", ctx.prefix)
    }

    @Test
    fun `real subqueries still bind to their inner FROM`() {
        val (q, caret) = ctxAt("SELECT Id, (SELECT La| FROM Contacts) FROM Account WHERE (Rating = 'Hot')")
        assertEquals("Contacts", soqlContextAt(q, caret).objectName)
    }
}

class SoqlSuggestionsTest {

    private val schemas = mapOf(
        "Account" to ObjectSchema(
            "Account",
            listOf(
                FieldInfo("Name", "string", emptyList(), null),
                FieldInfo("Industry", "picklist", emptyList(), null),
                FieldInfo("OwnerId", "reference", listOf("Group", "User"), "Owner"),
            ),
            emptyList(),
        ),
        "User" to ObjectSchema(
            "User",
            listOf(
                FieldInfo("Name", "string", emptyList(), null),
                FieldInfo("Alias", "string", emptyList(), null),
            ),
            emptyList(),
        ),
    )
    private val provider: (String) -> ObjectSchema? = { schemas[it] }
    private val objects = listOf("Account", "AccountContactRole", "Contact")

    private fun ctx(textWithCaret: String) =
        soqlContextAt(textWithCaret.replace("|", ""), textWithCaret.indexOf('|'))

    @Test
    fun `FROM completion ranks startsWith first`() {
        val s = soqlSuggestions(ctx("SELECT Id FROM Acc|"), objects, provider)
        assertEquals(listOf("Account", "AccountContactRole"), s)
    }

    @Test
    fun `field completion filters by prefix`() {
        val s = soqlSuggestions(ctx("SELECT In| FROM Account"), objects, provider)
        assertEquals(listOf("Industry"), s)
    }

    @Test
    fun `lookup fields offer relationship dot entries`() {
        val s = soqlSuggestions(ctx("SELECT Own| FROM Account"), objects, provider)
        assertEquals(listOf("Owner.", "OwnerId"), s)
    }

    @Test
    fun `dot chain resolves through the relationship, preferring User for polymorphic`() {
        val s = soqlSuggestions(ctx("SELECT Owner.Al| FROM Account"), objects, provider)
        assertEquals(listOf("Owner.Alias"), s)
    }

    @Test
    fun `uncached describe yields empty, never a CLI call`() {
        val s = soqlSuggestions(ctx("SELECT X| FROM Contact"), objects, provider)
        assertTrue(s.isEmpty())
    }
}
