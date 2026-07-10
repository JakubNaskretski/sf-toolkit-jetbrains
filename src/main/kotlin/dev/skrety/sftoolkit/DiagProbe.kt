package dev.skrety.sftoolkit

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.testFramework.LightVirtualFile

// TEMP diagnostic — remove after highlighting is confirmed.
class DiagProbe : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: List<String>) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val log = Logger.getInstance(DiagProbe::class.java)
            try {
                val ft = FileTypeManager.getInstance().getFileTypeByFileName("Probe.cls")
                log.warn("SFDIAG cls fileType = ${ft.name} (${ft.javaClass.name})")
                val scheme = EditorColorsManager.getInstance().globalScheme
                val vf = LightVirtualFile("Probe.cls", "public class Probe { void m(){} }")
                ApplicationManager.getApplication().runReadAction {
                    val hl = EditorHighlighterFactory.getInstance()
                        .createEditorHighlighter(vf, scheme, null)
                    log.warn("SFDIAG editorHighlighter = ${hl.javaClass.name}")
                }
            } catch (t: Throwable) {
                log.warn("SFDIAG failed: $t")
            }
        }
    }
}
