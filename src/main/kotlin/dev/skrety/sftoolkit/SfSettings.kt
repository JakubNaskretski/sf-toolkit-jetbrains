package dev.skrety.sftoolkit

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

@Service
@State(name = "SfToolkitSettings", storages = [Storage("sfToolkit.xml")])
class SfSettings : SimplePersistentStateComponent<SfSettings.MyState>(MyState()) {
    class MyState : BaseState() {
        var sfPath by string()
        var apexLspJarPath by string()
    }

    companion object {
        fun get(): SfSettings = ApplicationManager.getApplication().getService(SfSettings::class.java)
    }
}

class SfSettingsConfigurable : Configurable {
    private val sfPathField = JBTextField()
    private val lspJarField = JBTextField()

    override fun getDisplayName(): String = "SF Toolkit"

    override fun createComponent(): JComponent {
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("Salesforce CLI (sf) path (blank = find on PATH):", sfPathField)
            .addLabeledComponent("Apex Language Server jar (blank = auto-detect from VS Code):", lspJarField)
            .panel
        return JPanel(BorderLayout()).apply { add(form, BorderLayout.NORTH) }
    }

    override fun isModified(): Boolean {
        val s = SfSettings.get().state
        return sfPathField.text.trim() != (s.sfPath ?: "") || lspJarField.text.trim() != (s.apexLspJarPath ?: "")
    }

    override fun apply() {
        val s = SfSettings.get().state
        s.sfPath = sfPathField.text.trim().ifBlank { null }
        s.apexLspJarPath = lspJarField.text.trim().ifBlank { null }
    }

    override fun reset() {
        val s = SfSettings.get().state
        sfPathField.text = s.sfPath ?: ""
        lspJarField.text = s.apexLspJarPath ?: ""
    }
}
