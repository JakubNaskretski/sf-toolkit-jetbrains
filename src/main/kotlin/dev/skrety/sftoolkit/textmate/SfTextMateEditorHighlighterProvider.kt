package dev.skrety.sftoolkit.textmate

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.ex.util.DataStorage
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.fileTypes.EditorHighlighterProvider
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.plugins.textmate.TextMateService
import org.jetbrains.plugins.textmate.language.syntax.highlighting.TextMateHighlighter
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateHighlightingLexer
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateLexerDataStorage

/**
 * Editor highlighting for our registered file types (Apex, SOQL), delegated to the
 * TextMate grammar resolved by file name — mirrors TextMateSyntaxHighlighterFactory +
 * TextMateEditorHighlighterProvider from the platform's textmate plugin (242 sources).
 * Falls back to plain text when the bundle/grammar isn't available.
 */
class SfTextMateEditorHighlighterProvider : EditorHighlighterProvider {

    override fun getEditorHighlighter(
        project: Project?,
        fileType: FileType,
        virtualFile: VirtualFile?,
        colors: EditorColorsScheme,
    ): EditorHighlighter {
        return object : LexerEditorHighlighter(syntaxHighlighterFor(virtualFile), colors) {
            override fun createStorage(): DataStorage = TextMateLexerDataStorage()
        }
    }

    private fun syntaxHighlighterFor(virtualFile: VirtualFile?): SyntaxHighlighter {
        if (virtualFile != null) {
            val descriptor = TextMateService.getInstance()
                ?.getLanguageDescriptorByFileName(virtualFile.name)
            if (descriptor != null) {
                return TextMateHighlighter(
                    TextMateHighlightingLexer(
                        descriptor,
                        Registry.get("textmate.line.highlighting.limit").asInteger(),
                    ),
                )
            }
        }
        return TextMateHighlighter(null)
    }
}
