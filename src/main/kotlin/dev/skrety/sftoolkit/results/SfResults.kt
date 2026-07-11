package dev.skrety.sftoolkit.results

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Publish bus for the "SF Results" window. One reusable content showing the LATEST run
 * (a deploy result is "the latest run", not a scratch tab). Auto-shows on failure ONLY —
 * success keeps the balloon and never steals focus.
 */
@Service(Service.Level.PROJECT)
class SfResultsService(private val project: Project) {
    @Volatile
    var latest: RunResult? = null
        private set

    @Volatile
    var panel: SfResultsPanel? = null // set by the factory when the window first opens

    /** Any thread. */
    fun publish(run: RunResult) {
        latest = run
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
                ?: return@invokeLater
            tw.setAvailable(true)
            if (run.hasFailures) {
                // activate() initializes content (factory draws `latest`) then runs the callback
                tw.activate { panel?.showRun(run) }
            } else {
                panel?.showRun(run) // refresh only if already open; never pop for success
            }
        }
    }

    companion object {
        const val TOOL_WINDOW_ID = "SF Results"

        fun get(project: Project): SfResultsService = project.service()
    }
}

class SfResultsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SfResultsPanel(project)
        val service = SfResultsService.get(project)
        service.panel = panel
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        content.isCloseable = false
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        service.latest?.let { panel.showRun(it) } // draw a run published before first open
    }
}

/** Per-type grouped results tree; double-click / Enter on a failure opens file:line:col. */
class SfResultsPanel(private val project: Project) : Disposable {

    private data class TypeGroup(val type: String, val total: Int, val failed: Int)

    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        emptyText.text = "Deploy / validate / retrieve results appear here"
        cellRenderer = ResultRenderer()
    }
    private val summary = JBLabel(" ").apply {
        setCopyable(true)
        border = JBUI.Borders.empty(3, 8)
    }
    private var current: RunResult? = null

    val component: JComponent = build()

    private fun build(): JComponent {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) navigateSelected()
            }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) navigateSelected()
            }
        })
        return JPanel(BorderLayout()).apply {
            add(summary, BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
        }
    }

    /** EDT only — rebuilds tree + summary in place. */
    fun showRun(run: RunResult) {
        current = run
        summary.text = run.summaryLine()
        summary.toolTipText = run.summaryLine()
        summary.icon = when {
            run.hasFailures -> AllIcons.General.BalloonError
            run.files.isEmpty() -> AllIcons.General.Warning
            else -> AllIcons.General.InspectionsOK
        }
        root.removeAllChildren()
        run.files.groupBy { it.type }.toSortedMap().forEach { (type, rows) ->
            val group = DefaultMutableTreeNode(TypeGroup(type, rows.size, rows.count { it.failed }))
            rows.sortedBy { it.fullName }.forEach { group.add(DefaultMutableTreeNode(it)) }
            root.add(group)
        }
        model.reload()
        if (run.files.size <= EXPAND_LIMIT) TreeUtil.expandAll(tree) else TreeUtil.expand(tree, 1)
    }

    private fun navigateSelected() {
        val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return
        val result = node.userObject as? SourceFileResult ?: return
        val path = result.filePath ?: return
        val abs = if (File(path).isAbsolute) path else current?.baseDir?.let { "$it/$path" } ?: path
        val vf = LocalFileSystem.getInstance().findFileByPath(abs) ?: return
        val line0 = result.line?.let { (it - 1).coerceAtLeast(0) } // CLI is 1-based
        val col0 = result.column?.let { (it - 1).coerceAtLeast(0) } ?: 0
        val descriptor =
            if (line0 != null) OpenFileDescriptor(project, vf, line0, col0)
            else OpenFileDescriptor(project, vf)
        if (descriptor.canNavigate()) descriptor.navigate(true)
    }

    override fun dispose() {}

    private class ResultRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            when (val obj = (value as? DefaultMutableTreeNode)?.userObject) {
                is TypeGroup -> {
                    icon = AllIcons.Nodes.Folder
                    append(obj.type.ifBlank { "(other)" }, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    val tail =
                        if (obj.failed > 0) "  ${obj.total} (${obj.failed} failed)" else "  ${obj.total}"
                    append(
                        tail,
                        if (obj.failed > 0) SimpleTextAttributes.ERROR_ATTRIBUTES
                        else SimpleTextAttributes.GRAYED_ATTRIBUTES,
                    )
                }
                is SourceFileResult -> {
                    icon = if (obj.failed) AllIcons.General.Error else AllIcons.General.InspectionsOK
                    append(obj.fullName)
                    val stateAttributes = when {
                        obj.failed -> SimpleTextAttributes.ERROR_ATTRIBUTES
                        obj.state.equals("Unchanged", true) || obj.state.equals("Deleted", true) ->
                            SimpleTextAttributes.GRAYED_ATTRIBUTES
                        else -> SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES
                    }
                    append("  ${obj.state}", stateAttributes)
                    if (obj.failed && obj.error != null) {
                        append("  ${obj.error}", SimpleTextAttributes.ERROR_ATTRIBUTES)
                        toolTipText = "${obj.fullName}: ${obj.error}"
                    }
                }
            }
        }
    }

    private companion object {
        const val EXPAND_LIMIT = 200
    }
}
