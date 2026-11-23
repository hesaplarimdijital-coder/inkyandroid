package com.inkyandroid.ink

import com.inkyandroid.core.syntax.InkLineState
import com.inkyandroid.core.syntax.InkLineTokens
import com.inkyandroid.core.syntax.InkLines
import com.inkyandroid.core.syntax.InkTokenizer

/**
 * The tokens of a document's lines with upstream's own token types, for what is decided by them
 * around the cursor: completion and links. (The editor's colours need only roles, and the editor
 * keeps those itself.)
 *
 * A line's tokens depend on the state the line before ended in, so they cannot be made for one
 * line alone. The cache keeps the end state of every line it has tokenized. When the text changes
 * ([update]) it finds the first line that differs and forgets the states from there on; a query
 * ([tokens]) tokenizes from the last known line down to the one asked for. Typing at the end of a
 * long file therefore costs a comparison of the text and one line's tokens, not a pass over the file.
 *
 * Not thread-safe: used from the main thread, like the editor it serves.
 */
class InkLineCache(tokenizer: InkTokenizer) {

    /** The tokenizer; a new one (the project's instruction prefix changed) forgets everything. */
    var tokenizer: InkTokenizer = tokenizer
        set(value) {
            if (field === value) return
            field = value
            valid = 0
        }

    private var text: CharSequence = ""
    private var lines: List<String> = listOf("")
    private var endStates = arrayOfNulls<InkLineState>(1)

    /** The number of leading lines whose end states are known for the current text. */
    private var valid = 0

    /** The document's lines as the tokenizer sees them (split as Ace splits them). */
    val lineCount: Int get() = lines.size

    fun line(row: Int): String = lines.getOrElse(row) { "" }

    /** The document is now [newText]. Cheap when nothing changed, or little did. */
    fun update(newText: CharSequence) {
        if (newText === text) return
        val old = text
        val common = commonPrefix(old, newText)
        if (common == old.length && common == newText.length) {
            text = newText
            return
        }
        // the line the first difference is in: every line before it is the same text in the same state
        var changedRow = 0
        for (i in 0 until common) {
            val char = old[i]
            // a line ends at \n, at \r\n (counted at its \n) or at a lone \r, as Ace splits
            if (char == '\n' || (char == '\r' && !(i + 1 < old.length && old[i + 1] == '\n'))) changedRow++
        }
        text = newText
        lines = InkLines.split(newText)
        if (endStates.size < lines.size) endStates = endStates.copyOf(lines.size + lines.size / 2)
        valid = minOf(valid, changedRow, lines.size)
    }

    /** The tokens of line [row] (zero-based), or null for a row the document does not have. */
    fun tokens(row: Int): InkLineTokens? {
        if (row !in lines.indices) return null
        var state = if (valid == 0) tokenizer.initialState else endStates[valid - 1]!!
        // lines between the last known one and the one asked for: only their end states are kept
        for (index in valid until row) {
            state = tokenizer.tokenizeLine(lines[index], state).endState
            endStates[index] = state
        }
        if (row > valid) valid = row
        val start = if (row == 0) tokenizer.initialState else endStates[row - 1]!!
        val result = tokenizer.tokenizeLine(lines[row], start)
        if (row == valid) {
            endStates[row] = result.endState
            valid = row + 1
        }
        return result
    }

    private fun commonPrefix(a: CharSequence, b: CharSequence): Int {
        val limit = minOf(a.length, b.length)
        var index = 0
        while (index < limit && a[index] == b[index]) index++
        return index
    }
}
