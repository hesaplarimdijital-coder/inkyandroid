package com.inkyandroid.core.syntax

/** Line splitting as Ace's document does it, so that rows here are the editor's rows upstream. */
object InkLines {

    /**
     * The lines of [text], split at `\r\n`, `\r` and `\n` (Ace: `/\r\n|\r|\n/`), without the line breaks.
     * Text ending in a line break has a last, empty line; empty text is one empty line.
     */
    fun split(text: CharSequence): List<String> {
        val lines = ArrayList<String>()
        var lineStart = 0
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                lines.add(text.subSequence(lineStart, i).toString())
                if (c == '\r' && i + 1 < n && text[i + 1] == '\n') i++
                lineStart = i + 1
            }
            i++
        }
        lines.add(text.subSequence(lineStart, n).toString())
        return lines
    }
}
