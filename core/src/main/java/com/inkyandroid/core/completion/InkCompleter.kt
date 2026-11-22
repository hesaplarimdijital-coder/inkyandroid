package com.inkyandroid.core.completion

import com.inkyandroid.core.symbols.InkFileSymbols
import com.inkyandroid.core.syntax.InkLineTokens

/** Where a suggestion comes from; upstream shows this as the item's "meta" text. */
enum class CompletionKind(
    /** Upstream's English meta text, as a fallback for the app's own strings. */
    val upstreamMeta: String,
) {
    Keyword("Ink Keyword"),
    DivertTarget("Divert Target"),
    Variable("Variable"),
    Vocabulary("Vocabulary"),
}

/** One suggestion: the text inserted in place of the prefix, and its kind. */
data class CompletionItem(val value: String, val kind: CompletionKind)

/**
 * The suggestions at a cursor position. Inserting an item replaces the text from [replaceStart] to the
 * cursor (that is, [prefix]) with [CompletionItem.value].
 *
 * [items] is the list upstream's popup shows, in its order; it is empty when upstream would show no
 * popup: no prefix, nothing matches, or the only match is the prefix itself.
 */
data class CompletionResult(val prefix: String, val replaceStart: Int, val items: List<CompletionItem>) {
    companion object {
        val NONE = CompletionResult("", 0, emptyList())
    }
}

/**
 * Auto-complete as upstream Inky does it: Ace's live auto-completion (`ext-language_tools.js` as upstream
 * patched it: matches must start with the prefix, case-sensitively) fed by the ink mode's keywords and
 * `inkCompleter.js`.
 */
object InkCompleter {

    /** The ink keywords upstream's mode offers (`ace-ink.js: keywords`), in its order. */
    val keywords: List<String> = listOf(
        "CONST", "CHOICE_COUNT", "DONE", "END", "INCLUDE", "LIST", "LIST_ALL", "LIST_COUNT", "LIST_INVERT",
        "LIST_MAX", "LIST_MIN", "LIST_RANGE", "LIST_VALUE", "LIST_RANDOM", "TODO", "TURNS_SINCE", "VAR",
    )

    /**
     * The identifier before [column] in [line] that auto-complete completes (Ace's
     * `retrievePrecedingIdentifier`: letters, digits, `_`, `$`, `-` and every character from U+00A2 up).
     * Ace opens its popup after a typed character when this is not empty.
     */
    fun prefixAt(line: CharSequence, column: Int): String {
        var start = minOf(column, line.length)
        while (start > 0 && isIdentifierUnit(line[start - 1])) start--
        return line.subSequence(start, minOf(column, line.length)).toString()
    }

    /** Ace's `ID_REGEX`, `/[a-zA-Z_0-9\$\-\u00A2-\uFFFF]/`, tested per UTF-16 unit. */
    private fun isIdentifierUnit(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '$' || c == '-' || c.code >= 0xA2

    /**
     * The suggestions for the cursor at [column] of [line], whose tokens are [lineTokens], with the
     * symbols of every file of the project in [files] (project order).
     *
     * What is offered depends on the token at the cursor: in a divert, a knot or stitch declaration or a
     * label, the divert targets; in logic, divert targets, then variables, then vocabulary; elsewhere,
     * vocabulary. The keywords always come first.
     *
     * [explicit] is for a completion the user asked for (upstream: Ctrl+Space): it lists everything even
     * when the prefix is empty, where live completion offers nothing.
     */
    fun complete(
        line: CharSequence,
        lineTokens: InkLineTokens,
        column: Int,
        files: List<InkFileSymbols>,
        explicit: Boolean = false,
    ): CompletionResult {
        val prefix = prefixAt(line, column)
        if (prefix.isEmpty() && !explicit) return CompletionResult(prefix, column, emptyList())

        // Ace's completers in upstream's order: snippets (none for ink), the mode's keywords, inkCompleter
        val matches = ArrayList<CompletionItem>()
        keywords.mapTo(matches) { CompletionItem(it, CompletionKind.Keyword) }
        matches.addAll(inkSuggestions(lineTokens.tokenAt(column)?.type, files))

        // Ace's FilteredList as upstream patched it (exactMatch = true): the value must start with the
        // prefix, case-sensitively; every match then scores the same, so the order stays; consecutive
        // duplicates are dropped.
        val filtered = ArrayList<CompletionItem>()
        for (item in matches) {
            if (!item.value.startsWith(prefix)) continue
            if (filtered.lastOrNull()?.value == item.value) continue
            filtered.add(item)
        }
        // Ace closes the popup when the only match is what is already typed
        if (filtered.size == 1 && filtered[0].value == prefix) filtered.clear()
        return CompletionResult(prefix, column - prefix.length, filtered)
    }

    /**
     * `inkCompleter.getCompletions`: in a divert or divert target, a flow declaration or a label only
     * divert targets; in logic, divert targets (which can be used as values), variables and vocabulary
     * (logic can print text); elsewhere vocabulary.
     */
    private fun inkSuggestions(tokenType: String?, files: List<InkFileSymbols>): List<CompletionItem> {
        // Deviation: upstream throws on a line without tokens; such a line has no prefix to complete,
        // except for an explicit completion, which then offers what prose offers.
        val type = tokenType.orEmpty()
        val targets = { union(files) { it.divertTargets }.map { CompletionItem(it, CompletionKind.DivertTarget) } }
        val variables = { union(files) { it.variables }.map { CompletionItem(it, CompletionKind.Variable) } }
        val vocabulary = { union(files) { it.vocabulary }.map { CompletionItem(it, CompletionKind.Vocabulary) } }
        return when {
            type.contains("divert") || type.contains("flow") || type.contains(".label") -> targets()
            type.contains("logic") -> targets() + variables() + vocabulary()
            else -> vocabulary()
        }
    }

    private fun union(files: List<InkFileSymbols>, set: (InkFileSymbols) -> Set<String>): Set<String> {
        val result = LinkedHashSet<String>()
        for (file in files) result.addAll(set(file))
        return result
    }
}
