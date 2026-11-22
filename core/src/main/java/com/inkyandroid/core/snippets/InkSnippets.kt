package com.inkyandroid.core.snippets

/**
 * An entry of the Ink menu's snippet tree (upstream's `inkSnippets.js` and the custom snippets of a
 * project's settings file).
 */
sealed interface SnippetNode {

    /** A line between groups of entries. */
    data object Separator : SnippetNode

    /**
     * A submenu: a built-in category or a custom submenu.
     *
     * [id] is a stable identifier for built-in categories, from which the app looks up its translated
     * name; [name] is upstream's English name, the fallback. Custom submenus have no id and show [name].
     */
    data class Category(val id: String?, val name: String, val children: List<SnippetNode>) : SnippetNode

    /**
     * A snippet: [ink] is inserted at the cursor as typed.
     *
     * [id] and [name] as for [Category]. [accelerator] is a custom snippet's shortcut, as written in the
     * settings file (an Electron accelerator such as `"CmdOrCtrl+Shift+1"`, see [Accelerator.parse]);
     * built-in snippets have none.
     */
    data class Snippet(val id: String?, val name: String, val ink: String, val accelerator: String? = null) : SnippetNode
}

/** The built-in snippets and how the Ink menu is made of them. */
object InkSnippets {

    /**
     * The built-in tree in upstream's order: categories (each a [SnippetNode.Category] of snippets and
     * separators) and separators between groups of categories. The long snippets' text is loaded from
     * resources bundled in `:core` (upstream's `longer-ink-snippets`, each with a final line break added as
     * upstream does) on first use.
     */
    val builtIn: List<SnippetNode> get() = TODO()

    /**
     * The Ink menu's entries as upstream builds them (`appmenus.js`): the built-in tree, then, if the
     * project's settings file has any custom snippets, a separator and the custom entries.
     */
    fun menu(custom: List<SnippetNode>): List<SnippetNode> = if (custom.isEmpty()) builtIn else builtIn + SnippetNode.Separator + custom

    /** The built-in node with the given [id], or null. */
    fun byId(id: String): SnippetNode? = TODO()
}
