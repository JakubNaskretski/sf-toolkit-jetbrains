package dev.skrety.sftoolkit.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import dev.skrety.sftoolkit.OrgService
import dev.skrety.sftoolkit.refreshOrgsInBackground
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Shared org switcher (combo + refresh button) used by the SOQL and Anonymous Apex
 * panels. Mirrors OrgService both ways; family lesson: one component, not N diverging
 * copies. Owner must dispose it (removes the OrgService listener).
 */
class OrgCombo(private val project: Project) : Disposable {

    val combo = ComboBox<String>().apply {
        toolTipText = "Org to run against (also updates the project-wide selection)"
        setMinimumAndPreferredWidth(220)
    }
    val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Refresh org list"
        isFocusable = false
    }

    private var updating = false
    private val listener = Runnable { sync() }

    init {
        combo.addActionListener {
            if (!updating) {
                val selected = combo.selectedItem as? String
                if (!selected.isNullOrBlank() && selected != OrgService.get(project).current) {
                    OrgService.get(project).current = selected
                }
            }
        }
        refreshButton.addActionListener { refreshOrgsInBackground(project) }
        OrgService.get(project).addChangeListener(listener)
        sync()
        if (OrgService.get(project).orgs.isEmpty()) refreshOrgsInBackground(project, quiet = true)
    }

    /** Adds "Org:" label + combo + refresh button to a FlowLayout toolbar. */
    fun addTo(toolbar: JPanel) {
        toolbar.add(JBLabel("Org:"))
        toolbar.add(combo)
        toolbar.add(refreshButton)
    }

    override fun dispose() {
        OrgService.get(project).removeChangeListener(listener)
    }

    private fun sync() {
        updating = true
        try {
            val orgService = OrgService.get(project)
            val items = orgService.orgs.map { it.display }.toMutableList()
            val current = orgService.current
            if (current != null && current !in items) items.add(0, current)
            combo.model = DefaultComboBoxModel(items.toTypedArray())
            combo.selectedItem = current
        } finally {
            updating = false
        }
    }
}
