package com.inkyandroid.core.syntax

/**
 * One token of a line, exactly as Ace's tokenizer produces it for upstream Inky's ink mode.
 *
 * [type] is Ace's token type string, for example `"divert.target"`, `"flow.knot.declaration.name"`,
 * `"text"` or `"logic.punctuation"`; the dot-separated parts are what Ace turns into CSS classes
 * (`ace_divert ace_target`), and what upstream derives symbols, completion and links from.
 * [start] and [length] are in UTF-16 units of the line.
 */
data class InkToken(val type: String, val start: Int, val length: Int) {

    /** The column after the last character of the token. */
    val end: Int get() = start + length

    /** The token's text, cut from the [line] it was produced for. */
    fun text(line: CharSequence): String = line.subSequence(start, end).toString()
}

/**
 * The tokens of one line and the tokenizer state at its end, which is the start state of the next line.
 *
 * The tokens cover the whole line without gaps, in order. An empty line has no tokens.
 */
class InkLineTokens(val tokens: List<InkToken>, val endState: InkLineState) {

    /**
     * The token at [column] with Ace's `EditSession.getTokenAt` rule: the first token whose end is at or
     * after [column], so at a boundary between two tokens it is the token before the boundary (the one the
     * cursor just typed into). Null for a line without tokens and for a column past the line's end, as in Ace.
     */
    fun tokenAt(column: Int): InkToken? {
        for (token in tokens) {
            if (token.end >= column) return token
        }
        return null
    }

    override fun toString(): String = "InkLineTokens(tokens=$tokens, endState=$endState)"
}
