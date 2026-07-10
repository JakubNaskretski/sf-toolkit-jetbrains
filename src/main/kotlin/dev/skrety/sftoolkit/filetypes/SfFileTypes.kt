package dev.skrety.sftoolkit.filetypes

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * Real file types for Salesforce sources: project-view icons + a Settings → File Types
 * entry. Deliberately NOT LanguageFileTypes — no PSI/parser claims. Highlighting is
 * delegated back to the TextMate grammars via SfTextMateEditorHighlighterProvider
 * (registering a concrete type stops TextMate's own takeover, verified against
 * TextMateFileType.isMyFileType in the 242 sources).
 */
object ApexFileType : FileType {
    override fun getName(): String = "Apex"
    override fun getDescription(): String = "Salesforce Apex source"
    override fun getDefaultExtension(): String = "cls"
    override fun getIcon(): Icon = AllIcons.Nodes.Class
    override fun isBinary(): Boolean = false
    override fun isReadOnly(): Boolean = false
    override fun getCharset(file: VirtualFile, content: ByteArray): String? = null
}

object SoqlFileType : FileType {
    override fun getName(): String = "SOQL"
    override fun getDescription(): String = "Salesforce SOQL query"
    override fun getDefaultExtension(): String = "soql"
    override fun getIcon(): Icon = AllIcons.Nodes.DataTables
    override fun isBinary(): Boolean = false
    override fun isReadOnly(): Boolean = false
    override fun getCharset(file: VirtualFile, content: ByteArray): String? = null
}
