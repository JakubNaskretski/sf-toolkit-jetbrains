package dev.skrety.sftoolkit.filetypes

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.LightVirtualFile

/**
 * Resolves the file type the platform assigns to a Salesforce file name — which is
 * TextMate's own type once our grammar is registered, so tool-window editor panes get
 * the same native highlighting as real files on disk. Falls back to plain text.
 *
 * We deliberately do NOT register our own FileType for .cls/.trigger/.apex/.soql:
 * a concrete type blocks TextMate's native highlighting, and the custom
 * editorHighlighterProvider delegation did not apply colors in practice.
 */
object SfFileTypes {
    fun forName(fileName: String): FileType = try {
        val byName = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        if (byName !is com.intellij.openapi.fileTypes.UnknownFileType) byName
        else FileTypeManager.getInstance().getFileTypeByFile(LightVirtualFile(fileName, ""))
    } catch (_: Throwable) {
        PlainTextFileType.INSTANCE
    }

    fun apex(): FileType = forName("SfToolkitScratch.apex")
    fun soql(): FileType = forName("SfToolkitScratch.soql")
}
