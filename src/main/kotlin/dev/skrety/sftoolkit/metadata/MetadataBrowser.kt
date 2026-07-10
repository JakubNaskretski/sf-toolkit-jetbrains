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
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import dev.skrety.sftoolkit.OrgService
import dev.skrety.sftoolkit.SfCli
import dev.skrety.sftoolkit.SfLog
import dev.skrety.sftoolkit.compareFileWithOrgInBackground
import dev.skrety.sftoolkit.notifySourceOutcome
import dev.skrety.sftoolkit.sourceFailureLines
import dev.skrety.sftoolkit.sourceFiles
import dev.skrety.sftoolkit.str
import dev.skrety.sftoolkit.toolingRefForTypeName
import dev.skrety.sftoolkit.ui.OrgCombo
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultTreeModel

class MetadataToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = MetadataBrowserPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * IC2-style metadata browser: a checkbox tree grouped by metadata type. Check
 * components (or whole types) → Retrieve / Deploy; single check → Compare.
 */
class MetadataBrowserPanel(private val project: Project) : Disposable {

    private val orgCombo = OrgCombo(project) // browser = project-wide org, like retrieve/deploy
    private val listButton = JButton("List Metadata", AllIcons.Actions.Refresh)
    private val typesButton = JButton("Types…").apply {
        toolTipText = "Choose which metadata types get listed"
        isFocusable = false
    }
    private val search = SearchTextField()
    private val locationFilter = ComboBox(DefaultComboBoxModel(arrayOf(ALL, "Local only", "Org only", "Both")))
    private val retrieveButton = JButton("Retrieve", AllIcons.Actions.Download).apply { isEnabled = false }
    private val deployButton = JButton("Deploy", AllIcons.Actions.Upload).apply { isEnabled = false }
    private val compareButton = JButton("Compare", AllIcons.Actions.Diff).apply { isEnabled = false }
    private val statusLabel = JBLabel(" ").apply {
        setCopyable(true)
        border = JBUI.Borders.empty(3, 8)
    }

    private val rootNode = CheckedTreeNode(null)
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = object : CheckboxTree(Renderer(), rootNode) {
        init {
            model = treeModel
            isRootVisible = false
            showsRootHandles = true
            emptyText.text = "Click \"List Metadata\" to load org + local components"
        }

        override fun onNodeStateChanged(node: CheckedTreeNode) {
            updateButtons()
        }
    }

    private var rows: List<MetaRow> = emptyList()

    @Volatile
    private var listing = false

    @Volatile
    private var actionInFlight = false

    val component: JComponent = build()

    private class Renderer : CheckboxTree.CheckboxTreeCellRenderer(true) {
        override fun customizeRenderer(
            tree: JTree?, value: Any?, selected: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            val node = value as? CheckedTreeNode ?: return
            when (val obj = node.userObject) {
                is MetaRow -> {
                    textRenderer.icon = AllIcons.FileTypes.Any_type
                    textRenderer.append(decodeMetaName(obj.name))
                    textRenderer.append("  ${obj.location}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    // small screens: full text on hover instead of clipping
                    textRenderer.toolTipText = "${decodeMetaName(obj.name)} — ${obj.location}"
                }
                is TypeGroup -> {
                    textRenderer.icon = AllIcons.Nodes.Folder
                    textRenderer.append(obj.type, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    textRenderer.append("  ${obj.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
    }

    private data class TypeGroup(val type: String, val count: Int) {
        override fun toString(): String = type
    }

    private fun build(): JComponent {
        val toolbar = JPanel(com.intellij.util.ui.WrapLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(listButton)
            add(typesButton)
        }
        orgCombo.addTo(toolbar)
        val filterBar = JPanel(com.intellij.util.ui.WrapLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(search.apply { textEditor.columns = 16; textEditor.emptyText.text = "Filter by name…" })
            add(JBLabel("Show:"))
            add(locationFilter)
        }
        val north = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(filterBar, BorderLayout.SOUTH)
        }
        val actions = JPanel(com.intellij.util.ui.WrapLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(retrieveButton)
            add(deployButton)
            add(compareButton)
        }
        val south = JPanel(BorderLayout()).apply {
            add(actions, BorderLayout.WEST)
            add(statusLabel, BorderLayout.CENTER)
        }

        listButton.addActionListener { listMetadata() }
        typesButton.addActionListener { chooseTypes() }
        loadCachedRows()
        retrieveButton.addActionListener { runOnChecked("Retrieving from org…", "Retrieved", retrieve = true) }
        deployButton.addActionListener { runOnChecked("Deploying to org…", "Deployed", retrieve = false) }
        compareButton.addActionListener { compareChecked() }
        search.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = rebuildTree()
            override fun removeUpdate(e: DocumentEvent) = rebuildTree()
            override fun changedUpdate(e: DocumentEvent) = rebuildTree()
        })
        locationFilter.addActionListener { rebuildTree() }

        return JPanel(BorderLayout()).apply {
            add(north, BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
            add(south, BorderLayout.SOUTH)
        }
    }

    override fun dispose() {
        orgCombo.dispose()
    }

    /** Selected types persist per project (workspace-level, not in VCS). */
    private fun selectedTypes(): Set<String> {
        val csv = com.intellij.ide.util.PropertiesComponent.getInstance(project)
            .getValue(TYPES_KEY) ?: return META_TYPES.map { it.type }.toSet()
        return csv.split(',').filter { it.isNotBlank() }.toSet()
    }

    private fun chooseTypes() {
        val current = selectedTypes()
        val boxes = META_TYPES.map { com.intellij.ui.components.JBCheckBox(it.type, it.type in current) }
        val dialog = object : com.intellij.openapi.ui.DialogWrapper(project) {
            init {
                title = "Metadata Types to List"
                init()
            }

            override fun createCenterPanel(): JComponent =
                JPanel(java.awt.GridLayout(0, 2, 12, 2)).apply { boxes.forEach { add(it) } }
        }
        if (dialog.showAndGet()) {
            val chosen = boxes.filter { it.isSelected }.map { it.text }
            com.intellij.ide.util.PropertiesComponent.getInstance(project)
                .setValue(TYPES_KEY, chosen.joinToString(","))
            statusLabel.text = "${chosen.size} type(s) selected — click List Metadata to refresh"
            statusLabel.toolTipText = statusLabel.text
        }
    }

    /** Restores the last listing from the per-org disk cache (field request: no re-list on restart). */
    private fun loadCachedRows() {
        val org = OrgService.get(project).current ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val cached = dev.skrety.sftoolkit.schema.OrgSchemaService.get(project)
                .store(org).readMetadataRows() ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || rows.isNotEmpty()) return@invokeLater
                rows = cached
                rebuildTree()
                statusLabel.text = "${cached.size} components — $org (cached; List Metadata refreshes)"
                statusLabel.toolTipText = statusLabel.text
            }
        }
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
        if (listing || actionInFlight) return
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
        listButton.isEnabled = false
        statusLabel.text = "Listing metadata from $org…"

        object : Task.Backgroundable(project, "Listing Salesforce metadata", true) {
            override fun run(indicator: ProgressIndicator) {
                val local = scanLocalComponents(packageDirs(root))
                val org2names = LinkedHashMap<String, List<String>>()
                var failedTypes = 0
                val types = selectedTypes()
                val rules = META_TYPES.filter { it.type in types }
                for ((i, rule) in rules.withIndex()) {
                    indicator.checkCanceled()
                    indicator.text = "Listing ${rule.type}…"
                    indicator.fraction = i.toDouble() / rules.size
                    val res = SfCli.get(project).execute(
                        listOf("org", "list", "metadata", "--metadata-type", rule.type, "-o", org),
                        indicator,
                        workDir = root.toString(),
                    )
                    if (res.cancelled) return
                    if (res.ok) org2names[rule.type] = orgListNames(res.json?.get("result"))
                    else {
                        failedTypes++
                        SfLog.get(project).warn("list metadata ${rule.type} failed: ${res.errorMessage()}")
                    }
                }
                val localFiltered = local.filterKeys { it in types }
                val merged = mergeRows(localFiltered, org2names)
                dev.skrety.sftoolkit.schema.OrgSchemaService.get(project)
                    .store(org).writeMetadataRows(merged)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    rows = merged
                    rebuildTree()
                    statusLabel.text = "${merged.size} components — $org" +
                        if (failedTypes > 0) " ($failedTypes type(s) failed to list, see SF Log)" else ""
                    statusLabel.toolTipText = statusLabel.text
                }
            }

            override fun onFinished() {
                listing = false
                listButton.isEnabled = true
                updateButtons()
            }
        }.queue()
    }

    /** Rebuilds the grouped tree from [rows] + the active filters. */
    private fun rebuildTree() {
        val text = search.text.trim().lowercase()
        val location = locationFilter.selectedItem as? String ?: ALL
        val checkedKeys = checkedRows().map { it.key }.toSet()

        val filtered = rows.filter { row ->
            (location == ALL || row.location == location) &&
                (text.isEmpty() || decodeMetaName(row.name).lowercase().contains(text) ||
                    row.name.lowercase().contains(text))
        }
        rootNode.removeAllChildren()
        for ((type, typeRows) in filtered.groupBy { it.type }) {
            val typeNode = CheckedTreeNode(TypeGroup(type, typeRows.size)).apply { isChecked = false }
            for (row in typeRows) {
                typeNode.add(CheckedTreeNode(row).apply { isChecked = row.key in checkedKeys })
            }
            rootNode.add(typeNode)
        }
        treeModel.reload()
        // Invisible root collapses on reload — without this the tree renders EMPTY
        // (field bug: "14050 components but I can't see them").
        if (text.isNotEmpty()) TreeUtil.expandAll(tree) else TreeUtil.expand(tree, 1)
        updateButtons()
    }

    private fun checkedRows(): List<MetaRow> =
        tree.getCheckedNodes(MetaRow::class.java, null).toList()

    private fun updateButtons() {
        val busy = listing || actionInFlight
        val checked = checkedRows()
        retrieveButton.isEnabled = !busy && checked.any { it.org }
        deployButton.isEnabled = !busy && checked.any { it.local }
        compareButton.isEnabled = !busy && checked.size == 1 && checked[0].local && checked[0].org &&
            toolingRefForTypeName(checked[0].type, checked[0].name) != null
        if (checked.isNotEmpty() && !busy) {
            statusLabel.text = "${checked.size} component(s) checked"
        }
    }

    private fun runOnChecked(progressTitle: String, pastVerb: String, retrieve: Boolean) {
        if (actionInFlight || listing) return
        val org = OrgService.get(project).requireCurrent() ?: return
        val root = sfdxRoot() ?: return
        val targets = checkedRows().filter { if (retrieve) it.org else it.local }
        if (targets.isEmpty()) return
        val base = if (retrieve) listOf("project", "retrieve", "start") else listOf("project", "deploy", "start")
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
                        hardError = res.errorMessage()
                        break
                    }
                }
                notifySourceOutcome(project, org, pastVerb, fileCount, failureLines, hardError)
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

    private fun compareChecked() {
        if (actionInFlight || listing) return
        val row = checkedRows().singleOrNull() ?: return
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
        private const val TYPES_KEY = "sfToolkit.metadata.types"

        // ~45 chars per --metadata pair × 100 stays far under the ~32k Windows limit.
        private const val COMPONENTS_PER_CALL = 100
    }
}
