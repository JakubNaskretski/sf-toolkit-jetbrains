package dev.skrety.sftoolkit.ui

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.EditorTextField
import com.intellij.util.LocalTimeCounter
import dev.skrety.sftoolkit.filetypes.SfFileTypes

/**
 * Editor pane for the SOQL / Anonymous Apex tool windows. Two hard platform constraints
 * (both bytecode-verified against 2024.2 and 2026.1) force this exact shape:
 *
 * 1. Completion: TextFieldWithAutoCompletion.installCompletion silently installs NOTHING
 *    when the document has no PSI file, and the only contributor that consumes the
 *    installed provider (TextCompletionContributor) is registered for language TEXT.
 *    So the document must be PSI-backed AND plain-text — a TextMate-typed PSI file has
 *    language "textmate" and the contributor never runs.
 * 2. Coloring: TextMate picks a grammar solely by virtual-file NAME, and an
 *    EditorTextField derives its highlighter from the document's file, whose type here
 *    must stay plain text (see 1). The two pull apart, so the TextMate highlighter is
 *    set explicitly from a separate light file whose name carries the .soql/.apex
 *    extension.
 */
class SfPaneTextField(
    private val paneProject: Project,
    private val paneFileName: String,
    initialText: String,
) : EditorTextField(
    createPaneDocument(paneProject, paneFileName, initialText),
    paneProject,
    PlainTextFileType.INSTANCE,
    false,
    false,
) {
    override fun createEditor(): EditorEx {
        val editor = super.createEditor()
        try {
            val grammarFile = LightVirtualFile(paneFileName, SfFileTypes.forName(paneFileName), "")
            editor.highlighter =
                EditorHighlighterFactory.getInstance().createEditorHighlighter(paneProject, grammarFile)
        } catch (_: Throwable) {
            // colors are cosmetic — a plain pane beats a broken pane
        }
        return editor
    }
}

/**
 * eventSystemEnabled=true is load-bearing: the 3-arg createFileFromText overload builds a
 * non-physical file for which PsiDocumentManager.getDocument returns null.
 */
private fun createPaneDocument(project: Project, fileName: String, text: String): Document {
    val psi = PsiFileFactory.getInstance(project).createFileFromText(
        fileName,
        PlainTextFileType.INSTANCE,
        text,
        LocalTimeCounter.currentTime(),
        true,
    )
    return PsiDocumentManager.getInstance(project).getDocument(psi)
        ?: psi.viewProvider.document
        ?: error("no document for pane file $fileName")
}
