package dev.skrety.sftoolkit.apex

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.EditorTextField
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBFont
import dev.skrety.sftoolkit.OrgService
import dev.skrety.sftoolkit.SfCli
import dev.skrety.sftoolkit.filetypes.ApexFileType
import dev.skrety.sftoolkit.ui.OrgCombo
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

class ApexToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ApexPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

class ApexPanel(private val project: Project) : Disposable {

    private val codeField = EditorTextField(
        EditorFactory.getInstance().createDocument("System.debug('hello');"),
        project,
        ApexFileType,
        false,
        false,
    )
    private val orgCombo = OrgCombo(project)
    private val runButton = JButton("Run").apply {
        toolTipText = "Execute anonymous Apex (Ctrl/Cmd+Enter). Click again to cancel."
    }
    private val statusLabel = JBLabel(" ").apply { setCopyable(true) }
    private val output = JTextArea().apply {
        isEditable = false
        font = JBFont.create(java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12))
        lineWrap = false
    }

    @Volatile
    private var runningIndicator: ProgressIndicator? = null

    // EDT-set in-flight guard — same double-submit protection as the SOQL panel.
    @Volatile
    private var inFlight = false

    @Volatile
    private var cancelRequested = false

    val component: JComponent = build()

    private fun build(): JComponent {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(runButton)
        }
        orgCombo.addTo(toolbar)
        val top = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(codeField, BorderLayout.CENTER)
        }
        val bottom = JPanel(BorderLayout()).apply {
            add(JBScrollPane(output), BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }
        runButton.addActionListener { toggleRun() }
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                if (!inFlight) toggleRun()
            }
        }.registerCustomShortcutSet(CustomShortcutSet.fromString("ctrl ENTER", "meta ENTER"), codeField)

        return OnePixelSplitter(true, 0.4f).apply {
            firstComponent = top
            secondComponent = bottom
        }
    }

    override fun dispose() {
        orgCombo.dispose()
    }

    private fun toggleRun() {
        if (inFlight) {
            cancelRequested = true
            runningIndicator?.cancel()
            return
        }
        val org = OrgService.get(project).requireCurrent() ?: return
        val code = codeField.text
        if (code.isBlank()) return
        inFlight = true
        cancelRequested = false
        runButton.text = "Cancel"
        statusLabel.text = "Running against $org…"

        object : Task.Backgroundable(project, "Anonymous Apex", true) {
            override fun run(indicator: ProgressIndicator) {
                runningIndicator = indicator
                if (cancelRequested) indicator.cancel()
                // Code travels via temp file, never argv — nothing user-typed hits the log.
                val tmp = Files.createTempFile("sf-toolkit-anon-", ".apex")
                val res = try {
                    Files.writeString(tmp, code)
                    SfCli.get(project).execute(
                        listOf("apex", "run", "--file", tmp.toString(), "-o", org),
                        indicator,
                        timeoutMs = 300_000,
                    )
                } finally {
                    Files.deleteIfExists(tmp)
                }
                val app = ApplicationManager.getApplication()
                if (res.cancelled) {
                    app.invokeLater { statusLabel.text = "Cancelled" }
                    return
                }
                val outcome = parseApexRunOutcome(res.json)
                app.invokeLater {
                    if (project.isDisposed) return@invokeLater
                    if (outcome == null) {
                        output.text = res.errorMessage()
                        statusLabel.text = "Error — $org"
                    } else {
                        output.text = formatApexRunOutcome(outcome)
                        output.caretPosition = 0
                        statusLabel.text = when {
                            outcome.success -> "Success — $org"
                            !outcome.compiled -> "Compile error — $org"
                            else -> "Runtime error — $org"
                        }
                    }
                }
            }

            override fun onFinished() {
                runningIndicator = null
                inFlight = false
                runButton.text = "Run"
            }
        }.queue()
    }
}
