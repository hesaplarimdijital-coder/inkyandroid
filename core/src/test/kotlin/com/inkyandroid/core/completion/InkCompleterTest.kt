package com.inkyandroid.core.completion

import com.inkyandroid.core.symbols.InkFileSymbols
import com.inkyandroid.core.syntax.InkTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upstream's completion: Ace's live auto-completion as upstream patched it (prefix match, case-sensitive,
 * order kept) over the mode's keywords and `inkCompleter.js`'s suggestions by context.
 */
class InkCompleterTest {

    private val tokenizer = InkTokenizer()

    private val main = InkFileSymbols.parse(
        """
        VAR health = 10
        VAR hero_name = "Ann"
        === knot_one ===
        The hero walked here, then went home.
        = knife_fight
        -> knot_two
        === knot_two ===
        Heavy weather.
        """.trimIndent(),
        tokenizer,
    )
    private val other = InkFileSymbols.parse("=== hello_world ===\nHello there, helpful friend.\n=== VAR ===\n", tokenizer)
    private val files = listOf(main, other)

    private fun complete(line: String, column: Int = line.length, explicit: Boolean = false): CompletionResult =
        InkCompleter.complete(line, tokenizer.tokenizeLine(line), column, files, explicit)

    private fun values(result: CompletionResult) = result.items.map { it.value }

    @Test
    fun `the prefix is Ace's identifier before the cursor`() {
        assertEquals("kn", InkCompleter.prefixAt("-> kn", 5))
        assertEquals("kn", InkCompleter.prefixAt("->kn", 4))
        assertEquals("a-b", InkCompleter.prefixAt("x a-b", 5))
        assertEquals("y", InkCompleter.prefixAt("x.y", 3))
        assertEquals("çoc", InkCompleter.prefixAt("-> çoc", 6))
        assertEquals("", InkCompleter.prefixAt("-> ", 3))
        assertEquals("ab", InkCompleter.prefixAt("abc", 2))
    }

    @Test
    fun `in a divert only divert targets, from every file, in project order`() {
        val result = complete("-> kn")
        assertEquals("kn", result.prefix)
        assertEquals(3, result.replaceStart)
        assertEquals(listOf("knot_one", "knife_fight", "knot_two"), values(result))
        assertTrue(result.items.all { it.kind == CompletionKind.DivertTarget })
        assertEquals(listOf("hello_world"), values(complete("-> he")))
    }

    @Test
    fun `in logic divert targets then variables then vocabulary`() {
        val result = complete("~ x = he")
        assertEquals(listOf("hello_world", "health", "hero_name", "hero", "here", "helpful"), values(result))
        assertEquals(
            listOf(CompletionKind.DivertTarget) + List(2) { CompletionKind.Variable } + List(3) { CompletionKind.Vocabulary },
            result.items.map { it.kind },
        )
        assertEquals(values(result), values(complete("Text {he")))
    }

    @Test
    fun `in prose only vocabulary, and matching is case-sensitive`() {
        assertEquals(listOf("hero", "here", "helpful"), values(complete("Then he")))
        assertEquals(listOf("Heavy", "Hello"), values(complete("Then He")))
    }

    @Test
    fun `keywords come first, consecutive duplicates go, and an exact sole match closes the popup`() {
        // keyword VAR, then the divert target VAR: one entry
        val result = complete("-> VA")
        assertEquals(listOf("VAR"), values(result))
        assertEquals(CompletionKind.Keyword, result.items.single().kind)
        assertEquals(listOf("LIST", "LIST_ALL", "LIST_COUNT", "LIST_INVERT", "LIST_MAX", "LIST_MIN", "LIST_RANGE", "LIST_VALUE", "LIST_RANDOM"), values(complete("LI")))
        assertEquals(emptyList<String>(), values(complete("Then TURNS_SINCE")))
        assertEquals(emptyList<String>(), values(complete("Then zzz")))
    }

    @Test
    fun `nothing without a prefix unless asked for`() {
        assertEquals(CompletionResult("", 3, emptyList()), complete("-> "))
        // after "-> " the cursor is in the text token after the arrow: prose
        val explicit = complete("-> ", explicit = true)
        val vocabulary = listOf("The", "hero", "walked", "here", "then", "went", "home", "Heavy", "weather", "Hello", "there", "helpful", "friend")
        assertEquals(InkCompleter.keywords + vocabulary, values(explicit))
    }

    @Test
    fun `the cursor in the middle of a word completes the part before it`() {
        val result = complete("-> knot_one", column = 5)
        assertEquals("kn", result.prefix)
        assertEquals(listOf("knot_one", "knife_fight", "knot_two"), values(result))
    }
}
