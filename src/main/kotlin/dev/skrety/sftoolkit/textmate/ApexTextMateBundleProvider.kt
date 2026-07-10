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

    private fun extractBundle(): Path? = extractApexBundle()

    companion object {
        private val FILES get() = APEX_BUNDLE_FILES
    }
}

private val LOG = Logger.getInstance(ApexTextMateBundleProvider::class.java)
internal val APEX_BUNDLE_FILES = listOf("package.json", "apex.tmLanguage", "soql.tmLanguage", "LICENSE.txt")

/** Extracts the bundle resources to a stable path; shared by the EP provider and the self-heal. */
internal fun extractApexBundle(): Path? {
    try {
        val target = Path.of(PathManager.getSystemPath(), "sf-toolkit", "textmate-apex")
        Files.createDirectories(target)
        for (name in APEX_BUNDLE_FILES) {
            val res = ApexTextMateBundleProvider::class.java.getResourceAsStream("/textmate/apex/$name")
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

/**
 * Fallback for installs where the bundleProvider EP path doesn't deliver (field report:
 * plain-text .cls): registers the extracted bundle as a TextMate USER bundle and reloads.
 * Idempotent; invoked reflectively from SfStartup (this class needs the textmate plugin).
 */
object TextMateSelfHeal {
    @JvmStatic
    fun ensureGrammar(): Boolean {
        if (resolves()) return true
        val dir = extractApexBundle() ?: return false
        return try {
            val settings =
                org.jetbrains.plugins.textmate.configuration.TextMateUserBundlesSettings.getInstance()
            val already = settings?.bundles?.keys?.any { it == dir.toString() } == true
            if (!already) settings?.addBundle(dir.toString(), "Apex (SF Toolkit)")
            org.jetbrains.plugins.textmate.TextMateService.getInstance().reloadEnabledBundles()
            resolves()
        } catch (t: Throwable) {
            LOG.warn("TextMate self-heal failed", t)
            false
        }
    }

    private fun resolves(): Boolean = try {
        org.jetbrains.plugins.textmate.TextMateService.getInstance()
            ?.getLanguageDescriptorByFileName("Probe.cls") != null
    } catch (_: Throwable) {
        false
    }
}
