package dev.skrety.sftoolkit

import com.google.gson.JsonParser
import dev.skrety.sftoolkit.apex.parseApexTestRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Envelope captured live from `sf apex run test --synchronous --json` (CLI 2.137.7,
 * 2026-07-10). Quirks pinned here: failing runs exit 100 with a full `result`;
 * summary times are unit-suffixed STRINGS ("180 ms"); per-test RunTime is a string.
 */
class ApexTestRunTest {

    private val mixedRun = JsonParser.parseString(
        """
        {"status":100,"result":{
          "summary":{"outcome":"Failed","testsRan":2,"passing":1,"failing":1,"skipped":0,
                     "passRate":"50%","failRate":"50%","testTotalTime":"180 ms"},
          "tests":[
            {"FullName":"SampleIT.passes","Outcome":"Pass","Message":"","StackTrace":"","RunTime":"34"},
            {"FullName":"SampleIT.fails","Outcome":"Fail",
             "Message":"System.AssertException: Assertion Failed: deliberate failure",
             "StackTrace":"Class.SampleIT.fails: line 7, column 1","RunTime":"123"}
          ]}}
        """.trimIndent(),
    ).asJsonObject

    @Test
    fun `mixed run parses counts, string time, and failure details`() {
        val run = parseApexTestRun(mixedRun)!!
        assertEquals("Failed", run.outcome)
        assertEquals(2, run.testsRan)
        assertEquals(1, run.passing)
        assertEquals(1, run.failing)
        assertEquals(0, run.skipped)
        assertEquals("180 ms", run.totalTime)
        assertEquals(1, run.failures.size)
        val f = run.failures[0]
        assertEquals("SampleIT.fails", f.fullName)
        assertEquals("System.AssertException: Assertion Failed: deliberate failure", f.message)
        assertEquals("Class.SampleIT.fails: line 7, column 1", f.stackTrace)
    }

    @Test
    fun `all-pass run has no failures`() {
        val run = parseApexTestRun(
            JsonParser.parseString(
                """
                {"status":0,"result":{
                  "summary":{"outcome":"Passed","testsRan":3,"passing":3,"failing":0,"skipped":0,
                             "testTotalTime":"95 ms"},
                  "tests":[{"FullName":"SampleIT.a","Outcome":"Pass","Message":"","StackTrace":""}]}}
                """.trimIndent(),
            ).asJsonObject,
        )!!
        assertEquals(0, run.failing)
        assertEquals(emptyList(), run.failures)
    }

    @Test
    fun `garbage parses to null`() {
        assertNull(parseApexTestRun(null))
        assertNull(parseApexTestRun(JsonParser.parseString("{}").asJsonObject))
        assertNull(parseApexTestRun(JsonParser.parseString("""{"result":{"tests":[]}}""").asJsonObject))
    }
}
