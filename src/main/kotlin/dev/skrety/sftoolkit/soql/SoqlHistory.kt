package dev.skrety.sftoolkit.soql

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Pure MRU: newest first, dedup, capped. */
fun mruAdd(entries: List<String>, item: String, cap: Int = 25): List<String> {
    val trimmed = item.trim()
    if (trimmed.isEmpty()) return entries
    return (listOf(trimmed) + entries.filter { it != trimmed }).take(cap)
}

/** Last-run SOQL queries, per project (workspace.xml — never in VCS). */
@Service(Service.Level.PROJECT)
@State(name = "SfToolkitSoqlHistory", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class SoqlHistory : SimplePersistentStateComponent<SoqlHistory.MyState>(MyState()) {

    class MyState : BaseState() {
        var entries by list<String>()
    }

    fun entries(): List<String> = state.entries.toList()

    fun add(query: String) {
        val updated = mruAdd(state.entries, query)
        state.entries.clear()
        state.entries.addAll(updated)
        state.intIncrementModificationCount()
    }

    companion object {
        fun get(project: Project): SoqlHistory = project.service()
    }
}
