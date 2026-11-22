package com.inkyandroid.ink

import com.inkyandroid.core.snippets.InkSnippets
import com.inkyandroid.core.snippets.SnippetNode
import com.inkyandroid.core.syntax.InkLines
import com.inkyandroid.core.syntax.InkTokenizer
import desktopkit.editor.TokenSink
import desktopkit.theme.SyntaxRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ink editor's colours: upstream's token types to the kit's roles, and the tokenizer the editor runs. */
class InkSyntaxTest {

    private val tokenizer = InkTokenizer()

    /** The roles of [line] (first line of a document), as (text, role) for every token that has one. */
    private fun roles(line: String, with: InkTokenizer = tokenizer): List<Pair<String, SyntaxRole>> =
        with.tokenizeLine(line).tokens.mapNotNull { token -> inkSyntaxRole(token.type)?.let { token.text(line) to it } }

    @Test
    fun aKnotsLineIsASectionTitleAndFunctionIsAKeyword() {
        assertEquals(listOf("==" to SyntaxRole.Heading, " " to SyntaxRole.Heading, "start" to SyntaxRole.Heading, " " to SyntaxRole.Heading, "==" to SyntaxRole.Heading), roles("== start =="))
        val function = roles("=== function add(a, b) ===")
        assertTrue(function.contains("function" to SyntaxRole.Keyword))
        assertTrue(function.contains("add" to SyntaxRole.Heading))
        assertTrue(function.contains("(a, b)" to SyntaxRole.Name))
        assertTrue(roles("= scene").contains("scene" to SyntaxRole.Heading))
    }

    @Test
    fun divertTargetsAndIncludePathsAreLinksAndTheirArrowsKeywords() {
        val divert = roles("Go on -> chapter.scene")
        assertTrue(divert.contains("->" to SyntaxRole.Keyword))
        assertTrue(divert.any { it.second == SyntaxRole.Link && it.first.trim() == "chapter.scene" })
        val include = roles("INCLUDE chapters/one.ink")
        assertEquals(listOf("INCLUDE" to SyntaxRole.Keyword, "chapters/one.ink" to SyntaxRole.Link), include.filter { it.first.isNotBlank() })
        assertTrue(roles("-> END").contains("END" to SyntaxRole.Keyword))
    }

    @Test
    fun proseIsPlainAndTheMarksThatSteerTheReaderAreLoud() {
        assertEquals(emptyList<Pair<String, SyntaxRole>>(), roles("Once upon a time..."))
        val choice = roles("* [Open the door] She opened it.")
        assertEquals("*", choice.first().first.trim())
        assertEquals(SyntaxRole.Keyword, choice.first().second)
        // the text of the choice itself is prose
        assertTrue(choice.none { it.first.contains("Open the door") })
        assertTrue(roles("- They lived happily ever after.").first().let { it.first.trim() == "-" && it.second == SyntaxRole.Keyword })
        assertTrue(roles("Hello<>").contains("<>" to SyntaxRole.Keyword))
    }

    @Test
    fun commentsTagsLogicAndDeclarations() {
        assertTrue(roles("// a note").all { it.second == SyntaxRole.Comment })
        assertTrue(roles("Text # mood: dark").contains("# mood: dark" to SyntaxRole.Tag))
        val variable = roles("VAR gold = 5")
        assertTrue(variable.contains("VAR" to SyntaxRole.Keyword))
        assertTrue(variable.contains("gold" to SyntaxRole.Name))
        val logic = roles("~ gold = gold + 1")
        assertTrue(logic.isNotEmpty() && logic.all { it.second != SyntaxRole.Keyword })
        val inline = roles("You have {gold} coins.")
        assertTrue(inline.contains("{" to SyntaxRole.Punctuation))
        assertTrue(inline.contains("gold" to SyntaxRole.Name))
    }

    @Test
    fun theProjectsInstructionLinesAreDirectives() {
        val prefixed = InkTokenizer(">>>")
        assertTrue(roles(">>> CAMERA: wide", prefixed).isNotEmpty())
        assertTrue(roles(">>> CAMERA: wide", prefixed).all { it.second == SyntaxRole.Meta })
        assertTrue(roles(">>> CAMERA: wide").none { it.second == SyntaxRole.Meta })
    }

    @Test
    fun aTodoStandsOut() {
        val todo = roles("TODO: write the ending")
        assertTrue(todo.contains("TODO" to SyntaxRole.Keyword))
        assertTrue(todo.any { it.second == SyntaxRole.Emphasis })
    }

    /**
     * Every token type the bundled stories produce has a decided role: the types that stay plain
     * are exactly the prose and the white space upstream leaves uncoloured.
     */
    @Test
    fun everyTokenTypeOfTheBundledStoriesIsDecided() {
        val types = HashSet<String>()
        stories().forEach { text -> tokenizer.tokenize(text).forEach { line -> line.tokens.forEach { types += it.type } } }
        val plain = types.filter { inkSyntaxRole(it) == null }.toSet()
        val expectedPlain = setOf(
            "", "text", "escape", "choice", "choice.weaveInsideBrackets", "gather", "gather.innerContent", "divert", "include",
            "external", "logic.inline.innerContent", "logic.sequence.innerContent", "logic.multiline.innerContent", "logic.multiline.branch",
        )
        assertTrue("plain types not expected: ${plain - expectedPlain}", (plain - expectedPlain).isEmpty())
        assertTrue("the stories cover the important types", types.containsAll(listOf("divert.target", "flow.knot.declaration.name", "choice.bullets", "tag")))
    }

    private fun stories(): List<String> {
        val full = InkSnippets.builtIn.filterIsInstance<SnippetNode.Category>().first { it.id == "full_stories" }
        return full.children.filterIsInstance<SnippetNode.Snippet>().map { it.ink }
    }

    @Test
    fun theEditorsTokenizerCarriesTheStateAcrossLinesAsItsIdMovedUpByOne() {
        val editor = InkEditorTokenizer(tokenizer)
        assertSame(tokenizer.initialState, editor.stateOf(0))
        val lines = listOf("/* a comment", "that goes on", "*/ and ends")
        var state = 0
        val ends = lines.map { line ->
            state = editor.tokenizeLine(line, state, sink())
            state
        }
        val expected = tokenizer.tokenizeLines(lines.map { it })
        ends.forEachIndexed { index, end -> assertSame(expected[index].endState, editor.stateOf(end)) }
        assertTrue("never the editor's reserved value", ends.none { it == Int.MIN_VALUE })
    }

    /** The kit keeps its sink to itself; the states are what this test is about, so any sink will do. */
    private fun sink(): TokenSink = TokenSink::class.java.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()

    @Test
    fun highlightSpansAreOffsetsIntoTheWholeTextWhateverTheLineBreaks() {
        val text = "== knot ==\r\n-> END\nText"
        val spans = InkHighlight.spans(text)
        val heading = spans.first { it.role == SyntaxRole.Heading && text.substring(it.start, it.end) == "knot" }
        assertEquals(3, heading.start)
        val end = spans.first { text.substring(it.start, it.end) == "END" }
        assertEquals(SyntaxRole.Keyword, end.role)
        assertEquals(InkLines.split(text)[0].length + 2 + 3, end.start)
        assertTrue(spans.zipWithNext().all { (a, b) -> a.end <= b.start })
    }

    @Test
    fun roleOfAnEmptyTypeIsPlain() {
        assertNull(inkSyntaxRole(""))
        assertNull(inkSyntaxRole("text"))
    }
}
