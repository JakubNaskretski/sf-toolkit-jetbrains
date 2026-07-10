package dev.skrety.sftoolkit

import com.google.gson.JsonParser
import dev.skrety.sftoolkit.apex.parseLogBody
import dev.skrety.sftoolkit.apex.parseLogList
import dev.skrety.sftoolkit.soql.mruAdd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApexLogsTest {

    @Test
    fun `log list parses real shape and sorts newest first`() {
        // Shape from a live `sf apex list log --json` (values fictional).
        val result = JsonParser.parseString(
            """
            [
              {"attributes":{"type":"ApexLog"},"Id":"07L000000000001AAA",
               "Application":"Unknown","DurationMilliseconds":1510,
               "Location":"SystemLog","LogLength":2882,
               "LogUser":{"attributes":{"type":"Name"},"Name":"Dana Placeholder"},
               "Operation":"/services/data/v67.0/tooling/runTestsSynchronous",
               "Request":"Api","StartTime":"2026-07-10T10:01:08+0000","Status":"Success"},
              {"Id":"07L000000000002AAA","LogLength":100,"DurationMilliseconds":5,
               "Operation":"ApexRun","StartTime":"2026-07-11T09:00:00+0000","Status":"Success"}
            ]
            """.trimIndent(),
        )
        val logs = parseLogList(result)
        assertEquals(2, logs.size)
        assertEquals("07L000000000002AAA", logs[0].id) // newer first
        assertEquals("Dana Placeholder", logs[1].user)
        assertEquals(2882, logs[1].lengthBytes)
        assertEquals(emptyList(), parseLogList(null))
    }

    @Test
    fun `log body parses array-of-object, bare object, and rejects blank`() {
        assertEquals(
            "62.0 APEX_CODE,DEBUG",
            parseLogBody(JsonParser.parseString("""[{"log":"62.0 APEX_CODE,DEBUG"}]""")),
        )
        assertEquals("x", parseLogBody(JsonParser.parseString("""{"log":"x"}""")))
        assertNull(parseLogBody(JsonParser.parseString("""[{"log":""}]""")))
        assertNull(parseLogBody(null))
    }

    @Test
    fun `mru dedups, prepends and caps`() {
        assertEquals(listOf("b", "a"), mruAdd(listOf("a"), "b"))
        assertEquals(listOf("a", "b"), mruAdd(listOf("b", "a"), "a"))
        assertEquals(listOf("x"), mruAdd(emptyList(), "  x  "))
        assertEquals(emptyList(), mruAdd(emptyList(), "   "))
        val capped = mruAdd((1..30).map { "q$it" }, "new", cap = 25)
        assertEquals(25, capped.size)
        assertEquals("new", capped.first())
    }
}
