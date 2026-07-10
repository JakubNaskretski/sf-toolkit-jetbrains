package dev.skrety.sftoolkit.metadata

import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import dev.skrety.sftoolkit.OrgService
import dev.skrety.sftoolkit.SfCli
import dev.skrety.sftoolkit.SfLog
import dev.skrety.sftoolkit.compareFileWithOrgInBackground
import dev.skrety.sftoolkit.notifySourceOutcome
import dev.skrety.sftoolkit.refreshOrgsInBackground
import dev.skrety.sftoolkit.sourceFailureLines
import dev.skrety.sftoolkit.sourceFiles
import dev.skrety.sftoolkit.str
import dev.skrety.sftoolkit.toolingRefForTypeName
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.RowFilter
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

class MetadataToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = MetadataBrowserPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * IC-style metadata browser: lists org + local components side by side, searchable and
 * filterable; selected rows can be retrieved (by Type:Name — works for components that
 * don't exist locally yet), deployed, or compared.
 */
class MetadataBrowserPanel(private val project: Project) : Disposable {

    private val listButton = JButton("List Metadata", AllIcons.Actions.Refresh)
    private val orgLabel = JBLabel()
    private val search = SearchTextField()
    private val typeFilter = ComboBox(DefaultComboBoxModel(arrayOf(ALL) + META_TYPES.map { it.type }))
    private val locationFilter = ComboBox(DefaultComboBoxModel(arrayOf(ALL, "Local only", "Org only", "Both")))
    private val retrieveButton = JButton("Retrieve").apply { isEnabled = false }
    private val deployButton = JButton("Deploy").apply { isEnabled = false }
    private val compareButton = JButton("Compare").apply { isEnabled = false }
    private val statusLabel = JBLabel(" ").apply { setCopyable(true) }

    private val tableModel = object : DefaultTableModel(arrayOf<Any>("Type", "Name", "Location"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val sorter = TableRowSorter(tableModel)
    private val table = JBTable(tableModel).apply {
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        rowSorter = sorter
        emptyText.text = "Click \"List Metadata\" to load org + local components"
    }

    private var rows: List<MetaRow> = emptyList()

    @Volatile
    private var listing = false

    // Set on the EDT before queue() — same double-submit guard as the SOQL panel.
    @Volatile
    private var actionInFlight = false

    private val orgListener = Runnable { updateOrgLabel() }

    val component: JComponent = build()

    private fun build(): JComponent {
        val filters = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(listButton)
            add(orgLabel)
            add(search.apply { textEditor.columns = 18; textEditor.emptyText.text = "Filter by name…" })
            add(JBLabel("Type:"))
            add(typeFilter)
            add(JBLabel("Show:"))
            add(locationFilter)
        }
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(retrieveButton)
            add(deployButton)
            add(compareButton)
        }
        val south = JPanel(BorderLayout()).apply {
            add(actions, BorderLayout.WEST)
            add(statusLabel, BorderLayout.CENTER)
        }

        listButton.addActionListener { listMetadata() }
        retrieveButton.addActionListener { runOnSelection("Retrieving from org…", "Retrieved", retrieve = true) }
        deployButton.addActionListener { runOnSelection("Deploying to org…", "Deployed", retrieve = false) }
        compareButton.addActionListener { compareSelection() }
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) updateButtons() }
        search.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })
        typeFilter.addActionListener { applyFilter() }
        locationFilter.addActionListener { applyFilter() }

        OrgService.get(project).addChangeListener(orgListener)
        updateOrgLabel()
        if (OrgService.get(project).orgs.isEmpty()) refreshOrgsInBackground(project, quiet = true)

        return JPanel(BorderLayout()).apply {
            add(filters, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(south, BorderLayout.SOUTH)
        }
    }

    override fun dispose() {
        OrgService.get(project).removeChangeListener(orgListener)
    }

    private fun updateOrgLabel() {
        orgLabel.text = "Org: " + (OrgService.get(project).current ?: "none selected")
    }

    private fun sfdxRoot(): Path? {
        // ponytail: IDE project root must be the DX root; nested DX projects when needed
        val base = project.basePath ?: return null
        val root = Path.of(base)
        return if (Files.isRegularFile(root.resolve("sfdx-project.json"))) root else null
    }

    private fun packageDirs(root: Path): List<Path> = try {
        val json = JsonParser.parseString(Files.readString(root.resolve("sfdx-project.json"))).asJsonObject
        json.getAsJsonArray("packageDirectories")
            ?.mapNotNull { it.takeIf { el -> el.isJsonObject }?.asJsonObject?.str("path") }
            ?.map { root.resolve(it).normalize() }
            ?: listOf(root.resolve("force-app"))
    } catch (_: Exception) {
        listOf(root.resolve("force-app"))
    }

    private fun listMetadata() {
        if (listing) return
        val org = OrgService.get(project).requireCurrent() ?: return
        val root = sfdxRoot()
        if (root == null) {
            NotificationGroupManager.getInstance().getNotificationGroup("SF Toolkit")
                .createNotification(
                    "Not an SFDX project",
                    "sfdx-project.json not found at the project root — the metadata browser needs one.",
                    NotificationType.WARNING,
                ).notify(project)
            return
        }
        listing = true
        updateButtons()
        statusLabel.text = "Listing metadata from $org…"

        object : Task.Backgroundable(project, "Listing Salesforce metadata", true) {
            override fun run(indicator: ProgressIndicator) {
                val local = scanLocalComponents(packageDirs(root))
                val org2names = LinkedHashMap<String, List<String>>()
                var failedTypes = 0
                for ((i, rule) in META_TYPES.withIndex()) {
                    indicator.checkCanceled()
                    indicator.text = "Listing ${rule.type}…"
                    indicator.fraction = i.toDouble() / META_TYPES.size
                    val res = SfCli.get(project).execute(
                        listOf("org", "list", "metadata", "--metadata-type", rule.type, "-o", org),
                        indicator,
                        workDir = root.toString(),
                    )
                    if (res.cancelled) return
                    if (res.ok) {
                        org2names[rule.type] = orgListNames(res.json?.get("result"))
                    } else {
                        failedTypes++
                        SfLog.get(project).warn("list metadata ${rule.type} failed: ${res.errorMessage()}")
                    }
                }
                val merged = mergeRows(local, org2names)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    rows = merged
                    tableModel.setRowCount(0)
                    merged.forEach { tableModel.addRow(arrayOf<Any>(it.type, it.name, it.location)) }
                    applyFilter()
                    statusLabel.text = "${merged.size} components — $org" +
                        if (failedTypes > 0) " ($failedTypes type(s) failed to list, see SF Log)" else ""
                }
            }

            override fun onFinished() {
                listing = false
                updateButtons()
            }
        }.queue()
    }

    private fun applyFilter() {
        val text = search.text.trim().lowercase()
        val type = typeFilter.selectedItem as? String ?: ALL
        val location = locationFilter.selectedItem as? String ?: ALL
        sorter.rowFilter = object : RowFilter<DefaultTableModel, Int>() {
            override fun include(entry: Entry<out DefaultTableModel, out Int>): Boolean {
                val row = rows.getOrNull(entry.identifier) ?: return false
                if (type != ALL && row.type != type) return false
                if (location != ALL && row.location != location) return false
                return text.isEmpty() || row.name.lowercase().contains(text)
            }
        }
        updateButtons()
    }

    private fun selectedRows(): List<MetaRow> =
        table.selectedRows.map { table.convertRowIndexToModel(it) }.mapNotNull { rows.getOrNull(it) }

    private fun updateButtons() {
        val busy = listing || actionInFlight
        val sel = selectedRows()
        listButton.isEnabled = !busy
        retrieveButton.isEnabled = !busy && sel.any { it.org }
        deployButton.isEnabled = !busy && sel.any { it.local }
        compareButton.isEnabled = !busy && sel.size == 1 && sel[0].local && sel[0].org &&
            toolingRefForTypeName(sel[0].type, sel[0].name) != null
    }

    private fun runOnSelection(progressTitle: String, pastVerb: String, retrieve: Boolean) {
        if (actionInFlight || listing) return
        val org = OrgService.get(project).requireCurrent() ?: return
        val root = sfdxRoot() ?: return
        val targets = selectedRows().filter { if (retrieve) it.org else it.local }
        if (targets.isEmpty()) return
        val base = if (retrieve) listOf("project", "retrieve", "start") else listOf("project", "deploy", "start")
        // Chunked: hundreds of --metadata pairs would blow the ~32k Windows command-line limit.
        val chunks = targets.chunked(COMPONENTS_PER_CALL)
        actionInFlight = true
        updateButtons()

        object : Task.Backgroundable(project, progressTitle, true) {
            override fun run(indicator: ProgressIndicator) {
                var fileCount = 0
                val failureLines = ArrayList<String>()
                var hardError: String? = null
                for ((i, chunk) in chunks.withIndex()) {
                    indicator.checkCanceled()
                    if (chunks.size > 1) indicator.text = "Batch ${i + 1} of ${chunks.size}…"
                    indicator.fraction = i.toDouble() / chunks.size
                    val metadataArgs = chunk.flatMap { listOf("--metadata", "${it.type}:${it.name}") }
                    val res = SfCli.get(project).execute(
                        base + listOf("-o", org) + metadataArgs,
                        indicator,
                        timeoutMs = 600_000,
                        workDir = root.toString(),
                    )
                    if (res.cancelled) return
                    val files = sourceFiles(res.resultObj())
                    fileCount += files.size
                    val chunkFailures = sourceFailureLines(files)
                    failureLines += chunkFailures
                    if (!res.ok && chunkFailures.isEmpty()) {
                        // Hard CLI error with no per-component detail — stop the batch loop.
                        hardError = res.errorMessage()
                        break
                    }
                }
                notifySourceOutcome(
                    project, org, pastVerb, fileCount,
                    failureLines, hardError,
                )
                if (retrieve && fileCount > 0) {
                    ApplicationManager.getApplication().invokeLater {
                        LocalFileSystem.getInstance().findFileByNioFile(root)?.let {
                            VfsUtil.markDirtyAndRefresh(true, true, true, it)
                        }
                    }
                }
            }

            override fun onFinished() {
                actionInFlight = false
                updateButtons()
            }
        }.queue()
    }

    private fun compareSelection() {
        if (actionInFlight || listing) return
        val row = selectedRows().singleOrNull() ?: return
        val org = OrgService.get(project).requireCurrent() ?: return
        val root = sfdxRoot() ?: return
        val ref = toolingRefForTypeName(row.type, row.name) ?: return
        val file = row.localPath?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: return
        actionInFlight = true
        updateButtons()
        compareFileWithOrgInBackground(project, file, ref, org, root.toString()) {
            actionInFlight = false
            updateButtons()
        }
    }

    companion object {
        private const val ALL = "All"

        // ~45 chars per --metadata pair × 100 stays far under the ~32k Windows limit.
        private const val COMPONENTS_PER_CALL = 100
    }
}
