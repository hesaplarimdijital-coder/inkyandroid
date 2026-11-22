package com.inkyandroid.ui.workspace

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkyandroid.core.engine.InkIssue
import com.inkyandroid.core.engine.IssueKind
import com.inkyandroid.core.symbols.InkFileSymbols
import com.inkyandroid.core.syntax.InkTokenizer
import com.inkyandroid.session.TextPosition
import com.inkyandroid.ui.offsetOf
import desktopkit.editor.CodeEditorState
import desktopkit.editor.CodeIndent
import desktopkit.editor.MarkerSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The workspace's pure parts: what the panes make of the session's data. */
@RunWith(AndroidJUnit4::class)
class WorkspaceLogicTest {

    private fun issue(kind: IssueKind, file: String?, line: Int?, message: String = kind.name) = InkIssue(kind, file, line, message)

    @Test
    fun markersAreZeroBasedGravestFirstAndARuntimeWarningIsDrawnAsAnError() {
        val markers = markersOf(
            listOf(
                issue(IssueKind.Todo, "a.ink", 3),
                issue(IssueKind.Warning, "a.ink", 3),
                issue(IssueKind.RuntimeWarning, "a.ink", 1),
                issue(IssueKind.Error, "a.ink", 3),
                // no line: nothing to mark
                issue(IssueKind.Error, "a.ink", null),
                issue(IssueKind.Error, "a.ink", 0),
            ),
        )
        assertEquals(listOf(0, 2, 2, 2), markers.map { it.line })
        assertEquals(listOf(MarkerSeverity.Error, MarkerSeverity.Error, MarkerSeverity.Warning, MarkerSeverity.Info), markers.map { it.severity })
    }

    @Test
    fun theOutlineIsUpstreamsKnotsView() {
        val text = """
            Before any knot.
            == start ==
            = first
            * (label) A choice
            = first
            = second
            == function add(a, b) ==
            ~ return a + b
            EXTERNAL play(sound)
            == function play(sound) ==
            ~ return
        """.trimIndent()
        val rows = outlineRows(InkFileSymbols.parse(text, InkTokenizer()))
        val described = rows.map { row ->
            when (row) {
                is OutlineRow.Group -> "[" + row.kind.name + "]"
                is OutlineRow.Symbol -> (if (row.isStitch) "  " else "") + row.symbol.name
            }
        }
        // a stitch declared twice shows once, labels are not listed, functions and externals apart
        assertEquals(listOf("[Content]", "start", "  first", "  second", "[Functions]", "add", "[Externals]", "play"), described)
    }

    @Test
    fun issuesAreGroupedByFileInTheProjectsOrderAndCountedAsTheyAre() {
        val issues = listOf(
            issue(IssueKind.Warning, "b.ink", 2),
            issue(IssueKind.Error, "main.ink", 9),
            issue(IssueKind.Error, null, null, "Missing function binding"),
            issue(IssueKind.Todo, "main.ink", 1),
            issue(IssueKind.RuntimeWarning, "elsewhere.ink", 4),
        )
        val groups = groupIssues(issues, listOf("main.ink", "b.ink"))
        assertEquals(listOf("main.ink", "b.ink", "elsewhere.ink", null), groups.map { it.path })
        // upstream's order inside a file: errors before TODOs whatever their lines
        assertEquals(listOf(IssueKind.Error, IssueKind.Todo), groups.first().issues.map { it.kind })
        val counts = countIssues(issues)
        assertEquals(2, counts.errors)
        // a runtime warning counts as the warning it is (upstream counts it nowhere)
        assertEquals(2, counts.warnings)
        assertEquals(1, counts.todos)
    }

    @Test
    fun storyMarkupDrawsTheWebPlayersLittleHtmlAndShowsEverythingElseAsWritten() {
        val link = SpanStyle(color = Color.Unspecified)
        assertFalse(StoryMarkup.hasMarkup("a < b and c > d"))
        assertEquals("a < b and c > d", StoryMarkup.render("a < b and c > d", link).text)
        assertEquals("bold and plain", StoryMarkup.render("<b>bold</b> and plain", link).text)
        assertEquals("one\ntwo", StoryMarkup.render("one<br>two", link).text)
        // anything that is not one of the allowed tags is text
        assertEquals("<script>x</script> & y", StoryMarkup.render("<script>x</script> &amp; y", link).text)
        assertTrue(StoryMarkup.sanitize("<i>x</i> <blink>").contains("&lt;blink&gt;"))
    }

    @Test
    fun theSymbolKeysTypeAsAWriterExpects() {
        val editor = CodeEditorState("Hello")
        editor.select(5)
        editor.typeKey(INK_KEYS.first { it.label == "->" })
        assertEquals("Hello->", editor.text)
        // a pair with nothing selected: the cursor between its halves
        editor.typeKey(INK_KEYS.first { it.label == "{ }" })
        assertEquals("Hello->{}", editor.text)
        assertEquals(8, editor.cursor)
        // a pair around a selection
        editor.select(0, 5)
        editor.typeKey(INK_KEYS.first { it.label == "[ ]" })
        assertEquals("[Hello]->{}", editor.text)
        // Tab: spaces to the next stop of four
        val tab = CodeEditorState("ab")
        tab.select(2)
        tab.typeTab(CodeIndent.spaces(4), 4)
        assertEquals("ab  ", tab.text)
        // one undo step per key
        editor.undo()
        assertEquals("Hello->{}", editor.text)
    }

    @Test
    fun aPositionIsAnOffsetWhateverTheLineBreaks() {
        val text = "one\r\ntwo\rthree\nfour"
        assertEquals(0, offsetOf(text, TextPosition(0, 0)))
        assertEquals(5, offsetOf(text, TextPosition(1, 0)))
        assertEquals(9, offsetOf(text, TextPosition(2, 0)))
        assertEquals(17, offsetOf(text, TextPosition(3, 2)))
        // past the end of a line: the line's end; past the last line: the text's end
        assertEquals(3, offsetOf(text, TextPosition(0, 99)))
        assertEquals(text.length, offsetOf(text, TextPosition(9, 0)))
    }
}
