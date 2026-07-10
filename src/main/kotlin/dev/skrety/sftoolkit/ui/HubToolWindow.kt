package dev.skrety.sftoolkit.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import dev.skrety.sftoolkit.LoginAction
import java.awt.FlowLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The home window (left stripe): everything the plugin can do, in one place —
 * org switching, every tool window, and the schema/diagnostic actions.
 */
class HubToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = HubPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

class HubPanel(private val project: Project) : Disposable {

    private val orgCombo = OrgCombo(project) // project-wide switcher

    val component: JComponent = build()

    private fun build(): JComponent {
        val main = JPanel(VerticalLayout(JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(8, 10)

            add(TitledSeparator("Org"))
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).also { orgCombo.addTo(it) })
            add(actionButton("Log In to New Org…", AllIcons.General.User) { LoginAction.run(project) })

            add(TitledSeparator("Windows"))
            add(actionButton("Org Metadata Browser", AllIcons.Toolwindows.ToolWindowStructure) { open("SF Metadata") })
            add(actionButton("SOQL Queries", AllIcons.Nodes.DataTables) { open("SOQL") })
            add(actionButton("Anonymous Apex", AllIcons.Actions.Execute) { open("Anonymous Apex") })
            add(actionButton("CLI Log", AllIcons.Toolwindows.ToolWindowMessages) { open("SF Log") })

            add(TitledSeparator("Apex Schema & Tools"))
            add(actionButton("Generate Apex sObject Definitions", AllIcons.Actions.Download) {
                execute("SfToolkit.GenerateSObjects")
            })
            add(actionButton("Run Health Check", AllIcons.General.InspectionsEye) {
                execute("SfToolkit.HealthCheck")
            })
        }
        return JBScrollPane(main).apply { border = JBUI.Borders.empty() }
    }

    private fun actionButton(text: String, icon: Icon, onClick: () -> Unit): JButton =
        JButton(text, icon).apply {
            horizontalAlignment = SwingConstants.LEFT
            isFocusable = false
            addActionListener { onClick() }
        }

    private fun open(id: String) {
        ToolWindowManager.getInstance(project).getToolWindow(id)?.show(null)
    }

    private fun execute(actionId: String) {
        val action = ActionManager.getInstance().getAction(actionId) ?: return
        ActionManager.getInstance().tryToExecute(action, null, component, null, true)
    }

    override fun dispose() {
        orgCombo.dispose()
    }
}
