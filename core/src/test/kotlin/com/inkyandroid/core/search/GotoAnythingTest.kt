package com.inkyandroid.core.search

import com.inkyandroid.core.symbols.FileSymbols
import com.inkyandroid.core.symbols.InkFileSymbols
import com.inkyandroid.core.syntax.InkTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Go to anything" (`goto.js`): candidates, the query's special entries, text search. */
class GotoAnythingTest {

    private val tokenizer = InkTokenizer()
    private val mainText = """
        === intro ===
        = one
        * (pick) Pick
        - (after) After
        = two
        === outro ===
        The İstanbul end.
    """.trimIndent()
    private val otherText = "=== side ===\n= inner\nSide text with Pick in it.\r\nLast line"
    private val files = listOf(
        FileSymbols("main.ink", InkFileSymbols.parse(mainText, tokenizer)),
        FileSymbols("parts/other.ink", InkFileSymbols.parse(otherText, tokenizer)),
    )

    @Test
    fun `files with their names and folders, in project order`() {
        val candidates = GotoCandidates.collect(files, "main.ink")
        assertEquals(listOf(GotoFile("main.ink", "main.ink", ""), GotoFile("parts/other.ink", "other.ink", "parts")), candidates.files)
    }

    @Test
    fun `every symbol of the active file in upstream's order, only the top level of the others`() {
        val candidates = GotoCandidates.collect(files, "main.ink")
        // a level first, then each symbol's descendants in turn
        assertEquals(listOf("intro", "outro", "one", "two", "pick", "after"), candidates.activeFileSymbols.map { it.name })
        assertEquals(listOf("", "", "intro.", "intro.", "intro.one.", "intro.one."), candidates.activeFileSymbols.map { it.ancestors })
        assertEquals(listOf("side"), candidates.otherSymbols.map { it.name })
        assertEquals("parts/other.ink", candidates.otherSymbols.single().path)
        val pick = candidates.activeFileSymbols[4]
        assertEquals(2, pick.row)
        assertEquals(3, pick.column)

        val fromOther = GotoCandidates.collect(files, "parts/other.ink")
        assertEquals(listOf("side", "inner"), fromOther.activeFileSymbols.map { it.name })
        assertEquals(listOf("intro", "outro"), fromOther.otherSymbols.map { it.name })
    }

    @Test
    fun `line numbers and runtime paths`() {
        assertEquals(41, GotoQuery.lineRow("42"))
        assertEquals(41, GotoQuery.lineRow("  42 "))
        assertEquals(-1, GotoQuery.lineRow("0"))
        assertEquals(Int.MAX_VALUE - 1, GotoQuery.lineRow("99999999999999999999"))
        assertNull(GotoQuery.lineRow("4 2"))
        assertNull(GotoQuery.lineRow("x42"))
        assertEquals("knot.0.c-1", GotoQuery.runtimePath("knot.0.c-1"))
        assertEquals("a.b", GotoQuery.runtimePath("a.b"))
        assertNull(GotoQuery.runtimePath("knot"))
        assertNull(GotoQuery.runtimePath("a..b"))
        assertNull(GotoQuery.runtimePath("a.b "))
    }

    @Test
    fun `text search ignores case, lists the active file first and stops at the limit`() {
        val index = TextSearchIndex(files.map { SearchableFile(it.path, if (it.path == "main.ink") mainText else otherText) }, "parts/other.ink")
        val matches = index.find("pick")
        assertEquals(listOf("parts/other.ink" to 2, "main.ink" to 2), matches.map { it.path to it.row })
        assertEquals("Side text with Pick in it.", matches[0].line)
        assertEquals(15, matches[0].matchStart)
        assertEquals(1, index.find("pick", limit = 1).size)
        assertEquals(emptyList<TextLineMatch>(), index.find(""))
        assertEquals(3, index.find("Last line").single().row)
    }

    @Test
    fun `positions survive lower-casing that changes the length`() {
        val index = TextSearchIndex(listOf(SearchableFile("main.ink", mainText)), "main.ink")
        val match = index.find("end").single()
        assertEquals("The İstanbul end.", match.line)
        assertEquals(13, match.matchStart)
        assertEquals(5, index.find("stanbul").single().matchStart)
        // as in JavaScript, "İ" lower-cases to "i" and a combining dot, so "istanbul" is not in it
        assertEquals(emptyList<TextLineMatch>(), index.find("istanbul"))
    }
}
