package dev.skrety.sftoolkit.soql

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.LanguageTextField
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import dev.skrety.sftoolkit.OrgService
import dev.skrety.sftoolkit.SfCli
import dev.skrety.sftoolkit.refreshOrgsInBackground
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

class SoqlToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SoqlPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

class SoqlPanel(private val project: Project) : Disposable {

    private val queryField =
        LanguageTextField(PlainTextLanguage.INSTANCE, project, "SELECT Id, Name FROM Account", false)
    private val orgCombo = ComboBox<String>().apply {
        toolTipText = "Org this query runs against (also updates the project-wide selection)"
        setMinimumAndPreferredWidth(220)
    }
    private val refreshOrgsButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Refresh org list"
        isFocusable = false
    }
    private val autoLimit = JBCheckBox("Auto LIMIT 200", true).apply {
        toolTipText = "Append LIMIT 200 when the query has no top-level LIMIT"
    }
    private val runButton = JButton("Run").apply {
        toolTipText = "Run query (Ctrl/Cmd+Enter). Click again to cancel."
    }
    private val statusLabel = JBLabel(" ").apply { setCopyable(true) }
    private val tableModel = object : DefaultTableModel() {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val table = JBTable(tableModel).apply {
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        autoCreateRowSorter = true
        emptyText.text = "No results — run a query (Ctrl/Cmd+Enter)"
    }

    private val orgListener = Runnable { refreshOrgCombo() }
    private var updatingCombo = false

    @Volatile
    private var runningIndicator: ProgressIndicator? = null

    // Set on the EDT before queue() so a second click can't double-submit while the
    // task is still scheduling (review finding: the indicator alone races).
    @Volatile
    private var inFlight = false

    @Volatile
    private var cancelRequested = false

    val component: JComponent = build()

    private fun build(): JComponent {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(runButton)
            add(JBLabel("Org:"))
            add(orgCombo)
            add(refreshOrgsButton)
            add(autoLimit)
        }
        val top = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(queryField, BorderLayout.CENTER)
        }
        val bottom = JPanel(BorderLayout()).apply {
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }
        TableSpeedSearch.installOn(table)

        runButton.addActionListener { toggleRun() }
        refreshOrgsButton.addActionListener { refreshOrgsInBackground(project) }
        orgCombo.addActionListener {
            if (!updatingCombo) {
                val selected = orgCombo.selectedItem as? String
                if (!selected.isNullOrBlank() && selected != OrgService.get(project).current) {
                    OrgService.get(project).current = selected
                }
            }
        }
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                if (!inFlight) toggleRun()
            }
        }.registerCustomShortcutSet(CustomShortcutSet.fromString("ctrl ENTER", "meta ENTER"), queryField)

        val orgService = OrgService.get(project)
        orgService.addChangeListener(orgListener)
        refreshOrgCombo()
        if (orgService.orgs.isEmpty()) refreshOrgsInBackground(project, quiet = true)

        return OnePixelSplitter(true, 0.3f).apply {
            firstComponent = top
            secondComponent = bottom
        }
    }

    override fun dispose() {
        OrgService.get(project).removeChangeListener(orgListener)
    }

    /** Mirrors OrgService into the combo; the combo is the panel's org switcher. */
    private fun refreshOrgCombo() {
        updatingCombo = true
        try {
            val orgService = OrgService.get(project)
            val items = orgService.orgs.map { it.display }.toMutableList()
            val current = orgService.current
            if (current != null && current !in items) items.add(0, current)
            orgCombo.model = DefaultComboBoxModel(items.toTypedArray())
            orgCombo.selectedItem = current
        } finally {
            updatingCombo = false
        }
    }

    /** Run button doubles as Cancel while a query is in flight (family lesson: always cancellable). */
    private fun toggleRun() {
        if (inFlight) {
            cancelRequested = true
            runningIndicator?.cancel()
            return
        }
        val org = OrgService.get(project).requireCurrent() ?: return
        val raw = queryField.text.trim()
        if (raw.isBlank()) return
        val query = applyAutoLimit(raw, autoLimit.isSelected)
        inFlight = true
        cancelRequested = false
        runButton.text = "Cancel"
        statusLabel.text = "Running against $org…"

        object : Task.Backgroundable(project, "SOQL query", true) {
            override fun run(indicator: ProgressIndicator) {
                runningIndicator = indicator
                if (cancelRequested) indicator.cancel() // cancel clicked before the task got a thread
                val res = SfCli.get(project).execute(
                    listOf("data", "query", "--query", query, "-o", org),
                    indicator,
                    timeoutMs = 300_000,
                )
                val app = ApplicationManager.getApplication()
                when {
                    res.cancelled -> app.invokeLater { statusLabel.text = "Cancelled" }
                    !res.ok -> app.invokeLater {
                        statusLabel.text = "Error: ${res.errorMessage()}".take(300)
                    }
                    else -> {
                        val resultObj = res.resultObj()
                        val records = resultObj?.get("records")?.takeIf { it.isJsonArray }?.asJsonArray
                            ?.mapNotNull { el -> el.takeIf { it.isJsonObject }?.asJsonObject }
                            .orEmpty()
                        val total = resultObj?.get("totalSize")
                            ?.takeIf { it.isJsonPrimitive }?.asInt ?: records.size
                        val rows = records.take(MAX_ROWS).map { flattenRecord(it) }
                        val cols = columnsOf(rows)
                        val data = rows
                            .map { row -> cols.map { c -> (row[c] ?: "") as Any }.toTypedArray() }
                            .toTypedArray()
                        app.invokeLater {
                            tableModel.setDataVector(data, cols.map { it as Any }.toTypedArray())
                            autoSizeColumns(cols, rows)
                            statusLabel.text = "$total row(s) — $org" +
                                if (records.size > MAX_ROWS) " (showing first $MAX_ROWS)" else ""
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

    private fun autoSizeColumns(cols: List<String>, rows: List<Map<String, String>>) {
        val metrics = table.getFontMetrics(table.font)
        for ((i, col) in cols.withIndex()) {
            var width = metrics.stringWidth(col) + 24
            for (row in rows.take(100)) {
                width = maxOf(width, metrics.stringWidth(row[col] ?: "") + 16)
            }
            table.columnModel.getColumn(i).preferredWidth = width.coerceIn(80, 360)
        }
    }

    companion object {
        const val MAX_ROWS = 10_000
    }
}
