package dev.skrety.sftoolkit

import com.google.gson.JsonParser
import dev.skrety.sftoolkit.apex.formatApexRunOutcome
import dev.skrety.sftoolkit.apex.parseApexRunOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Envelope shapes captured live from `sf apex run --json` (CLI 2.137.7, 2026-07-10). */
class ApexRunTest {

    @Test
    fun `success payload lives in result with numeric positions`() {
        val envelope = JsonParser.parseString(
            """
            {"status":0,"result":{"success":true,"compiled":true,"compileProblem":"",
             "exceptionMessage":"","exceptionStackTrace":"","line":-1,"column":-1,
             "logs":"67.0 APEX_CODE,DEBUG\nExecute Anonymous: System.debug('x');\n"}}
            """.trimIndent(),
        ).asJsonObject
        val o = parseApexRunOutcome(envelope)!!
        assertTrue(o.success)
        assertTrue(o.compiled)
        assertNull(o.compileProblem)
        assertNull(o.line) // -1 means "no position"
        assertTrue(o.logs!!.contains("Execute Anonymous"))
        assertTrue(formatApexRunOutcome(o).startsWith("Success"))
    }

    @Test
    fun `compile failure payload lives in data with string positions`() {
        val envelope = JsonParser.parseString(
            """
            {"status":1,"name":"executeCompileFailure",
             "message":"Compilation failed at Line 1 column 9 with the error:\n\nIllegal assignment from String to Integer",
             "data":{"success":false,"compiled":false,
                     "compileProblem":"Illegal assignment from String to Integer",
                     "exceptionMessage":"","exceptionStackTrace":"","line":"1","column":"9","logs":""}}
            """.trimIndent(),
        ).asJsonObject
        val o = parseApexRunOutcome(envelope)!!
        assertFalse(o.success)
        assertFalse(o.compiled)
        assertEquals("Illegal assignment from String to Integer", o.compileProblem)
        assertEquals(1, o.line)
        assertEquals(9, o.column)
        assertNull(o.logs)
        val text = formatApexRunOutcome(o)
        assertTrue(text.startsWith("Compile error (line 1, col 9)"))
        assertTrue(text.contains("Illegal assignment"))
    }

    @Test
    fun `runtime failure keeps compiled=true and carries exception plus logs`() {
        val envelope = JsonParser.parseString(
            """
            {"status":1,"name":"executeRuntimeFailure",
             "data":{"success":false,"compiled":true,"compileProblem":"",
                     "exceptionMessage":"System.ListException: List index out of bounds: 5",
                     "exceptionStackTrace":"AnonymousBlock: line 1, column 1",
                     "line":"1","column":"1","logs":"67.0 APEX_CODE,DEBUG\n..."}}
            """.trimIndent(),
        ).asJsonObject
        val o = parseApexRunOutcome(envelope)!!
        assertFalse(o.success)
        assertTrue(o.compiled)
        assertEquals("System.ListException: List index out of bounds: 5", o.exceptionMessage)
        assertTrue(formatApexRunOutcome(o).startsWith("Runtime error (line 1, col 1)"))
        assertTrue(formatApexRunOutcome(o).contains("Debug log"))
    }

    @Test
    fun `garbage envelopes parse to null`() {
        assertNull(parseApexRunOutcome(null))
        assertNull(parseApexRunOutcome(JsonParser.parseString("{}").asJsonObject))
        assertNull(parseApexRunOutcome(JsonParser.parseString("""{"result":{}}""").asJsonObject))
        assertNull(parseApexRunOutcome(JsonParser.parseString("""{"data":{"foo":1}}""").asJsonObject))
    }
}
