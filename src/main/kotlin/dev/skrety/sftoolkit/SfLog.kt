package dev.skrety.sftoolkit

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/** Console log of every sf CLI call — the JetBrains port of the family's OutputChannel convention. */
@Service(Service.Level.PROJECT)
class SfLog(@Suppress("unused") private val project: Project) {
    private var console: ConsoleView? = null
    private val pending = ArrayDeque<Pair<String, ConsoleViewContentType>>()

    @Synchronized
    fun attach(c: ConsoleView) {
        console = c
        pending.forEach { c.print(it.first, it.second) }
        pending.clear()
    }

    @Synchronized
    fun log(line: String, type: ConsoleViewContentType = ConsoleViewContentType.NORMAL_OUTPUT) {
        val c = console
        if (c != null) c.print(line + "\n", type)
        else {
            pending.addLast(line + "\n" to type)
            while (pending.size > 500) pending.removeFirst()
        }
    }

    fun cmd(line: String) = log("[cmd] $line", ConsoleViewContentType.USER_INPUT)
    fun ok(line: String) = log("[ok] $line")
    fun warn(line: String) = log("[warn] $line", ConsoleViewContentType.LOG_WARNING_OUTPUT)
    fun error(line: String) = log("[error] $line", ConsoleViewContentType.ERROR_OUTPUT)

    companion object {
        fun get(project: Project): SfLog = project.service()
    }
}

class SfLogToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val content = ContentFactory.getInstance().createContent(console.component, "", false)
        content.setDisposer(console)
        toolWindow.contentManager.addContent(content)
        SfLog.get(project).attach(console)
    }
}
