package dev.skrety.sftoolkit

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class OrgStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = OrgService.ORG_WIDGET_ID
    override fun getDisplayName(): String = "SF Toolkit: Salesforce Org"
    override fun createWidget(project: Project): StatusBarWidget = OrgStatusBarWidget(project)
}

class OrgStatusBarWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.MultipleTextValuesPresentation {

    override fun ID(): String = OrgService.ORG_WIDGET_ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun install(statusBar: StatusBar) {}
    override fun dispose() {}

    override fun getTooltipText(): String = "SF Toolkit: current Salesforce org"

    override fun getSelectedValue(): String {
        val current = OrgService.get(project).current
        return "SF: " + (current ?: "no org")
    }

    private sealed interface Item {
        data class OrgItem(val org: SfOrg) : Item
        data object Refresh : Item
        data object Login : Item
    }

    override fun getPopup(): JBPopup {
        val orgService = OrgService.get(project)
        val items: List<Item> =
            orgService.orgs.map { Item.OrgItem(it) } + listOf(Item.Refresh, Item.Login)
        val step = object : BaseListPopupStep<Item>("Salesforce Org", items) {
            override fun getTextFor(value: Item): String = when (value) {
                is Item.OrgItem -> buildString {
                    if (value.org.display == orgService.current) append("✓ ")
                    append(value.org.display)
                    if (value.org.alias != null) append(" (").append(value.org.username).append(")")
                    if (value.org.isScratch) append(" [scratch]")
                    // scratch orgs report connectedStatus "Unknown" — don't mislabel them
                    if (!value.org.connected && !value.org.isScratch) append(" [disconnected]")
                }
                Item.Refresh -> "Refresh org list"
                Item.Login -> "Log in to a new org…"
            }

            override fun onChosen(selectedValue: Item, finalChoice: Boolean): PopupStep<*>? =
                doFinalStep {
                    when (selectedValue) {
                        is Item.OrgItem -> orgService.current = selectedValue.org.display
                        Item.Refresh -> refreshOrgsInBackground(project)
                        Item.Login -> LoginAction.run(project)
                    }
                }
        }
        return JBPopupFactory.getInstance().createListPopup(step)
    }
}
