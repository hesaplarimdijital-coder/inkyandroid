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
     *
     * The ids are stable (`[a-z_]`), chosen with the app, which names its string resources after them.
     */
    val builtIn: List<SnippetNode> by lazy { buildTree() }

    /**
     * The Ink menu's entries as upstream builds them (`appmenus.js`): the built-in tree, then, if the
     * project's settings file has any custom snippets, a separator and the custom entries.
     */
    fun menu(custom: List<SnippetNode>): List<SnippetNode> = if (custom.isEmpty()) builtIn else builtIn + SnippetNode.Separator + custom

    /** The built-in node with the given [id], or null. */
    fun byId(id: String): SnippetNode? = byIdMap[id]

    private val byIdMap: Map<String, SnippetNode> by lazy {
        val map = HashMap<String, SnippetNode>()
        fun visit(node: SnippetNode) {
            when (node) {
                is SnippetNode.Category -> {
                    node.id?.let { map[it] = node }
                    node.children.forEach(::visit)
                }
                is SnippetNode.Snippet -> node.id?.let { map[it] = node }
                SnippetNode.Separator -> Unit
            }
        }
        builtIn.forEach(::visit)
        map
    }

    /** Upstream's `loadLongerSnippet`: the file's text plus a final line break. */
    private fun longer(fileName: String): String {
        val stream = InkSnippets::class.java.getResourceAsStream("/com/inkyandroid/core/snippets/$fileName")
            ?: error("Missing bundled snippet $fileName")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) } + "\n"
    }

    private fun category(id: String, name: String, vararg children: SnippetNode) = SnippetNode.Category(id, name, children.toList())

    private fun snippet(id: String, name: String, ink: String) = SnippetNode.Snippet(id, name, ink)

    private val separator = SnippetNode.Separator

    // Upstream's inkSnippets.js, in its order and with its texts. SnippetsGoldenTest compares it with
    // what upstream's file evaluates to.
    private fun buildTree(): List<SnippetNode> = listOf(
        category(
            "basic_structure", "Basic structure",
            snippet(
                "knot", "Knot (main section)",
                "=== knotName ===\n" +
                    "This is the content of the knot.\n" +
                    "-> END\n",
            ),
            snippet(
                "stitch", "Stitch (sub-section)",
                "= stitchName\n" +
                    "This is the content of the stitch that should be embedded within a knot.\n" +
                    "-> END\n",
            ),
            separator,
            snippet("divert", "Divert", "-> targetKnotName"),
            snippet("ending", "Ending indicator", "-> END\n"),
        ),
        category(
            "choices", "Choices",
            snippet("choice_basic", "Basic Choice", "* This is a choice that can only be chosen once\n"),
            snippet("choice_sticky", "Sticky choice", "+ This is a sticky choice - the player can choose it more than once\n"),
            snippet("choice_suppressed", "Choice without printing", "* [A choice where the content isn't printed after choosing]\n"),
            snippet("choice_mixed", "Choice with mixed output", "* Try [it] this example!\n"),
        ),
        category(
            "variables", "Variables",
            snippet("variable_global", "Global variable", "VAR myNumber = 5\n"),
            snippet("variable_temporary", "Temporary variable", "temp myTemporaryValue = 5\n"),
            snippet("variable_modify", "Modify variable", "~ myNumber = myNumber + 1\n"),
            snippet("variable_type", "Get variable type", longer("type_of.ink")),
        ),
        category(
            "inline_logic", "Inline logic",
            snippet("inline_condition", "Condition", "{yourVariable: This is written if yourVariable is true|Otherwise this is written}"),
        ),
        category(
            "multiline_logic", "Multi-line logic",
            snippet(
                "multiline_condition", "Condition",
                "{yourVariable:\n" +
                    "    This is written if yourVariable is true.\n" +
                    "  - else:\n" +
                    "    Otherwise this is written.\n" +
                    "}\n",
            ),
        ),
        category(
            "comments", "Comments",
            snippet("comment_line", "Single-line comment", "// This line is a comment.\n"),
            snippet(
                "comment_block", "Block comment",
                "/* ---------------------------------\n" +
                    "\n" +
                    "   This whole section is a comment \n" +
                    "\n" +
                    " ----------------------------------*/\n",
            ),
        ),
        separator,
        category(
            "list_handling", "List-handling",
            snippet("list_pop", "List: pop", longer("list_pop.ink")),
            snippet("list_pop_random", "List: pop_random", longer("list_pop_random.ink")),
            snippet("list_next_prev", "List: LIST_NEXT and LIST_PREV", longer("list_prev_next.ink")),
            snippet("list_item_is_member_of", "List: list_item_is_member_of", longer("list_item_is_member_of.ink")),
            snippet("list_random_subset", "List: list_random_subset", longer("list_random_subset.ink")),
            snippet("list_random_subset_of_size", "List: list_random_subset_of_size", longer("list_random_subset_of_size.ink")),
            snippet("list_string_to_list", "List: string_to_list", longer("string_to_list.ink")),
        ),
        category(
            "useful_functions", "Useful functions",
            snippet("logic_maybe", "Logic: maybe", longer("maybe.ink")),
            separator,
            snippet(
                "math_divisor", "Mathematics: divisor",
                "=== function divisor(x, n)\n" +
                    "~ return (x - x mod n) / n",
            ),
            snippet(
                "math_abs", "Mathematics: abs",
                "=== function abs(x)\n" +
                    "{ x < 0:\n" +
                    "      ~ return -1 * x\n" +
                    "  - else: \n" +
                    "      ~ return x\n" +
                    "}",
            ),
            separator,
            snippet("flow_came_from", "Flow: came_from", longer("came_from.ink")),
            snippet("flow_seen_very_recently", "Flow: seen_very_recently", longer("seen_very_recently.ink")),
            snippet("flow_seen_more_recently_than", "Flow: seen_more_recently_than", longer("seen_more_recently_than.ink")),
            snippet("flow_seen_this_scene", "Flow: seen_this_scene", longer("seen_this_scene.ink")),
            snippet("flow_thread_in_tunnel", "Flow: thread_in_tunnel", longer("thread_in_tunnel.ink")),
            separator,
            snippet("print_a_or_an", "Printing: a (or an)", longer("a_or_an.ink")),
            snippet("print_uppercase", "Printing: UPPERCASE", longer("uppercase.ink")),
            snippet("print_number", "Printing: print_number", longer("print_number.ink")),
            snippet("print_list_with_commas", "Printing: list_with_commas", longer("list_with_commas.ink")),
        ),
        category(
            "useful_systems", "Useful systems",
            snippet("system_list_items_as_integers", "List Items as Integer Variables", longer("listToNumber.ink")),
            snippet("system_swing_variables", "Swing Variables", longer("swing_variables.ink")),
            snippet("system_storylets", "Storylets", longer("storylets.ink")),
        ),
        separator,
        category(
            "full_stories", "Full stories",
            snippet("story_crime_scene", "Crime Scene (from Writing with Ink)", longer("murder_scene.ink")),
            snippet("story_swindlestones", "Swindlestones (from Sorcery!)", longer("swindlestones.ink")),
            snippet("story_pontoon", "Pontoon Game (from Overboard!)", longer("pontoon_example.ink")),
            snippet("story_intercept", "The Intercept", longer("theintercept.ink")),
        ),
    )
}
