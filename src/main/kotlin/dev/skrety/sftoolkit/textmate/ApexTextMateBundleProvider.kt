package dev.skrety.sftoolkit.textmate

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider

/**
 * Feeds the IDE's TextMate engine a VS Code-style bundle with the Apex + SOQL grammars.
 * Grammars are classpath resources, extracted to the system dir because the engine
 * wants a filesystem path.
 */
class ApexTextMateBundleProvider : TextMateBundleProvider {

    override fun getBundles(): List<TextMateBundleProvider.PluginBundle> {
        val dir = extractBundle() ?: return emptyList()
        return listOf(TextMateBundleProvider.PluginBundle("Apex (SF Toolkit)", dir))
    }

    private fun extractBundle(): Path? {
        try {
            val target = Path.of(PathManager.getSystemPath(), "sf-toolkit", "textmate-apex")
            Files.createDirectories(target)
            for (name in FILES) {
                val res = javaClass.getResourceAsStream("/textmate/apex/$name")
                if (res == null) {
                    LOG.warn("Missing bundled resource textmate/apex/$name")
                    return null
                }
                res.use { Files.copy(it, target.resolve(name), StandardCopyOption.REPLACE_EXISTING) }
            }
            return target
        } catch (e: Exception) {
            LOG.warn("Failed to extract Apex TextMate bundle", e)
            return null
        }
    }

    companion object {
        private val LOG = Logger.getInstance(ApexTextMateBundleProvider::class.java)
        private val FILES = listOf("package.json", "apex.tmLanguage", "soql.tmLanguage", "LICENSE.txt")
    }
}
