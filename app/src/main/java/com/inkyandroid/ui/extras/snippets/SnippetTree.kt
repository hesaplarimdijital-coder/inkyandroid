package com.inkyandroid.ui.extras.snippets

import android.content.res.Resources
import com.inkyandroid.core.snippets.SnippetNode

/**
 * The Ink menu's tree with the names to show: built-in names in the window's language, custom ones
 * as the project's settings file writes them. [englishName] is upstream's name of a built-in entry
 * (the same as [label] for custom ones), so the palette finds a snippet by either.
 */
sealed interface SnippetEntry {
    data object Separator : SnippetEntry

    data class Category(val label: String, val englishName: String, val children: List<SnippetEntry>) : SnippetEntry

    data class Snippet(val label: String, val englishName: String, val ink: String, val accelerator: String?) : SnippetEntry
}

/** A snippet as the command palette lists it: its place in the tree ([path] of category labels). */
data class FlatSnippet(val key: String, val snippet: SnippetEntry.Snippet, val path: List<String>, val englishPath: List<String>)

/** Turns `:core`'s snippet nodes into [SnippetEntry]s, naming each built-in one through [names]. */
class SnippetLocaliser(private val names: (SnippetNode) -> String?) {

    /** The localised tree of [nodes]; a node whose id has no name keeps upstream's English name. */
    fun localise(nodes: List<SnippetNode>): List<SnippetEntry> = nodes.map { node ->
        when (node) {
            SnippetNode.Separator -> SnippetEntry.Separator
            is SnippetNode.Category -> SnippetEntry.Category(names(node) ?: node.name, node.name, localise(node.children))
            is SnippetNode.Snippet -> SnippetEntry.Snippet(names(node) ?: node.name, node.name, node.ink, node.accelerator)
        }
    }

    companion object {
        /** Names from the app's resources by the built-in ids ([SnippetNames]); custom nodes have no id. */
        fun of(resources: Resources): SnippetLocaliser = SnippetLocaliser { node ->
            val table = when (node) {
                is SnippetNode.Category -> SnippetNames.categories
                is SnippetNode.Snippet -> SnippetNames.snippets
                SnippetNode.Separator -> null
            }
            node.idOrNull()?.let { table?.get(it) }?.let(resources::getString)
        }

        private fun SnippetNode.idOrNull(): String? = when (this) {
            is SnippetNode.Category -> id
            is SnippetNode.Snippet -> id
            SnippetNode.Separator -> null
        }
    }
}

/** Every snippet of [entries] in menu order with the categories above it; separators and empty categories vanish. */
fun flatten(entries: List<SnippetEntry>): List<FlatSnippet> {
    val result = ArrayList<FlatSnippet>()
    fun walk(level: List<SnippetEntry>, path: List<String>, englishPath: List<String>, key: String) {
        level.forEachIndexed { index, entry ->
            when (entry) {
                SnippetEntry.Separator -> Unit
                is SnippetEntry.Category -> walk(entry.children, path + entry.label, englishPath + entry.englishName, "$key/$index")
                is SnippetEntry.Snippet -> result += FlatSnippet("$key/$index", entry, path, englishPath)
            }
        }
    }
    walk(entries, emptyList(), emptyList(), "snippet")
    return result
}
