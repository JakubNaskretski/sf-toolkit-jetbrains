package dev.skrety.sftoolkit

import com.google.gson.JsonParser
import dev.skrety.sftoolkit.results.RunKind
import dev.skrety.sftoolkit.results.RunResult
import dev.skrety.sftoolkit.results.parseDeployJobId
import dev.skrety.sftoolkit.results.parseDeployReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixtures are anonymized captures of REAL envelopes (sf CLI 2.137.7, API 67.0):
 * async start, report pending/succeeded/failed, cancel-error. Key quirks pinned here:
 * report exits 0 even for Failed deploys; numberFiles/zipSize are strings while the
 * counters are numbers; line/col were numbers but must parse tolerantly.
 */
class DeployReportTest {

    private fun obj(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun `job id comes from result id`() {
        val start = obj(
            """{"status":0,"result":{"id":"0AfXX00000000001AAA","done":false,"status":"Queued",
                "files":[],"zipSize":2066,"zipFileCount":7},"warnings":[]}""",
        )
        assertEquals("0AfXX00000000001AAA", parseDeployJobId(start))
        assertNull(parseDeployJobId(obj("""{"status":1,"name":"SomeError","message":"x"}""")))
    }

    @Test
    fun `pending report has zero totals and is not done`() {
        val report = parseDeployReport(
            obj(
                """{"status":0,"result":{"done":false,"status":"Pending","success":false,
                "numberComponentErrors":0,"numberComponentsDeployed":0,"numberComponentsTotal":0,
                "numberFiles":"8","numberTestErrors":0,"numberTestsCompleted":0,"numberTestsTotal":0,
                "zipSize":"1315","id":"0AfXX00000000001AAA","files":[]},"warnings":[]}""",
            ),
        )
        assertNotNull(report)
        assertFalse(report.done)
        assertEquals("Pending", report.status)
        assertEquals(0, report.componentsTotal) // divide-by-zero guard relies on this
    }

    @Test
    fun `succeeded report carries per-file rows`() {
        val report = parseDeployReport(
            obj(
                """{"status":0,"result":{"done":true,"status":"Succeeded","success":true,
                "numberComponentErrors":0,"numberComponentsDeployed":3,"numberComponentsTotal":3,
                "numberFiles":"8","numberTestErrors":0,"numberTestsCompleted":0,"numberTestsTotal":0,
                "zipSize":"1315","id":"0AfXX00000000001AAA","files":[
                  {"fullName":"AcmeProbe","type":"ApexClass","state":"Created",
                   "filePath":"/Users/dev/acme-dev/force-app/main/default/classes/AcmeProbe.cls"},
                  {"fullName":"AcmeProbe","type":"ApexClass","state":"Created",
                   "filePath":"/Users/dev/acme-dev/force-app/main/default/classes/AcmeProbe.cls-meta.xml"}
                ]},"warnings":[]}""",
            ),
        )
        assertNotNull(report)
        assertTrue(report.done)
        assertTrue(report.success)
        assertEquals(3, report.componentsDeployed)
        assertEquals(2, report.files.size) // one row per LOCAL FILE (.cls + meta.xml)
        assertTrue(report.files.none { it.failed })
    }

    @Test
    fun `failed report yields failed rows with line and column`() {
        val report = parseDeployReport(
            obj(
                """{"status":0,"result":{"done":true,"status":"Failed","success":false,
                "numberComponentErrors":2,"numberComponentsDeployed":0,"numberComponentsTotal":2,
                "numberFiles":"4","numberTestErrors":0,"numberTestsCompleted":0,"numberTestsTotal":0,
                "zipSize":"548","id":"0AfXX00000000002AAA","files":[
                  {"fullName":"AcmeBroken","type":"ApexClass","state":"Failed","problemType":"Error",
                   "filePath":"/Users/dev/acme-dev/force-app/main/default/classes/AcmeBroken.cls",
                   "lineNumber":2,"columnNumber":27,
                   "error":"Missing return statement required return type: Integer (2:27)"},
                  {"fullName":"AcmeBroken","type":"ApexClass","state":"Failed","problemType":"Error",
                   "filePath":"/Users/dev/acme-dev/force-app/main/default/classes/AcmeBroken.cls",
                   "lineNumber":4,"columnNumber":9,"error":"Unexpected token 'return'. (4:9)"}
                ]},"warnings":[]}""",
            ),
        )
        assertNotNull(report)
        assertTrue(report.done)
        assertFalse(report.success)
        assertEquals(2, report.componentErrors)
        val failed = report.files.filter { it.failed }
        assertEquals(2, failed.size)
        assertEquals(2, failed[0].line)
        assertEquals(27, failed[0].column)
        assertTrue(failed[0].failureLine().startsWith("ApexClass AcmeBroken:"))
    }

    @Test
    fun `string-typed counters and line numbers still parse`() {
        val report = parseDeployReport(
            obj(
                """{"status":0,"result":{"done":true,"status":"Failed","success":false,
                "numberComponentErrors":"1","numberComponentsDeployed":"0","numberComponentsTotal":"1",
                "numberTestErrors":"0","numberTestsCompleted":"0","numberTestsTotal":"0",
                "files":[{"fullName":"AcmeBroken","type":"ApexClass","state":"Failed",
                  "filePath":"classes/AcmeBroken.cls","lineNumber":"7","columnNumber":"3",
                  "error":"boom (7:3)"}]},"warnings":[]}""",
            ),
        )
        assertNotNull(report)
        assertEquals(1, report.componentErrors)
        assertEquals(7, report.files[0].line)
        assertEquals(3, report.files[0].column)
    }

    @Test
    fun `error envelopes without result parse to null`() {
        // cancel on finished job / wrong workdir — top-level error, no result key
        val cancelError = obj(
            """{"name":"CannotCancelDeployError","message":"Can't cancel deploy because it's
            already completed.","exitCode":1,"context":"DeployMetadataCancel",
            "data":{"errorCode":"sf:INVALID_ID_FIELD"},"code":"CannotCancelDeployError",
            "status":1,"commandName":"DeployMetadataCancel"}""",
        )
        assertNull(parseDeployReport(cancelError))
        assertNull(parseDeployReport(null))
    }

    @Test
    fun `run summary counts failures and keeps the hard error`() {
        val ok = RunResult(RunKind.DEPLOY, "acme-dev", "/p", emptyList(), 1500, null)
        assertFalse(ok.hasFailures)
        assertTrue(ok.summaryLine().contains("acme-dev"))
        val hard = RunResult(RunKind.VALIDATE, "acme-dev", "/p", emptyList(), 900, "Failed: 0 component error(s), 3 test error(s)")
        assertTrue(hard.hasFailures)
        assertTrue(hard.summaryLine().contains("test error"))
    }
}
