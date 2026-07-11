package dev.skrety.sftoolkit.metadata

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Action
import javax.swing.JComponent

/**
 * Non-modal "Retrieve Metadata" browser (replaces the old "SF Metadata" tool window —
 * IC2-style: a sub-window you open, search, pick components in, then retrieve).
 * The IDE stays usable while listing; List/Retrieve/Deploy run as project-scoped
 * background tasks and keep running even if the dialog is closed.
 */
class RetrieveMetadataDialog private constructor(
    private val project: Project,
) : DialogWrapper(project, true, IdeModalityType.MODELESS) {

    private val browser = MetadataBrowserPanel(project)

    // DialogWrapper is not itself a Disposable — force-close on project close via a guard.
    @Volatile
    private var released = false
    private val projectGuard = Disposable {
        if (!released) {
            released = true
            close(CANCEL_EXIT_CODE)
        }
    }

    init {
        title = "Retrieve Metadata"
        setModal(false)
        setCancelButtonText("Close")
        init()
        Disposer.register(disposable, browser)
        Disposer.register(project, projectGuard)
    }

    override fun createCenterPanel(): JComponent = browser.component

    // Retrieve/Deploy/Compare live inside the center panel; the frame contributes only Close.
    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    // Platform auto-persists size + location under this key.
    override fun getDimensionServiceKey(): String = "dev.skrety.sftoolkit.RetrieveMetadataDialog"

    override fun dispose() {
        released = true
        OPEN.remove(project, this)
        super.dispose()
    }

    companion object {
        private val OPEN = ConcurrentHashMap<Project, RetrieveMetadataDialog>()

        /** Single instance per project: front an open one, else open new (modeless). */
        fun show(project: Project) {
            OPEN[project]?.let { existing ->
                if (!existing.isDisposed) {
                    existing.window?.let {
                        it.toFront()
                        it.requestFocus()
                    }
                    return
                }
                OPEN.remove(project, existing)
            }
            val dialog = RetrieveMetadataDialog(project)
            OPEN[project] = dialog
            dialog.show()
        }
    }
}

/** Tools ▸ SF Toolkit ▸ Retrieve Metadata… and the Hub button both route here. */
class RetrieveMetadataAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        RetrieveMetadataDialog.show(e.project ?: return)
    }
}
