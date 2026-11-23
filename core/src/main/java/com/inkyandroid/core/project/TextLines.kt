package com.inkyandroid.core.project

/** Line arithmetic over a file's text, with lines as Ace (and the tokenizer) splits them: at `\r\n`, `\r` or `\n`. */
internal object TextLines {

    /** The offset just after the last character of the zero-based [row] (before its line break); the text's end for a row beyond it. */
    fun endOfRow(text: CharSequence, row: Int): Int {
        var current = 0
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '\n' || char == '\r') {
                if (current == row) return index
                if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                current++
            }
            index++
        }
        return text.length
    }

    /**
     * Where the `INCLUDE` lines of [text] have their paths: for each such line the range of the
     * path as written (after `INCLUDE` and its blanks, up to a `//` comment or the line's end,
     * without trailing blanks) and the path text.
     *
     * A plain line scan, used only to rewrite lines whose path the parser has already confirmed as
     * an include of the file; a line inside a block comment is found too, and rewriting it there
     * does no harm.
     */
    fun includePathRanges(text: CharSequence): List<Pair<IntRange, String>> {
        val found = ArrayList<Pair<IntRange, String>>()
        for (match in INCLUDE_LINE.findAll(text)) {
            val written = match.groups[1] ?: continue
            val path = written.value.substringBefore("//").trimEnd(' ', '\t')
            if (path.isEmpty()) continue
            val start = written.range.first
            found.add((start until start + path.length) to path)
        }
        return found
    }

    private val INCLUDE_LINE = Regex("""^[ \t]*INCLUDE[ \t]+([^\r\n]*)""", RegexOption.MULTILINE)
}
