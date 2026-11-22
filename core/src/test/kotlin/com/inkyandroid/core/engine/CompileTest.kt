package com.inkyandroid.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompileTest {
    private val engine = InkEngine.Default

    private fun failed(sources: InkSources): List<InkIssue> {
        val result = engine.compile(sources)
        assertTrue("expected errors, got $result ${result.issues}", result is CompileResult.Failed)
        return result.issues
    }

    @Test
    fun aProjectOfSeveralFilesCompilesToJson() {
        val story = Stories.compiled(Stories.twoFiles)
        assertTrue(story.toJson().startsWith("{\"inkVersion\":21,"))
        assertEquals(Stories.twoFiles, story.sources)
        // built once
        assertTrue(story.toJson() === story.toJson())
    }

    @Test
    fun parseErrorsOfEveryFileAreAllReportedInOrder() {
        val sources = InkSources(
            "main.ink",
            mapOf(
                "main.ink" to "INCLUDE sub/part.ink\nINCLUDE missing.ink\nHello\n{ x:\n- 1: one\n",
                "sub/part.ink" to "INCLUDE sub/nested.ink\n== part_knot ==\n~ temp y = \n-> END\n",
                "sub/nested.ink" to "INCLUDE sub/part.ink\n== nested_knot ==\nnested {\n-> END\n",
            ),
        )
        assertEquals(
            listOf(
                InkIssue(IssueKind.Error, "sub/nested.ink", 1, "Recursive INCLUDE detected: 'sub/part.ink' is already open."),
                InkIssue(IssueKind.Error, "sub/nested.ink", 3, "Expected some kind of logic, conditional or sequence within braces: { ... } but saw end of line"),
                InkIssue(IssueKind.Error, "sub/nested.ink", 3, "Expected end of line but saw '{'"),
                InkIssue(IssueKind.Error, "sub/part.ink", 3, "Expected value expression to be assigned but saw ' '"),
                InkIssue(IssueKind.Error, "main.ink", 2, "Failed to load: 'missing.ink'"),
                InkIssue(IssueKind.Error, "main.ink", 6, "Expected closing brace '}' for inline logic but saw end of line"),
            ),
            failed(sources),
        )
    }

    @Test
    fun todosWarningsAndErrorsOfMeaningComeOnlyOnceParsingIsClean() {
        val sources = InkSources(
            "main.ink",
            mapOf(
                "main.ink" to """
                    |INCLUDE sub/part.ink
                    |VAR x = 1
                    |TODO: write the intro
                    |Hello {x}
                    |-> knot_a
                    |== knot_a ==
                    |{unknown_var}
                    |* choice -> nowhere
                    |* other
                    |  -> END
                    |== knot_c
                    |{ x:
                    |- 1: one
                    |}
                    |
                """.trimMargin(),
                "sub/part.ink" to "== part_knot ==\nTODO: part todo\ntext -> bad_target\n-> END\n",
            ),
        )
        assertEquals(
            listOf(
                // in the compiler's order; inklecate -j lists TODOs, then warnings, then errors
                InkIssue(IssueKind.Todo, "main.ink", 3, "write the intro"),
                InkIssue(IssueKind.Warning, "main.ink", 12, "Apparent loose end exists where the flow runs out. Do you need a '-> DONE' statement, choice or divert?"),
                InkIssue(IssueKind.Todo, "sub/part.ink", 2, "part todo"),
                InkIssue(IssueKind.Error, "main.ink", 7, "Unresolved variable: unknown_var"),
                InkIssue(IssueKind.Error, "main.ink", 8, "Divert target not found: '-> nowhere'"),
                InkIssue(IssueKind.Error, "sub/part.ink", 3, "Divert target not found: '-> bad_target'"),
            ),
            failed(sources),
        )
    }

    @Test
    fun todosAndWarningsDoNotStopACompile() {
        val result = engine.compile(Stories.single("TODO: later\nHello.\n-> END"))
        assertTrue(result is CompileResult.Success)
        assertEquals(listOf(InkIssue(IssueKind.Todo, "main.ink", 1, "later")), result.issues)
    }

    @Test
    fun includesResolveAgainstTheMainFilesFolderAlsoInsideIncludedFiles() {
        val sources = InkSources(
            mainPath = "story/main.ink",
            texts = mapOf(
                "story/main.ink" to "INCLUDE ./chapters/one.ink\n-> one",
                // written relative to story/, also inside chapters/one.ink
                "story/chapters/one.ink" to "INCLUDE chapters\\two.ink\n== one ==\nOne.\n-> two",
                "story/chapters/two.ink" to "== two ==\nTwo {broken\n-> END",
            ),
        )
        assertEquals(
            listOf(
                InkIssue(IssueKind.Error, "story/chapters/two.ink", 2, "Expected some kind of logic, conditional or sequence within braces: { ... } but saw 'broken'"),
                InkIssue(IssueKind.Error, "story/chapters/two.ink", 2, "Expected end of line but saw '{broken'"),
            ),
            failed(sources),
        )
    }

    @Test
    fun anIncludeThatLeavesTheProjectIsAMissingFile() {
        assertEquals(
            listOf(InkIssue(IssueKind.Error, "main.ink", 1, "Failed to load: '../outside.ink'")),
            failed(InkSources("main.ink", mapOf("main.ink" to "INCLUDE ../outside.ink\nHello.\n", "outside.ink" to "x"))),
        )
    }

    @Test
    fun aFileThatNothingIncludesIsNotCompiled() {
        val sources = InkSources("main.ink", mapOf("main.ink" to "Hello.\n", "spare.ink" to "{broken\n"))
        assertTrue(engine.compile(sources) is CompileResult.Success)
    }

    @Test
    fun aMissingMainFileIsAnErrorNotAnException() {
        val issue = failed(InkSources("main.ink", mapOf("other.ink" to "Hello.\n"))).single()
        assertEquals(IssueKind.Error, issue.kind)
        assertNull(issue.file)
    }

    @Test
    fun allVisitsAreCountedAsInkyAsksInklecateTo() {
        // Nothing reads k's visit count, so only -c makes the knot count its visits (flag bit 1).
        assertTrue(Stories.compiled(Stories.single("-> k\n== k ==\nText\n-> END")).toJson().contains("{\"#f\":1}"))
    }

    @Test
    fun nestingDeeperThanTheParsersStackIsAnErrorNotACrash() {
        // 23 nested inline conditionals compile, 24 overflow the parser's fixed stack of 200 states.
        val issue = failed(Stories.single("{true: ".repeat(24) + "x" + "}".repeat(24))).single()
        assertEquals(IssueKind.Error, issue.kind)
        assertNull(issue.line)
        assertTrue(engine.compile(Stories.single("{true: ".repeat(23) + "x" + "}".repeat(23))) is CompileResult.Success)
    }

    @Test
    fun largeAndOddInputsCompile() {
        val prose = "The quick brown fox jumps over the lazy dog, again and again and again.\n".repeat(14_000)
        assertTrue(engine.compile(Stories.single(prose)) is CompileResult.Success)
        val knots = (0 until 2_000).joinToString("\n", prefix = "-> k0\n") { "== k$it ==\nKnot $it.\n* [On] -> k${(it + 1) % 2_000}" }
        assertTrue(engine.compile(Stories.single(knots)) is CompileResult.Success)
        for (odd in listOf("", "   \n\t\n", "\u0000", "\uD83D", "\uFEFFHello.")) {
            assertTrue("<$odd>", engine.compile(InkSources("main.ink", mapOf("main.ink" to odd))) !is CompileResult.Crashed)
        }
    }

    @Test
    fun statisticsCountWhatInklecateCounts() {
        // the numbers the reference inklecate 1.2.0 prints for these three files (-s -j)
        assertEquals(
            InkStatistics(words = 32, knots = 3, stitches = 0, functions = 1, choices = 2, gathers = 0, diverts = 5),
            engine.statistics(Stories.twoFiles),
        )
    }

    @Test
    fun statisticsAreThereForInkWithErrors() {
        val stats = engine.statistics(Stories.single("One two three.\n== knot ==\n{unknown} four\n-> nowhere"))
        assertNotNull(stats)
        assertEquals(4, stats!!.words)
        assertEquals(1, stats.knots)
        assertNull(engine.statistics(InkSources("main.ink", emptyMap())))
    }
}
