package com.inkyandroid.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** Every sample is a message the library (and the reference inklecate, identically) produced in a run. */
class IssueTextTest {
    private fun parse(raw: String, mainPath: String = "main.ink", fallback: IssueKind = IssueKind.Error) =
        IssueText(IncludePaths(mainPath)).parse(raw, fallback)

    @Test
    fun parserErrorInIncludedFile() = assertEquals(
        InkIssue(IssueKind.Error, "sub/nested.ink", 3, "Expected end of line but saw '{'"),
        parse("ERROR: 'sub/nested.ink' line 3: Expected end of line but saw '{'"),
    )

    @Test
    fun todoInMainFile() = assertEquals(
        InkIssue(IssueKind.Todo, "main.ink", 3, "write the intro"),
        parse("TODO: 'main.ink' line 3: write the intro"),
    )

    @Test
    fun warning() = assertEquals(
        InkIssue(IssueKind.Warning, "main.ink", 14, "Apparent loose end exists where the flow runs out. Do you need a '-> DONE' statement, choice or divert?"),
        parse("WARNING: 'main.ink' line 14: Apparent loose end exists where the flow runs out. Do you need a '-> DONE' statement, choice or divert?"),
    )

    @Test
    fun errorWithLineButNoFile() = assertEquals(
        InkIssue(IssueKind.Error, null, 1, "Unresolved variable: nope"),
        parse("ERROR: line 1: Unresolved variable: nope"),
    )

    @Test
    fun runtimeErrorWithLocation() = assertEquals(
        InkIssue(IssueKind.RuntimeError, "main.ink", 11, "Cannot perform operation '*' on String"),
        parse("RUNTIME ERROR: 'main.ink' line 11: Cannot perform operation '*' on String"),
    )

    @Test
    fun runtimeErrorWithPathOnly() = assertEquals(
        InkIssue(IssueKind.RuntimeError, null, null, "ran out of content. Do you need a '-> DONE' or '-> END'?", runtimePath = "knot.0.3"),
        parse("RUNTIME ERROR: (knot.0.3): ran out of content. Do you need a '-> DONE' or '-> END'?"),
    )

    @Test
    fun runtimeWarningWithoutLocation() = assertEquals(
        InkIssue(IssueKind.RuntimeWarning, null, null, "something odd"),
        parse("RUNTIME WARNING: something odd"),
    )

    @Test
    fun aQuotedWordAtTheStartOfTheMessageIsNotAFile() = assertEquals(
        InkIssue(IssueKind.Error, null, null, "'knot' is not a knot"),
        parse("ERROR: 'knot' is not a knot"),
    )

    @Test
    fun aFileNameWithAnApostrophe() = assertEquals(
        InkIssue(IssueKind.Error, "Barış's notes.ink", 2, "Divert target not found: '-> x'"),
        parse("ERROR: 'Barış's notes.ink' line 2: Divert target not found: '-> x'"),
    )

    @Test
    fun theFileNameAsWrittenIsResolvedAgainstTheMainFilesFolder() {
        assertEquals("story/sub/part.ink", parse("ERROR: './sub/part.ink' line 4: x", mainPath = "story/main.ink").file)
        assertEquals("sub/part.ink", parse("ERROR: 'sub\\other\\..\\part.ink' line 4: x").file)
        assertEquals("story/main.ink", parse("ERROR: 'main.ink' line 1: x", mainPath = "story/main.ink").file)
    }

    @Test
    fun aNameThatLeavesTheProjectIsKeptAsWritten() =
        assertEquals("../outside.ink", parse("ERROR: '../outside.ink' line 1: x").file)

    @Test
    fun anUnexpectedShapeKeepsTheWholeTextUnderTheFallbackKind() = assertEquals(
        InkIssue(IssueKind.RuntimeError, null, null, "Ink had 1 error"),
        parse("  Ink had 1 error\n", fallback = IssueKind.RuntimeError),
    )

    @Test
    fun aMessageOfSeveralLinesStaysWhole() = assertEquals(
        InkIssue(IssueKind.Error, "main.ink", 2, "first\nsecond"),
        parse("ERROR: 'main.ink' line 2: first\nsecond"),
    )

    @Test
    fun upstreamsOrderAndCounts() {
        val issues = listOf(
            InkIssue(IssueKind.Todo, "a.ink", 1, "todo"),
            InkIssue(IssueKind.RuntimeWarning, null, null, "rw"),
            InkIssue(IssueKind.Warning, "a.ink", 9, "w9"),
            InkIssue(IssueKind.Error, "b.ink", 7, "e7"),
            InkIssue(IssueKind.RuntimeError, "a.ink", 2, "re"),
            InkIssue(IssueKind.Error, "a.ink", 3, "e3"),
            InkIssue(IssueKind.Error, null, null, "e0"),
        )
        assertEquals(listOf("e0", "e3", "e7", "re", "w9", "rw", "todo"), InkIssues.sorted(issues).map { it.message })
        assertEquals(IssueCounts(errors = 4, warnings = 1, todos = 1), InkIssues.counts(issues))
        assertEquals(true, InkIssues.counts(listOf(issues[1])).isEmpty)
    }
}
