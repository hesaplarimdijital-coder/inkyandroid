package com.inkyandroid.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaySessionTest {
    private fun loc(file: String, line: Int) = SourceLocation(file, line)

    private fun texts(turn: StoryTurn) = turn.lines.map { it.text }

    @Test
    fun globalTagsMustComeBeforeAnyInclude() {
        // the same rule as in the reference runtime: the root's first content must be the tags
        val included = "a.ink" to "== a ==\n-> END\n"
        val after = InkSources("main.ink", mapOf("main.ink" to "INCLUDE a.ink\n# title: T\nHello.\n", included))
        assertEquals(emptyList<String>(), Stories.play(after).globalTags)
        val before = InkSources("main.ink", mapOf("main.ink" to "# title: T\nINCLUDE a.ink\nHello.\n", included))
        assertEquals(listOf("title: T"), Stories.play(before).globalTags)
    }

    @Test
    fun linesTagsChoicesAndTheEnd() {
        val play = Stories.play(Stories.twoFiles)
        assertEquals(listOf("author: Barış"), play.globalTags)
        assertTrue(play.choices.isEmpty())
        assertFalse(play.ended)

        val first = play.continueToChoice()
        // a tag line above an INCLUDE comes out as an empty line of its own, as from inklecate
        assertEquals(listOf("\n", "Line one.\n", "Value of x is 5 and more.\n", "A first sequence.\n"), texts(first))
        assertEquals(listOf(listOf("author: Barış"), listOf("tag1"), emptyList(), emptyList()), first.lines.map { it.tags })
        assertEquals(listOf(StoryChoice(0, "Go to the part"), StoryChoice(1, "Choice text", listOf("ctag"))), first.choices)
        assertEquals(first.choices, play.choices)
        assertFalse(first.ended)
        assertNull(first.stop)

        assertTrue(play.choose(0))
        val second = play.continueToChoice()
        assertEquals(listOf("Part line one.\n", "Loudhey said the part.\n"), texts(second))
        assertTrue(second.ended)
        assertTrue(play.ended)
        assertTrue(second.choices.isEmpty())
        assertTrue(second.allIssues.isEmpty())
    }

    @Test
    fun continuingWithoutAChoiceChangesNothing() {
        val play = Stories.play(Stories.twoFiles)
        val first = play.continueToChoice()
        val again = play.continueToChoice()
        assertTrue(again.lines.isEmpty())
        assertEquals(first.choices, again.choices)
        assertFalse(again.ended)
    }

    @Test
    fun aChoiceThatIsNotThereIsRefused() {
        val play = Stories.play(Stories.twoFiles)
        assertFalse("before the first turn", play.choose(0))
        play.continueToChoice()
        assertFalse(play.choose(2))
        assertFalse(play.choose(-1))
        assertEquals(2, play.choices.size)
        assertTrue(play.choose(1))
        assertEquals(listOf("Choice text\n", "After choice.\n"), texts(play.continueToChoice()))
        assertFalse("after the end", play.choose(0))
        assertTrue(play.continueToChoice().let { it.ended && it.lines.isEmpty() })
    }

    @Test
    fun everyLineKnowsItsFileAndLine() {
        val play = Stories.play(Stories.twoFiles)
        val first = play.continueToChoice()
        assertEquals(listOf(loc("main.ink", 1), loc("main.ink", 4), loc("main.ink", 5), loc("main.ink", 8)), first.lines.map { it.source })
        play.choose(0)
        val second = play.continueToChoice()
        assertEquals(listOf(loc("sub/part.ink", 3), loc("sub/helpers.ink", 2)), second.lines.map { it.source })
    }

    @Test
    fun theCharactersOfOneLineKeepTheirOwnSource() {
        val play = Stories.play(Stories.twoFiles)
        play.continueToChoice()
        play.choose(0)
        val line = play.continueToChoice().lines[1]
        assertEquals("Loudhey said the part.\n", line.text)
        // "Loud" is printed inside the function in helpers.ink; " said the part." by part.ink line 4.
        // "hey" is the returned value, made at runtime: it takes the place before it.
        assertEquals(loc("sub/helpers.ink", 2), line.sourceAt(0))
        assertEquals(loc("sub/part.ink", 4), line.sourceAt(line.text.indexOf("said")))
        assertEquals(
            listOf(SourceSpan(0, 7, loc("sub/helpers.ink", 2)), SourceSpan(7, 23, loc("sub/part.ink", 4))),
            line.spans,
        )
        // outside the text: the line's own place
        assertEquals(line.source, line.sourceAt(500))
        assertEquals(line.source, line.sourceAt(-1))
    }

    @Test
    fun spansCoverTheTextThroughGlueAndCollapsedBlanks() {
        val play = Stories.play("One   <>\ntwo    three <> \n-> more\n== more ==\nfour.\n-> END")
        val line = play.continueToChoice().lines.single()
        assertEquals("One two three four.\n", line.text)
        assertEquals(0, line.spans.first().start)
        assertEquals(line.text.length, line.spans.last().end)
        for ((a, b) in line.spans.zipWithNext()) assertEquals(a.end, b.start)
        assertEquals(loc("main.ink", 1), line.sourceAt(0))
        assertEquals(loc("main.ink", 2), line.sourceAt(line.text.indexOf("three")))
        assertEquals(loc("main.ink", 5), line.sourceAt(line.text.indexOf("four")))
    }

    @Test
    fun aRuntimeErrorIsReportedOnItsLineAndEndsTheStory() {
        val turn = Stories.play("VAR s = \"a\"\nBefore.\n~ s = s * 2\nAfter.").continueToChoice()
        // the line in which the error happened comes back empty
        assertEquals(listOf("Before.\n", ""), texts(turn))
        assertEquals(
            listOf(InkIssue(IssueKind.RuntimeError, "main.ink", 3, "Cannot perform operation '*' on String")),
            turn.lines[1].issues,
        )
        assertEquals(turn.lines[1].issues, turn.allIssues)
        assertTrue(turn.ended)
        assertNull(turn.stop)
    }

    @Test
    fun runningOutOfContentIsARuntimeError() {
        val turn = Stories.play("-> knot\n== knot ==\nText").continueToChoice()
        // the compiler warned; at runtime, falling off the end is an error
        val issue = turn.allIssues.single()
        assertEquals(IssueKind.RuntimeError, issue.kind)
        assertTrue(issue.message, issue.message.startsWith("ran out of content"))
        assertTrue(turn.ended)
    }

    @Test
    fun anExternalFunctionFallsBackToItsInkDefinition() {
        val play = Stories.play("EXTERNAL greet(name)\n{greet(\"Bahar\")}\n-> END\n== function greet(name) ==\n~ return \"Hi {name}\"")
        assertEquals(listOf("Hi Bahar\n"), texts(play.continueToChoice()))
    }

    @Test
    fun anExternalWithoutAFallbackIsReportedAndEndsTheStory() {
        val play = Stories.play("EXTERNAL greet(name)\n{greet(\"Bahar\")}\n-> END")
        val turn = play.continueToChoice()
        assertEquals(
            listOf(InkIssue(IssueKind.Error, null, null, "Missing function binding for external: 'greet' , and no fallback ink function found.")),
            turn.issues,
        )
        assertTrue(turn.lines.isEmpty())
        assertTrue(turn.ended)
        // the story's own refusal, not a failure of the engine: inklecate shows the error and "End of story"
        assertNull(turn.stop)
        assertTrue(play.ended)
        assertTrue(play.continueToChoice().let { it.ended && it.allIssues.isEmpty() })
    }

    @Test
    fun aLoopWithOutputStopsAtTheLineLimit() {
        val play = Stories.play("- (top) Again.\n-> top", PlayLimits(maxLinesPerTurn = 200))
        val turn = play.continueToChoice()
        assertEquals(StopReason.LineLimit, turn.stop)
        assertEquals(200, turn.lines.size)
        assertTrue(turn.ended)
        assertTrue(play.ended)
        assertTrue(play.choices.isEmpty())
    }

    @Test
    fun aLoopWithoutOutputStopsAtTheTimeLimit() {
        val play = Stories.play("VAR n = 0\n- (top)\n~ n++\n-> top", PlayLimits(turnBudgetMillis = 300))
        val started = System.nanoTime()
        val turn = play.continueToChoice()
        assertEquals(StopReason.TimeLimit, turn.stop)
        assertTrue(turn.ended)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
    }

    @Test
    fun anIntegerDivisionByZeroEscapesTheRuntimeButNotTheSession() {
        val play = Stories.play("VAR z = 0\nBefore.\n* [Go]\n- {1 / z}\nAfter.")
        assertEquals(listOf("Before.\n"), texts(play.continueToChoice()))
        play.choose(0)
        val turn = play.continueToChoice()
        assertEquals(StopReason.EngineFailure, turn.stop)
        assertEquals(listOf(InkIssue(IssueKind.RuntimeError, null, null, "/ by zero")), turn.issues)
        assertTrue(turn.ended)
        assertTrue(play.ended)
        assertTrue(play.choices.isEmpty())
        assertFalse(play.choose(0))
        // still answers
        assertEquals(Evaluation.Value("0"), play.evaluate("z"))
    }

    @Test
    fun aSavedStateBringsThePlaythroughBack() {
        val play = Stories.play(Stories.twoFiles)
        play.continueToChoice()
        val saved = play.saveState()
        play.choose(1)
        assertEquals(listOf("Choice text\n", "After choice.\n"), texts(play.continueToChoice()))
        assertTrue(play.ended)

        assertTrue(play.loadState(saved))
        assertFalse(play.ended)
        assertEquals(2, play.choices.size)
        play.choose(0)
        assertEquals("Part line one.\n", play.continueToChoice().lines.first().text)
        assertEquals(Evaluation.Value("1"), play.evaluate("part_knot"))
        assertEquals(Evaluation.Value("5"), play.evaluate("x"))
    }

    @Test
    fun aStateThatCannotBeReadLeavesThePlaythroughAsItWas() {
        val play = Stories.play(Stories.twoFiles)
        play.continueToChoice()
        val before = play.saveState()
        assertFalse(play.loadState("not json"))
        assertFalse(play.loadState("{}"))
        assertEquals(before, play.saveState())
        assertEquals(2, play.choices.size)
    }

    @Test
    fun theSameSeedGivesTheSameStory() {
        val ink = "- (top) {~one|two|three|four|five|six|seven} {RANDOM(1, 1000)}\n+ [Again] -> top"
        fun run(seed: Int?): List<String> {
            val play = Stories.play(ink, seed = seed)
            return (1..12).flatMap { texts(play.continueToChoice()).also { play.choose(0) } }
        }
        assertEquals(run(7), run(7))
        assertNotEquals(run(7), run(8))
    }

    @Test
    fun runtimePathsAreLocated() {
        val play = Stories.play(Stories.twoFiles)
        play.continueToChoice()
        assertEquals(loc("main.ink", 7), play.locate("knot_a"))
        assertEquals(loc("sub/part.ink", 2), play.locate("part_knot"))
        assertEquals(loc("sub/part.ink", 3), play.locate("part_knot.0"))
        assertNull(play.locate(""))
        assertNull(play.locate("no_such_knot.12.c-3"))
        assertNull(play.locate("((("))
    }

    @Test
    fun twoPlaythroughsOfOneStoryDoNotDisturbEachOther() {
        val story = Stories.compiled(Stories.twoFiles)
        val one = story.play()
        val two = story.play()
        one.continueToChoice()
        one.choose(0)
        assertEquals(listOf("Part line one.\n", "Loudhey said the part.\n"), texts(one.continueToChoice()))
        assertEquals(4, two.continueToChoice().lines.size)
        two.choose(1)
        assertEquals(listOf("Choice text\n", "After choice.\n"), texts(two.continueToChoice()))
    }
}
