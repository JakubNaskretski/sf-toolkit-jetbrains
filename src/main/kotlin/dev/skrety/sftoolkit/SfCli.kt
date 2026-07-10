package dev.skrety.sftoolkit

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.nio.charset.StandardCharsets

// Family rule: never log SOQL/user content — redact values following query flags.
internal fun redact(args: List<String>): List<String> {
    val out = args.toMutableList()
    for (i in out.indices) {
        if ((out[i] == "--query" || out[i] == "-q") && i + 1 < out.size) {
            out[i + 1] = "<query redacted, ${out[i + 1].length} chars>"
        }
    }
    return out
}

fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString

fun JsonObject.bool(key: String): Boolean? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

data class SfResult(
    val exitCode: Int,
    val json: JsonObject?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut && !cancelled

    fun resultObj(): JsonObject? = json?.get("result")?.takeIf { it.isJsonObject }?.asJsonObject

    fun errorMessage(): String = when {
        cancelled -> "Cancelled"
        timedOut -> "sf command timed out"
        else -> json?.str("message")
            ?: stderr.lineSequence().firstOrNull { it.isNotBlank() && !it.contains("Warning:") }
            ?: "sf exited with code $exitCode"
    }
}

@Service(Service.Level.PROJECT)
class SfCli(private val project: Project) {

    fun resolveBinary(): String? {
        SfSettings.get().state.sfPath?.takeIf { it.isNotBlank() }?.let { return it }
        // ponytail: sf.cmd works via GeneralCommandLine on Windows in practice — verify on machine #2 in M1 acceptance
        val names = if (SystemInfo.isWindows) listOf("sf.cmd", "sf.exe", "sf.bat", "sf") else listOf("sf")
        for (name in names) PathEnvironmentVariableUtil.findInPath(name)?.let { return it.absolutePath }
        return null
    }

    /**
     * Runs `sf <args> --json` synchronously. Call from a background thread only.
     * [quiet] suppresses the missing-CLI error balloon (for unsolicited startup calls).
     */
    fun execute(
        args: List<String>,
        indicator: ProgressIndicator? = null,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        workDir: String? = null,
        quiet: Boolean = false,
    ): SfResult {
        val log = SfLog.get(project)
        val bin = resolveBinary() ?: run {
            if (!quiet) notifyMissingSf()
            log.error("sf CLI not found (PATH + settings)")
            return SfResult(-1, null, "", "sf CLI not found; set its path in Settings → Tools → SF Toolkit")
        }
        val cmd = GeneralCommandLine(bin)
            .withParameters(args + "--json")
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(StandardCharsets.UTF_8)
        (workDir ?: project.basePath)?.let { cmd.withWorkDirectory(it) }

        log.cmd((listOf("sf") + redact(args) + "--json").joinToString(" "))
        val output: ProcessOutput = try {
            val handler = CapturingProcessHandler(cmd)
            if (indicator != null) handler.runProcessWithProgressIndicator(indicator, timeoutMs, true)
            else handler.runProcess(timeoutMs, true)
        } catch (e: ExecutionException) {
            log.error("failed to start sf: ${e.message}")
            return SfResult(-1, null, "", e.message ?: "failed to start sf")
        }
        val res = SfResult(
            output.exitCode, parseJson(output.stdout), output.stdout, output.stderr,
            output.isTimeout, output.isCancelled,
        )
        // MALFORMED_QUERY messages echo the query verbatim — keep user content out of the
        // persistent log (family rule); the caller's UI still shows the real message.
        val hasUserContent = args.any { it == "--query" || it == "-q" }
        when {
            res.ok -> log.ok("exit 0")
            res.cancelled -> log.warn("cancelled")
            else -> {
                val detail =
                    if (hasUserContent) "(message redacted — query command; details shown in the panel)"
                    else res.errorMessage()
                log.error("exit ${output.exitCode}${if (output.isTimeout) " (timeout)" else ""} — $detail")
            }
        }
        return res
    }

    private fun parseJson(stdout: String): JsonObject? = try {
        val start = stdout.indexOf('{')
        if (start < 0) null
        else JsonParser.parseString(stdout.substring(start)).takeIf { it.isJsonObject }?.asJsonObject
    } catch (_: Exception) {
        null
    }

    private fun notifyMissingSf() {
        NotificationGroupManager.getInstance().getNotificationGroup("SF Toolkit")
            .createNotification(
                "Salesforce CLI (sf) not found",
                "Install the Salesforce CLI or set its full path in Settings → Tools → SF Toolkit.",
                NotificationType.ERROR,
            )
            .addAction(NotificationAction.createSimple("Open settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "SF Toolkit")
            })
            .notify(project)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000

        fun get(project: Project): SfCli = project.service()
    }
}
