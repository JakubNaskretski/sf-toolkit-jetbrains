package dev.skrety.sftoolkit.apex

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.skrety.sftoolkit.OrgService
import dev.skrety.sftoolkit.SfCli
import dev.skrety.sftoolkit.soql.looksLikeRecordId
import dev.skrety.sftoolkit.ui.OrgCombo
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

class ApexLogsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ApexLogsPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/** Lists the org's Apex debug logs; opening one loads it into a read-only editor tab. */
class ApexLogsPanel(private val project: Project) : Disposable {

    private val orgCombo = OrgCombo(project, syncToProject = false)
    private val refreshButton = JButton("Refresh", AllIcons.Actions.Refresh)
    private val openButton = JButton("Open Log", AllIcons.Actions.MenuOpen).apply { isEnabled = false }
    private val statusLabel = JBLabel(" ").apply {
        setCopyable(true)
        border = JBUI.Borders.empty(3, 8)
    }
    private val tableModel = object : DefaultTableModel(
        arrayOf<Any>("Time", "Operation", "Status", "Size", "ms", "User"), 0,
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val table = JBTable(tableModel).apply {
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        autoCreateRowSorter = true
        emptyText.text = "Click Refresh to list the org's Apex debug logs"
    }

    private var entries: List<ApexLogEntry> = emptyList()

    @Volatile
    private var busy = false

    val component: JComponent = build()

    private fun build(): JComponent {
        val toolbar = JPanel(com.intellij.util.ui.WrapLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(refreshButton)
            add(openButton)
        }
        orgCombo.addTo(toolbar)
        refreshButton.addActionListener { refresh() }
        openButton.addActionListener { openSelected() }
        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) openButton.isEnabled = !busy && table.selectedRow >= 0
        }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openSelected()
            }
        })
        return JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }
    }

    override fun dispose() {
        orgCombo.dispose()
    }

    private fun currentOrg(): String? =
        orgCombo.selectedOrg ?: OrgService.get(project).requireCurrent()

    private fun refresh() {
        if (busy) return
        val org = currentOrg() ?: return
        busy = true
        refreshButton.isEnabled = false
        statusLabel.text = "Listing logs from $org…"
        object : Task.Backgroundable(project, "Listing Apex logs", true) {
            override fun run(indicator: ProgressIndicator) {
                val res = SfCli.get(project).execute(listOf("apex", "list", "log", "-o", org), indicator)
                val parsed = if (res.ok) parseLogList(res.json?.get("result")) else emptyList()
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    entries = parsed
                    tableModel.setRowCount(0)
                    parsed.forEach {
                        tableModel.addRow(
                            arrayOf<Any>(
                                it.startTime.replace("T", " ").removeSuffix("+0000"),
                                it.operation, it.status, "${it.lengthBytes / 1024} KB",
                                it.durationMs.toString(), it.user ?: "",
                            ),
                        )
                    }
                    statusLabel.text =
                        if (res.ok) "${parsed.size} log(s) — $org"
                        else "Error: ${res.errorMessage()}".take(200)
                }
            }

            override fun onFinished() {
                busy = false
                refreshButton.isEnabled = true
                openButton.isEnabled = table.selectedRow >= 0
            }
        }.queue()
    }

    private fun openSelected() {
        if (busy) return
        val viewRow = table.selectedRow.takeIf { it >= 0 } ?: return
        val entry = entries.getOrNull(table.convertRowIndexToModel(viewRow)) ?: return
        if (!looksLikeRecordId(entry.id)) return
        val org = currentOrg() ?: return
        busy = true
        openButton.isEnabled = false
        object : Task.Backgroundable(project, "Fetching Apex log…", true) {
            override fun run(indicator: ProgressIndicator) {
                val res = SfCli.get(project).execute(
                    listOf("apex", "get", "log", "-i", entry.id, "-o", org),
                    indicator,
                )
                val body = if (res.ok) parseLogBody(res.json?.get("result")) else null
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    if (body == null) {
                        statusLabel.text = "Error: ${res.errorMessage()}".take(200)
                    } else {
                        // In-memory only — never written into the project tree.
                        val file = LightVirtualFile("ApexLog-${entry.id}.log", body)
                        file.isWritable = false
                        FileEditorManager.getInstance(project).openFile(file, true)
                        statusLabel.text = "Opened ${entry.id} (${body.length / 1024} KB)"
                    }
                }
            }

            override fun onFinished() {
                busy = false
                openButton.isEnabled = table.selectedRow >= 0
            }
        }.queue()
    }
}
