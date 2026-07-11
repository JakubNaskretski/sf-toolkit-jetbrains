package dev.skrety.sftoolkit.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import javax.swing.JComponent

/**
 * IC-style multi-tab tool window: "+" adds a tab, tabs are closeable, closing the
 * last one respawns a fresh tab. Panels are in-memory only — nothing is saved.
 */
fun setupTabbedToolWindow(
    project: Project,
    toolWindow: ToolWindow,
    tabPrefix: String,
    createPanel: () -> Pair<JComponent, Disposable>,
) {
    // Monotonic per window — count-based names duplicate after closing tabs
    // ("Query 2" twice once Query 1 is closed and + is clicked).
    var tabCounter = 0

    fun addTab() {
        if (project.isDisposed || toolWindow.isDisposed) return
        val (component, disposer) = createPanel()
        val contentManager = toolWindow.contentManager
        tabCounter++
        val content = ContentFactory.getInstance()
            .createContent(component, "$tabPrefix $tabCounter", false)
        content.isCloseable = true
        content.setDisposer(disposer)
        contentManager.addContent(content)
        contentManager.setSelectedContent(content)
    }

    addTab()
    (toolWindow as? ToolWindowEx)?.setTabActions(
        object : DumbAwareAction("New $tabPrefix Tab", null, AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) = addTab()
        },
    )
    toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
        override fun contentRemoved(event: ContentManagerEvent) {
            if (!project.isDisposed && !toolWindow.isDisposed &&
                toolWindow.contentManager.contentCount == 0
            ) {
                addTab()
            }
        }
    })
}
