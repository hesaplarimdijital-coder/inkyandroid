package com.inkyandroid.core.syntax

/**
 * The ink syntax tokenizer: Ace's tokenizer (v1.2.6, the version upstream Inky ships) running upstream's
 * ink highlight rules (`app/renderer/ace-ink-mode/ace-ink.js`) unchanged, line by line.
 *
 * A line's tokens depend only on the line's text and the state at its start; the state at its end is in
 * the result. Tokenize a file by feeding each line the previous line's end state, starting from
 * [initialState]; after an edit, re-tokenize from the edited line until a line's end state equals the
 * one it had before. Lines are split as Ace splits them ([InkLines.split]): they never contain `\r` or `\n`.
 *
 * [instructionPrefix] is the project setting of that name (upstream: `new InkMode(instructionPrefix)`):
 * a JavaScript regular expression source; lines with it are coloured as `customInstruction`. Null or
 * empty means the standard rules. If it is not a valid regular expression the tokenizer uses the
 * standard rules and says why in [instructionPrefixProblem] (upstream throws and keeps the old mode).
 *
 * Instances are immutable and thread-safe; building one compiles the rules (a few milliseconds), so keep
 * one per instruction prefix. Tokenizing a line allocates only its tokens.
 */
class InkTokenizer(val instructionPrefix: String? = null) {

    /** Why [instructionPrefix] was not used, or null if it was used or there is none. */
    val instructionPrefixProblem: String?

    internal val ace: AceTokenizer

    init {
        var problem: String? = null
        val withPrefix = if (instructionPrefix.isNullOrEmpty()) {
            null
        } else {
            try {
                AceTokenizer(InkHighlightRules.build(instructionPrefix))
            } catch (e: JsRegex.SyntaxException) {
                // Deviation: upstream's `new InkMode(prefix)` throws here and the project keeps its old mode;
                // the standard rules are the closest equivalent that keeps colouring working.
                problem = e.message
                null
            }
        }
        ace = withPrefix ?: AceTokenizer(InkHighlightRules.build(null))
        instructionPrefixProblem = problem
    }

    /** The state at the start of a file. */
    val initialState: InkLineState get() = InkLineState.initial

    /** The tokens of [line] (one line, without its line break) when the previous line ended in [startState]. */
    fun tokenizeLine(line: CharSequence, startState: InkLineState = initialState): InkLineTokens =
        ace.getLineTokens(line, startState)

    /** The tokens of every line of [text], split as Ace splits lines ([InkLines.split]). */
    fun tokenize(text: CharSequence): List<InkLineTokens> = tokenizeLines(InkLines.split(text))

    /** The tokens of consecutive [lines], the first starting in [startState]. */
    fun tokenizeLines(lines: List<CharSequence>, startState: InkLineState = initialState): List<InkLineTokens> {
        val result = ArrayList<InkLineTokens>(lines.size)
        var state = startState
        for (line in lines) {
            val tokens = tokenizeLine(line, state)
            result.add(tokens)
            state = tokens.endState
        }
        return result
    }
}
