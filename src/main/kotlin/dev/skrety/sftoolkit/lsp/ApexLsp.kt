package dev.skrety.sftoolkit.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.redhat.devtools.lsp4ij.LanguageServerEnablementSupport
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import dev.skrety.sftoolkit.SfSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs Salesforce's Apex Language Server (apex-jorje-lsp.jar — the LS behind VS Code's
 * Apex support) through LSP4IJ. The jar is never bundled: it is auto-detected from an
 * installed salesforcedx-vscode-apex extension, or pointed at via settings.
 */
class ApexLspFactory : LanguageServerFactory, LanguageServerEnablementSupport {

    override fun isEnabled(project: Project): Boolean = userEnabled && findApexLspJar() != null

    override fun setEnabled(enabled: Boolean, project: Project) {
        userEnabled = enabled
    }

    override fun createConnectionProvider(project: Project): StreamConnectionProvider =
        ApexLspConnectionProvider(project)

    companion object {
        // ponytail: in-memory toggle; persist in SfSettings if anyone ever asks for it
        @Volatile
        private var userEnabled = true
    }
}

class ApexLspConnectionProvider(project: Project) : ProcessStreamConnectionProvider() {
    init {
        val jar = findApexLspJar()
        if (jar != null) {
            // The IDE's own runtime (JBR 21) satisfies jorje's Java 11+ requirement.
            val java = Path.of(
                System.getProperty("java.home"),
                "bin",
                if (SystemInfo.isWindows) "java.exe" else "java",
            )
            // Flags mirror salesforcedx-vscode-apex's launch configuration.
            commands = listOf(
                java.toString(),
                "-cp", jar.toString(),
                "-Ddebug.internal.errors=true",
                "-Ddebug.semantic.errors=false",
                "-Ddebug.completion.statistics=false",
                "-Dlwc.typegeneration.disabled=true",
                "apex.jorje.lsp.ApexLanguageServerLauncher",
            )
            workingDirectory = project.basePath
        }
    }
}

/** Where "Download Apex Language Server" installs the jar — no VS Code required. */
fun managedApexLspJar(): Path = Path.of(
    com.intellij.openapi.application.PathManager.getSystemPath(),
    "sf-toolkit", "apex-jorje-lsp.jar",
)

fun findApexLspJar(): Path? {
    SfSettings.get().state.apexLspJarPath?.takeIf { it.isNotBlank() }?.let {
        val p = Path.of(it)
        if (Files.isRegularFile(p)) return p
    }
    managedApexLspJar().takeIf { Files.isRegularFile(it) }?.let { return it }
    val extensions = Path.of(System.getProperty("user.home"), ".vscode", "extensions")
    if (!Files.isDirectory(extensions)) return null
    return Files.list(extensions).use { stream ->
        stream
            .filter { it.fileName.toString().startsWith("salesforce.salesforcedx-vscode-apex-") }
            .sorted(Comparator.reverseOrder()) // ponytail: lexical "newest"; fine until a 10.x/9.x flip bites
            .map { it.resolve("dist").resolve("apex-jorje-lsp.jar") }
            .filter { Files.isRegularFile(it) }
            .findFirst()
            .orElse(null)
    }
}
