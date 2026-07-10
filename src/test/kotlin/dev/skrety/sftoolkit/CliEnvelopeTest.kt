package dev.skrety.sftoolkit

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RedactionTest {

    @Test
    fun `query values are masked, everything else passes through`() {
        val args = listOf("data", "query", "--query", "SELECT Id FROM Secret__c WHERE X = 'y'", "-o", "AcmeDev")
        val out = redact(args)
        assertEquals("data", out[0])
        assertTrue(out[3].startsWith("<query redacted"))
        assertEquals("-o", out[4])
        assertEquals("AcmeDev", out[5])
        // short flag too
        assertTrue(redact(listOf("-q", "SELECT Id FROM A"))[1].startsWith("<query redacted"))
        // no query flag → untouched
        assertEquals(listOf("org", "list"), redact(listOf("org", "list")))
    }
}

class SfResultTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    @Test
    fun `error message prefers json message, then non-warning stderr, then exit code`() {
        assertEquals(
            "Nothing to deploy",
            SfResult(1, json("""{"status":1,"message":"Nothing to deploy"}"""), "", "").errorMessage(),
        )
        assertEquals(
            "ERROR: something broke",
            SfResult(1, null, "", " ›   Warning: CLI update available\nERROR: something broke").errorMessage(),
        )
        assertEquals("sf exited with code 7", SfResult(7, null, "", "").errorMessage())
        assertEquals("sf command timed out", SfResult(0, null, "", "", timedOut = true).errorMessage())
        assertEquals("Cancelled", SfResult(0, null, "", "", cancelled = true).errorMessage())
    }

    @Test
    fun `ok requires exit zero and no timeout or cancel`() {
        assertTrue(SfResult(0, null, "", "").ok)
        assertTrue(!SfResult(1, null, "", "").ok)
        assertTrue(!SfResult(0, null, "", "", timedOut = true).ok)
        assertTrue(!SfResult(0, null, "", "", cancelled = true).ok)
    }
}

class SourceFailureTest {

    // Trimmed copy of a real `sf project deploy start --dry-run --json` failure envelope
    // (captured live 2026-07-10, CLI 2.137.7; names fictionalized).
    private val failureEnvelope = JsonParser.parseString(
        """
        {
          "files": [
            {"fullName": "BrokenSample", "type": "ApexClass", "state": "Failed",
             "filePath": "force-app/main/default/classes/BrokenSample.cls",
             "lineNumber": 3, "columnNumber": 17, "problemType": "Error",
             "error": "Illegal assignment from String to Integer (3:17)"},
            {"fullName": "BrokenSample", "type": "ApexClass", "state": "Failed",
             "filePath": "force-app/main/default/classes/BrokenSample.cls",
             "lineNumber": 4, "columnNumber": 5, "problemType": "Error",
             "error": "Missing ';' at '}' (4:5)"}
          ],
          "checkOnly": true, "done": true
        }
        """.trimIndent(),
    ).asJsonObject

    // Real success shape: dry-run of an unchanged class.
    private val successEnvelope = JsonParser.parseString(
        """
        {
          "files": [
            {"fullName": "GoodSample", "type": "ApexClass", "state": "Unchanged",
             "filePath": "force-app/main/default/classes/GoodSample.cls"},
            {"fullName": "GoodSample", "type": "ApexClass", "state": "Unchanged",
             "filePath": "force-app/main/default/classes/GoodSample.cls-meta.xml"}
          ]
        }
        """.trimIndent(),
    ).asJsonObject

    @Test
    fun `failure lines carry type, name and compiler error`() {
        val lines = sourceFailureLines(sourceFiles(failureEnvelope))
        assertEquals(2, lines.size)
        assertEquals("ApexClass BrokenSample: Illegal assignment from String to Integer (3:17)", lines[0])
        assertEquals("ApexClass BrokenSample: Missing ';' at '}' (4:5)", lines[1])
    }

    @Test
    fun `success envelope produces no failure lines`() {
        assertEquals(2, sourceFiles(successEnvelope).size)
        assertEquals(emptyList(), sourceFailureLines(sourceFiles(successEnvelope)))
    }

    @Test
    fun `missing or malformed files array is tolerated`() {
        assertEquals(emptyList(), sourceFiles(null))
        assertEquals(emptyList(), sourceFiles(JsonParser.parseString("{}").asJsonObject))
        assertEquals(
            emptyList(),
            sourceFiles(JsonParser.parseString("""{"files": "oops"}""").asJsonObject),
        )
    }
}
