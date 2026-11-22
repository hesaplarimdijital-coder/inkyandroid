package com.inkyandroid.ui.extras

import android.content.res.Resources
import com.inkyandroid.R
import com.inkyandroid.core.snippets.InkSnippets
import com.inkyandroid.core.snippets.SnippetNode
import com.inkyandroid.session.ProjectSession
import com.inkyandroid.ui.extras.snippets.SnippetLocaliser
import com.inkyandroid.ui.extras.snippets.flatten
import desktopkit.components.PaletteItem

/**
 * The snippets for the command palette: every built-in snippet (localised name, its category path as
 * subtitle) and then the project's [custom] snippets, in the group "Snippets". Choosing one calls
 * [insert] with its ink. The English names are keywords too, so a writer who knows upstream's menu
 * finds "Sticky choice" in Turkish as well.
 */
fun snippetPaletteItems(custom: List<SnippetNode>, resources: Resources, insert: (String) -> Unit): List<PaletteItem> =
    paletteItems(InkSnippets.menu(custom), resources, insert)

/** [snippetPaletteItems] with the custom snippets of the settings file of the project open in [session]. */
fun snippetPaletteItems(session: ProjectSession, resources: Resources, insert: (String) -> Unit): List<PaletteItem> =
    snippetPaletteItems(session.state.value.projectSettings.customInkSnippets, resources, insert)

/** The palette's items for the whole tree [nodes] (built-in and custom). */
internal fun paletteItems(nodes: List<SnippetNode>, resources: Resources, insert: (String) -> Unit): List<PaletteItem> {
    val group = resources.getString(R.string.snippets_group)
    val separator = resources.getString(R.string.snippets_path_separator)
    val entries = SnippetLocaliser.of(resources).localise(nodes)
    return flatten(entries).map { flat ->
        PaletteItem(
            id = flat.key,
            title = flat.snippet.label,
            group = group,
            onSelect = { insert(flat.snippet.ink) },
            subtitle = flat.path.joinToString(separator).ifEmpty { null },
            keywords = (flat.englishPath + flat.snippet.englishName).distinct(),
        )
    }
}
