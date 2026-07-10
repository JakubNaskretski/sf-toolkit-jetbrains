package dev.skrety.sftoolkit

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.diagnostic.Logger

/** Field-debuggable health checks for the two silent-failure-prone integrations. */
object SfHealth {
    /** Reflective — the TextMate plugin is an optional dependency. Forces bundle init. */
    fun grammarActive(fileName: String = "Probe.cls"): Boolean = try {
        val service = Class.forName("org.jetbrains.plugins.textmate.TextMateService")
            .getMethod("getInstance").invoke(null)
        service?.javaClass?.getMethod("getLanguageDescriptorByFileName", CharSequence::class.java)
            ?.invoke(service, fileName) != null
    } catch (t: Throwable) {
        Logger.getInstance(SfHealth::class.java).warn("textmate probe failed: $t")
        false
    }

    fun pluginState(id: String): String {
        val descriptor = com.intellij.ide.plugins.PluginManagerCore
            .getPlugin(com.intellij.openapi.extensions.PluginId.getId(id))
        return when {
            descriptor == null -> "NOT INSTALLED"
            !descriptor.isEnabled -> "DISABLED"
            else -> "enabled (${descriptor.version})"
        }
    }

    fun scopesCount(): Int = try {
        SfSearchScopes().customScopes.size
    } catch (t: Throwable) {
        Logger.getInstance(SfHealth::class.java).warn("scopes probe failed: $t")
        -1
    }
}

/** Logs health at app start (welcome screen included) — greppable in idea.log. */
class SfAppHealth : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: List<String>) {
        val log = Logger.getInstance(SfAppHealth::class.java)
        log.info("SF Toolkit health: probe starting")
        com.intellij.openapi.application.ApplicationManager.getApplication()
            .executeOnPooledThread {
                log.info("SF Toolkit health: search-scopes=${SfHealth.scopesCount()}/4")
                log.info("SF Toolkit health: apex-grammar-active=${SfHealth.grammarActive()}")
            }
    }
}
