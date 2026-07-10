package dev.skrety.sftoolkit.soql

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.EditorTextField
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import dev.skrety.sftoolkit.OrgService
import dev.skrety.sftoolkit.SfCli
import dev.skrety.sftoolkit.filetypes.SoqlFileType
import dev.skrety.sftoolkit.schema.OrgSchemaService
import dev.skrety.sftoolkit.ui.OrgCombo
import dev.skrety.sftoolkit.ui.setupTabbedToolWindow
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

class SoqlToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        setupTabbedToolWindow(project, toolWindow, "Query") {
            val panel = SoqlPanel(project)
            panel.component to panel
        }
    }
}

class SoqlPanel(private val project: Project) : Disposable {

    private val queryField = EditorTextField(
        EditorFactory.getInstance().createDocument("SELECT Id, Name FROM Account"),
        project,
        SoqlFileType,
        false,
        false,
    )
    // Local selection: each query tab targets its own org (IC2-style); the status-bar
    // widget stays the project-wide switcher for retrieve/deploy.
    private val orgCombo = OrgCombo(project, syncToProject = false)
    private val autoLimit = JBCheckBox("Auto LIMIT 200", true).apply {
        toolTipText = "Append LIMIT 200 when the query has no top-level LIMIT"
    }
    private val runButton = JButton("Run", com.intellij.icons.AllIcons.Actions.Execute).apply {
        toolTipText = "Run query (Ctrl/Cmd+Enter). Click again to cancel."
    }
    private val historyButton = JButton(com.intellij.icons.AllIcons.Vcs.History).apply {
        toolTipText = "Query history (last 25 runs)"
        isFocusable = false
    }
    private val exportButton = JButton("Export CSV").apply {
        toolTipText = "Save the fetched rows as CSV (formula-injection-safe quoting)"
        isEnabled = false
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

    // Last successful result, retained for CSV export and open-record.
    private var lastCols: List<String> = emptyList()
    private var lastRows: List<Map<String, String>> = emptyList()

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
        val toolbar = JPanel(com.intellij.util.ui.WrapLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(runButton)
        }
        orgCombo.addTo(toolbar)
        toolbar.add(historyButton)
        toolbar.add(autoLimit)
        toolbar.add(exportButton)
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
        exportButton.addActionListener { exportCsv() }
        historyButton.addActionListener { showHistory() }
        // IC-style: the schema cache builds itself — no button to know about.
        autoSyncSchemaIfNeeded()
        table.componentPopupMenu = javax.swing.JPopupMenu().apply {
            add(javax.swing.JMenuItem("Open Record in Org").apply {
                addActionListener { openSelectedRecord() }
            })
        }
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                if (!inFlight) toggleRun()
            }
        }.registerCustomShortcutSet(CustomShortcutSet.fromString("ctrl ENTER", "meta ENTER"), queryField)
        // Native lookup with auto-popup while typing (Ctrl+Space also works via the
        // standard Code Completion action).
        TextFieldWithAutoCompletion.installCompletion(
            queryField.document,
            project,
            SoqlCompletionProvider(
                project,
                { orgCombo.selectedOrg ?: OrgService.get(project).current },
                { msg ->
                    ApplicationManager.getApplication().invokeLater {
                        statusLabel.text = msg
                        autoSyncSchemaIfNeeded()
                    }
                },
            ),
            true,
        )

        statusLabel.border = com.intellij.util.ui.JBUI.Borders.empty(3, 8)
        return OnePixelSplitter(true, "sfToolkit.soql.splitter", 0.3f).apply {
            firstComponent = top
            secondComponent = bottom
        }
    }

    override fun dispose() {
        orgCombo.dispose()
    }

    /** Quiet one-shot per org: completion works without the user knowing about caches. */
    private fun autoSyncSchemaIfNeeded() {
        val org = orgCombo.selectedOrg ?: OrgService.get(project).current ?: return
        val schema = OrgSchemaService.get(project)
        if (schema.objectNames(org) != null || !syncingOrgs.add(org)) return
        object : Task.Backgroundable(project, "Caching org schema for completion", true) {
            private var count: Int? = null

            override fun run(indicator: ProgressIndicator) {
                count = schema.syncObjects(org, indicator)
            }

            override fun onFinished() {
                syncingOrgs.remove(org)
                count?.let { statusLabel.text = "Schema cached: $it objects — completion ready" }
            }
        }.queue()
    }


    /** Run button doubles as Cancel while a query is in flight (family lesson: always cancellable). */
    private fun toggleRun() {
        if (inFlight) {
            cancelRequested = true
            runningIndicator?.cancel()
            return
        }
        val org = orgCombo.selectedOrg ?: OrgService.get(project).requireCurrent() ?: return
        val raw = queryField.text.trim()
        if (raw.isBlank()) return
        val query = applyAutoLimit(raw, autoLimit.isSelected)
        inFlight = true
        cancelRequested = false
        runButton.text = "Cancel"
        runButton.icon = com.intellij.icons.AllIcons.Actions.Suspend
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
                            lastCols = cols
                            lastRows = rows
                            exportButton.isEnabled = rows.isNotEmpty()
                            SoqlHistory.get(project).add(raw)
                            statusLabel.text = "$total row(s) — $org" +
                                if (records.size > MAX_ROWS) " (showing first $MAX_ROWS)" else ""
                        }
                        // Cache the FROM object's describe so field completion works next
                        // time. Own pooled thread — must not extend the query's in-flight
                        // state past the visible results (review finding).
                        soqlContextAt(query, query.length).objectName?.let { obj ->
                            ApplicationManager.getApplication().executeOnPooledThread {
                                OrgSchemaService.get(project).ensureDescribe(org, obj, null)
                            }
                        }
                    }
                }
            }

            override fun onFinished() {
                runningIndicator = null
                inFlight = false
                runButton.text = "Run"
                runButton.icon = com.intellij.icons.AllIcons.Actions.Execute
            }
        }.queue()
    }

    private fun showHistory() {
        val entries = SoqlHistory.get(project).entries()
        if (entries.isEmpty()) {
            statusLabel.text = "No query history yet"
            return
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(entries)
            .setRenderer { _, value, _, _, _ ->
                javax.swing.JLabel(value.replace(Regex("\\s+"), " ").take(90))
            }
            .setItemChosenCallback { chosen -> queryField.text = chosen }
            .createPopup()
            .showUnderneathOf(historyButton)
    }

    private fun exportCsv() {
        if (lastRows.isEmpty()) return
        val descriptor = com.intellij.openapi.fileChooser.FileSaverDescriptor("Export Query Results", "", "csv")
        val wrapper = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(null as com.intellij.openapi.vfs.VirtualFile?, "query-results.csv") ?: return
        val csv = toCsv(lastCols, lastRows)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                wrapper.file.writeText(csv)
                ApplicationManager.getApplication().invokeLater {
                    statusLabel.text = "Exported ${lastRows.size} row(s) to ${wrapper.file.name}"
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    statusLabel.text = "Export failed: ${e.message}"
                }
            }
        }
    }

    /** Opens the selected row's record via `sf org open` (frontdoor URL, family pattern). */
    private fun openSelectedRecord() {
        val viewRow = table.selectedRow.takeIf { it >= 0 } ?: return
        val modelRow = table.convertRowIndexToModel(viewRow)
        val row = lastRows.getOrNull(modelRow) ?: return
        // Only the exact Id column — any Id-shaped fallback happily opens OwnerId/
        // CreatedById instead of the record (review finding).
        val id = row["Id"]?.takeIf { looksLikeRecordId(it) }
        if (id == null) {
            statusLabel.text = "Include Id in the SELECT to open records from results"
            return
        }
        val org = orgCombo.selectedOrg ?: OrgService.get(project).requireCurrent() ?: return
        object : Task.Backgroundable(project, "Opening record in $org…", true) {
            override fun run(indicator: ProgressIndicator) {
                SfCli.get(project).execute(
                    listOf("org", "open", "--path", "/$id", "-o", org),
                    indicator,
                )
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

        // single-flight guard for the quiet auto-sync, shared across tabs
        private val syncingOrgs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    }
}
