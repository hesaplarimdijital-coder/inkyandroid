package com.inkyandroid.core.symbols

import com.inkyandroid.core.syntax.InkLineTokens
import com.inkyandroid.core.syntax.InkLines
import com.inkyandroid.core.syntax.InkTokenizer
import com.inkyandroid.core.syntax.JsRegex

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
    /**
     * Names from `VAR`, `CONST`, `LIST` and `EXTERNAL` lines. Upstream takes every word of such a line
     * after the keyword, so values are in it too (`VAR x = 5` gives `x` and `5`; a list gives its items).
     */
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
    fun symbolAt(row: Int): FlowSymbol? = symbolWithin(root, row)

    /** The knot, stitch and label containing [row] (upstream's `flowAtPos`, used by the outline). */
    fun flowAt(row: Int): FlowPath {
        val found = HashMap<FlowType, FlowSymbol>()
        var index: List<FlowSymbol>? = root
        var start = 0
        var end = root.size - 1
        while (index != null && start <= end) {
            val next = if (start < end) index[start + 1] else null
            if (next == null || row < next.row) {
                val symbol = index[start]
                if (row >= symbol.row) found[symbol.flowType] = symbol
                // upstream descends even into a symbol that starts after the row
                index = symbol.children.takeIf { it.isNotEmpty() }
                start = 0
                end = if (index != null) index.size - 1 else 0
            } else {
                start++
            }
        }
        return FlowPath(found[FlowType.Knot], found[FlowType.Stitch], found[FlowType.Choice], found[FlowType.Gather])
    }

    /** The file's top level as a scope for [InkProjectSymbols.findSymbol]'s walk outwards. */
    internal fun topLevelScope(): JsObjectMap<FlowSymbol> = topLevelMap

    /** How prose is cut into vocabulary words. */
    internal enum class WordSplit {
        /**
         * Upstream: `split(/\W+/)`, ASCII word characters only, which cuts `çocuk` into `ocuk`. Used by
         * the tests to compare with upstream's output.
         */
        UpstreamAscii,

        /**
         * Deviation, the default: letters, marks and digits of every script count as word characters, so
         * that Turkish (or any non-English) prose gives whole words. Identical to upstream for ASCII text.
         */
        Unicode,
    }

    companion object {
        /** The symbols of a file whose lines and tokens the caller has already (for example from the editor). */
        fun parse(lines: List<CharSequence>, tokens: List<InkLineTokens>): InkFileSymbols = parse(lines, tokens, WordSplit.Unicode)

        /** The symbols of [text], tokenized with [tokenizer]. */
        fun parse(text: CharSequence, tokenizer: InkTokenizer): InkFileSymbols {
            val lines = InkLines.split(text)
            return parse(lines, tokenizer.tokenizeLines(lines))
        }

        private val DICTIONARY_TAG = JsRegex.compile("""\s*(\w+)\s*:\s*(.+)""")

        private val FLOW_CODES = listOf(
            FlowType.Knot to ".knot.declaration",
            FlowType.Stitch to ".stitch.declaration",
            FlowType.Choice to "choice.label",
            FlowType.Gather to "gather.label",
        )
        private val VARIABLE_CODES = listOf("var-decl", "list-decl", "external")

        /** Upstream's `InkFileSymbols.prototype.parse`, over the tokens in file order. */
        internal fun parse(lines: List<CharSequence>, tokens: List<InkLineTokens>, wordSplit: WordSplit): InkFileSymbols {
            require(lines.size == tokens.size) { "One token list per line" }
            val includes = ArrayList<String>()
            var lastIncludeRow = -1
            val root = ArrayList<FlowSymbol>()
            val topLevel = JsObjectMap<FlowSymbol>()
            val globalTags = ArrayList<String>()
            val dictionaryTags = JsObjectMap<String>()
            val divertTargets = LinkedHashSet<String>()
            val variables = LinkedHashSet<String>()
            val externals = LinkedHashSet<String>()
            val vocabulary = LinkedHashSet<String>()

            // The stack of open symbols; empty means the file's top level (upstream's level-0 root element)
            val stack = ArrayList<FlowSymbol>()
            var isFunction = false

            for (row in lines.indices) {
                val line = lines[row]
                for (token in tokens[row].tokens) {
                    val type = token.type
                    // Flag: if set, the next name is a function's
                    if (type.endsWith("function")) isFunction = true

                    if (type.contains(".name")) {
                        val name = token.text(line)
                        val flowType = FLOW_CODES.firstOrNull { type.contains(it.second) }?.first
                        val isVariable = VARIABLE_CODES.any { type.contains(it) }
                        if (flowType != null) {
                            while (stack.isNotEmpty() && flowType.level <= stack.last().flowType.level) stack.removeAt(stack.size - 1)
                            val parent = stack.lastOrNull()
                            val symbol = FlowSymbol(name, flowType, isFunction, row, token.start, parent)
                            if (parent == null) {
                                topLevel.put(name, symbol)
                                root.add(symbol)
                            } else {
                                parent.innerMap.put(name, symbol)
                                parent.childList.add(symbol)
                            }
                            stack.add(symbol)
                            divertTargets.add(name)
                            isFunction = false
                        } else if (isVariable) {
                            if (type == "external.declaration.name") externals.add(name)
                            variables.add(name)
                        }
                        // Not a knot/stitch/gather/choice nor a variable. Do nothing.
                    } else if (type == "divert.target") {
                        val target = token.text(line)
                        if (JsRegex.jsTrim(target).isNotEmpty()) divertTargets.add(target)
                    }
                    // (Upstream also reads list items from "list-decl.item" tokens, a type its rules never
                    // produce; list items reach `variables` as "list-decl.name" tokens instead.)
                    else if (type.contains("include.filepath")) {
                        val path = token.text(line)
                        if (JsRegex.jsTrim(path).isNotEmpty()) {
                            includes.add(path)
                            lastIncludeRow = row
                        }
                    } else if (type == "tag" && stack.isEmpty()) {
                        // Skip the leading #
                        val content = JsRegex.jsTrim(token.text(line).substring(1))
                        globalTags.add(content)
                        val m = DICTIONARY_TAG.matcher(content)
                        if (m.find()) dictionaryTags.put(m.group(1), m.group(2))
                    } else if (type == "text") {
                        for (word in splitWords(token.text(line), wordSplit)) if (word.length >= 3) vocabulary.add(word)
                    }
                }
            }

            return InkFileSymbols(
                root, topLevel, includes, lastIncludeRow, globalTags, dictionaryTags,
                divertTargets, variables, externals, vocabulary,
            )
        }

        private fun splitWords(text: String, split: WordSplit): List<String> {
            val words = ArrayList<String>()
            var start = -1
            var i = 0
            while (i < text.length) {
                val cp = Character.codePointAt(text, i)
                val isWord = when (split) {
                    WordSplit.UpstreamAscii -> cp == '_'.code || cp in '0'.code..'9'.code || cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code
                    WordSplit.Unicode -> cp == '_'.code || Character.isLetterOrDigit(cp) || Character.getType(cp).let {
                        it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt()
                    }
                }
                if (isWord && start < 0) start = i
                if (!isWord && start >= 0) {
                    words.add(text.substring(start, i))
                    start = -1
                }
                i += Character.charCount(cp)
            }
            if (start >= 0) words.add(text.substring(start))
            return words
        }

        /** Upstream's `symbolWithinIndex`. */
        private fun symbolWithin(index: List<FlowSymbol>, row: Int): FlowSymbol? {
            for (i in index.indices) {
                val next = index.getOrNull(i + 1)
                if (next == null || row < next.row) {
                    val symbol = index[i]
                    return symbolWithin(symbol.children, row) ?: symbol
                }
            }
            return null
        }
    }
}
