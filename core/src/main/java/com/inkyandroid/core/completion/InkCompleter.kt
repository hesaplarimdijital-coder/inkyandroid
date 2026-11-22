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
    fun prefixAt(line: CharSequence, column: Int): String = TODO()

    /**
     * The suggestions for the cursor at [column] of [line], whose tokens are [lineTokens], with the
     * symbols of every file of the project in [files] (project order).
     *
     * What is offered depends on the token at the cursor: in a divert, a knot or stitch declaration or a
     * label, the divert targets; in logic, divert targets, then variables, then vocabulary; elsewhere,
     * vocabulary. The keywords always come first.
     */
    fun complete(line: CharSequence, lineTokens: InkLineTokens, column: Int, files: List<InkFileSymbols>): CompletionResult =
        TODO()
}
