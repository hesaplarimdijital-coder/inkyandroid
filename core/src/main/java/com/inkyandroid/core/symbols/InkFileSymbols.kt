package com.inkyandroid.core.symbols

import com.inkyandroid.core.syntax.InkLineTokens
import com.inkyandroid.core.syntax.InkLines
import com.inkyandroid.core.syntax.InkTokenizer

/**
 * What upstream knows about one ink file from its tokens (`inkFileSymbols.js`): the flow symbols (knots,
 * stitches, choice and gather labels) with their nesting, the includes, the global tags, and the word
 * sets auto-complete offers. Immutable; build it with [parse].
 *
 * Upstream re-parses a file 200 ms after its last change; the caller decides when (see `core-api.md`).
 */
class InkFileSymbols private constructor(
    /** The top-level symbols in file order (upstream's root `rangeIndex`). */
    val root: List<FlowSymbol>,
    private val topLevelMap: JsObjectMap<FlowSymbol>,
    /** The paths of the `INCLUDE` lines, in order, as written (upstream does not trim them). */
    val includes: List<String>,
    /** The zero-based row of the last `INCLUDE` line, or -1 if there is none. */
    val lastIncludeRow: Int,
    /** The text of each tag before the first flow symbol, without its `#`, trimmed. */
    val globalTags: List<String>,
    private val dictionaryTagMap: JsObjectMap<String>,
    /** Knot, stitch and label names, and every divert target written in the file, in order of appearance. */
    val divertTargets: Set<String>,
    /** Names declared by `VAR`, `CONST`, `LIST` and `EXTERNAL` lines (with upstream's quirks, see the port). */
    val variables: Set<String>,
    /** Names declared by `EXTERNAL` lines. */
    val externals: Set<String>,
    /** Words of three characters or more from the file's plain text. */
    val vocabulary: Set<String>,
) {
    /** The top-level symbols by name (upstream's `getSymbols()`), in upstream's order (see [FlowSymbol.innerSymbols]). */
    val topLevelSymbols: Map<String, FlowSymbol> get() = topLevelMap

    /**
     * Global tags of the form `key: value` (upstream's `globalDictionaryStyleTags`), for example the
     * `title` the web export uses. A later tag with the same key wins.
     */
    val dictionaryTags: Map<String, String> get() = dictionaryTagMap

    /**
     * The innermost symbol whose range contains [row] (upstream's `symbolAtPos`): the last symbol at each
     * level that starts at or before the row, digging into its children. Before the first symbol of a
     * level, upstream's rule still picks that first symbol. Null only for a file without symbols.
     */
    fun symbolAt(row: Int): FlowSymbol? = TODO()

    /** The knot, stitch and label containing [row] (upstream's `flowAtPos`, used by the outline). */
    fun flowAt(row: Int): FlowPath = TODO()

    companion object {
        /** The symbols of a file whose lines and tokens the caller has already (for example from the editor). */
        fun parse(lines: List<CharSequence>, tokens: List<InkLineTokens>): InkFileSymbols = TODO()

        /** The symbols of [text], tokenized with [tokenizer]. */
        fun parse(text: CharSequence, tokenizer: InkTokenizer): InkFileSymbols {
            val lines = InkLines.split(text)
            return parse(lines, tokenizer.tokenizeLines(lines))
        }
    }
}
