package com.inkyandroid.core.search

import com.inkyandroid.core.symbols.FileSymbols
import com.inkyandroid.core.symbols.FlowSymbol
import com.inkyandroid.core.symbols.FlowType
import com.inkyandroid.core.syntax.InkLines
import com.inkyandroid.core.syntax.JsRegex

/** A project file as "Go to anything" lists it: its file name, and the folder shown before it. */
data class GotoFile(
    /** Root-relative path, `/`-separated. */
    val path: String,
    /** The file name, what the palette matches against (upstream: `file.filename()`). */
    val name: String,
    /** The folder part of [path] without a trailing `/`, or "" for a file at the root. */
    val directory: String,
)

/** A symbol as "Go to anything" lists it. */
data class GotoSymbol(
    /** What the palette matches against: the symbol's own name. */
    val name: String,
    /** The enclosing symbols' names, each followed by a dot (`"knot.stitch."`), or "" (upstream's ancestor text). */
    val ancestors: String,
    val flowType: FlowType,
    val isFunction: Boolean,
    /** The file the symbol is in. */
    val path: String,
    val row: Int,
    val column: Int,
) {
    internal companion object {
        fun of(symbol: FlowSymbol, path: String) = GotoSymbol(
            name = symbol.name,
            ancestors = symbol.ancestorNames.joinToString("") { "$it." },
            flowType = symbol.flowType,
            isFunction = symbol.isFunction,
            path = path,
            row = symbol.row,
            column = symbol.column,
        )
    }
}

/**
 * What "Go to anything" (`goto.js`) can offer, collected when it opens: the project's files, every symbol
 * of the active file (at any depth), and the top-level symbols of the other files. The palette ranks them
 * against the query itself; [TextSearchIndex] adds the matching text lines and [GotoQuery] the line
 * number and runtime path entries.
 */
data class GotoCandidates(
    /** Every file, in project order. */
    val files: List<GotoFile>,
    /**
     * The active file's symbols at every depth, in upstream's order (`collectSymbols`): a level's symbols,
     * then, for each of them in turn, its own children and their descendants the same way. Names repeated
     * in the same scope appear once, as in upstream's name maps.
     */
    val activeFileSymbols: List<GotoSymbol>,
    /** The other files' top-level symbols, in project order. */
    val otherSymbols: List<GotoSymbol>,
) {
    companion object {
        /** Collects the candidates of the project's [files] (project order), [activePath] being the open one. */
        fun collect(files: List<FileSymbols>, activePath: String): GotoCandidates {
            val gotoFiles = files.map { file ->
                val slash = file.path.lastIndexOf('/')
                GotoFile(
                    path = file.path,
                    name = file.path.substring(slash + 1),
                    directory = if (slash < 0) "" else file.path.substring(0, slash),
                )
            }
            val active = ArrayList<GotoSymbol>()
            val others = ArrayList<GotoSymbol>()
            for (file in files) {
                if (file.path == activePath) {
                    collectRecursively(active, file.symbols.topLevelSymbols.values, file.path)
                } else {
                    file.symbols.topLevelSymbols.values.mapTo(others) { GotoSymbol.of(it, file.path) }
                }
            }
            return GotoCandidates(gotoFiles, active, others)
        }

        private fun collectRecursively(into: MutableList<GotoSymbol>, symbols: Collection<FlowSymbol>, path: String) {
            symbols.mapTo(into) { GotoSymbol.of(it, path) }
            for (symbol in symbols) {
                if (symbol.innerSymbols.isNotEmpty()) collectRecursively(into, symbol.innerSymbols.values, path)
            }
        }
    }
}

/** The special entries upstream derives from the query text itself. */
object GotoQuery {

    /**
     * The zero-based row to go to if the query is a line number (`^\s*(\d+)\s*$`): upstream's
     * "Go to line N" entry, which is listed first. Line 0 gives row -1, which the caller treats as out of
     * range (upstream then shows an empty line content). Null if the query is not a line number.
     */
    fun lineRow(query: String): Int? {
        val m = LINE_NUMBER.matcher(query)
        if (!m.find()) return null
        // parseInt; a number beyond any file just goes nowhere
        val number = m.group(1).toBigInteger().min(Int.MAX_VALUE.toBigInteger()).toInt()
        return number - 1
    }

    private val LINE_NUMBER = JsRegex.compile("""^\s*(\d+)\s*$""")
    private val RUNTIME_PATH = JsRegex.compile("""^(?:(?:[\w-]+)\.)+[\w-]+$""")

    /**
     * The query, if it has the shape of an ink runtime path (`knot.0.c-1`: `^(?:(?:[\w-]+)\.)+[\w-]+$`).
     * Upstream then asks the compiled story where that path is in the source and lists the line it gets.
     */
    fun runtimePath(query: String): String? = query.takeIf { RUNTIME_PATH.matcher(it).find() }
}

/** A line of a project file that contains the query. */
data class TextLineMatch(
    val path: String,
    val row: Int,
    /** The whole line. */
    val line: String,
    /** Where in [line] the first occurrence of the query starts. */
    val matchStart: Int,
)

/** A searchable file: its path and its current text. */
data class SearchableFile(val path: String, val text: CharSequence)

/**
 * The lines of a project prepared for "Go to anything"'s text search (upstream caches them when the
 * palette opens): the active file first, then the others in project order. Build it once when the
 * palette opens, then call [find] for every change of the query. Thread-safe once built.
 */
class TextSearchIndex(files: List<SearchableFile>, activePath: String) {

    private class Line(val path: String, val row: Int, val text: String, val lower: String, val lowerToText: IntArray?)

    // Deviation: lines are split as the editor splits them (\r\n, \r, \n), so that rows agree with it
    // even for files with old Mac line ends; upstream splits at "\n" only and keeps a "\r".
    private val lines: List<Line> = (files.filter { it.path == activePath } + files.filter { it.path != activePath })
        .flatMap { file -> InkLines.split(file.text).mapIndexed { row, text -> line(file.path, row, text) } }

    private fun line(path: String, row: Int, text: String): Line {
        val lower = text.lowercase()
        if (lower.length == text.length) return Line(path, row, text, lower, null)
        // Lower-casing changed the length (İ becomes i and a combining dot): map positions back
        val builder = StringBuilder()
        val map = ArrayList<Int>()
        for ((i, c) in text.withIndex()) {
            val l = c.toString().lowercase()
            builder.append(l)
            repeat(l.length) { map.add(i) }
        }
        return Line(path, row, text, builder.toString(), map.toIntArray())
    }

    /**
     * The lines containing [query], ignoring case (upstream: `toLowerCase` and `indexOf`), in index order,
     * at most [limit] of them. Upstream stops listing after 1000 results in total (files, symbols and lines
     * together), so pass 1000 minus the entries listed before the lines. An empty query matches nothing.
     */
    fun find(query: String, limit: Int = MAX_RESULTS): List<TextLineMatch> {
        if (query.isEmpty() || limit <= 0) return emptyList()
        val needle = query.lowercase()
        val result = ArrayList<TextLineMatch>()
        for (line in lines) {
            val index = line.lower.indexOf(needle)
            if (index < 0) continue
            result.add(TextLineMatch(line.path, line.row, line.text, line.lowerToText?.get(index) ?: index))
            if (result.size >= limit) break
        }
        return result
    }

    companion object {
        /** Upstream's cap on the number of results "Go to anything" lists. */
        const val MAX_RESULTS = 1000
    }
}
