package com.inkyandroid.ink

import com.inkyandroid.core.project.ProjectPaths
import com.inkyandroid.core.syntax.InkLineTokens

/**
 * Something in a line of ink that leads elsewhere, as desktop Inky's Alt+click has it: the target
 * of a divert (`-> knot.stitch`, a tunnel's, a thread's, a divert passed as an argument) leads to
 * its definition, the path of an `INCLUDE` line to that file. Nothing else is a link: not a knot's
 * name where it is declared, not a variable, not `END` or `DONE`.
 *
 * [start] and [end] are columns of the line, the end exclusive.
 */
sealed interface InkLink {
    val start: Int
    val end: Int

    /** A divert's target as it is written, e.g. `knot`, `knot.stitch`, `label`. */
    data class Divert(val target: String, override val start: Int, override val end: Int) : InkLink

    /** An included file: [path] is the project path the compiler reads from the line, null when the line names none. */
    data class Include(val written: String, val path: String?, override val start: Int, override val end: Int) : InkLink
}

/** Finds the links of a line from its tokens: the highlighter's token types decide, exactly as upstream. */
object InkLinks {

    private const val DIVERT_TARGET = "divert.target"
    private const val INCLUDE_PATH = "include.filepath"

    /**
     * The link at [column] of [line], or null. The token is chosen by Ace's rule ([InkLineTokens.tokenAt]:
     * the token that contains the column or ends at it), so a cursor right behind a target still is on it.
     *
     * Upstream's rule for a divert's target keeps the blanks around it inside the token; the link's
     * range and its target are the name without them.
     */
    fun linkAt(line: CharSequence, tokens: InkLineTokens, column: Int): InkLink? {
        val token = tokens.tokenAt(column) ?: return null
        if (token.type != DIVERT_TARGET && token.type != INCLUDE_PATH) return null
        val raw = token.text(line)
        val lead = raw.length - raw.trimStart().length
        val name = raw.trim()
        if (name.isEmpty()) return null
        val start = token.start + lead
        val end = start + name.length
        return if (token.type == DIVERT_TARGET) {
            InkLink.Divert(name, start, end)
        } else {
            InkLink.Include(raw, ProjectPaths.normalizeInclude(raw), start, end)
        }
    }
}
