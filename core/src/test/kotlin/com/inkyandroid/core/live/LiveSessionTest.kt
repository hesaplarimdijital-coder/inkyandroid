package com.inkyandroid.core.live

import com.inkyandroid.core.engine.InkIssue
import com.inkyandroid.core.engine.IssueKind
import com.inkyandroid.core.engine.PlayLimits
import com.inkyandroid.core.engine.SourceLocation
import com.inkyandroid.core.engine.StopReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live session's state machine, on virtual time: when it compiles, what it replays, what it publishes. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionTest {

    // --- loading and the pause before a recompile ------------------------------------------------

    @Test
    fun nothingIsCompiledBeforeTheFirstReload() = runTest {
        val live = LiveHarness(this, LiveStories.forest)
        live.advance(10_000)
        assertEquals(0, live.engine.compiles)
        assertEquals(LiveState(), live.state)
        assertSame(PlayerTranscript.Empty, live.transcript)
        assertFalse(live.state.canStepBack)
        assertFalse(live.state.transcriptIsCurrent)
    }

    @Test
    fun theFirstLoadCompilesAndPlaysAtOnce() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        assertEquals(1, live.engine.compiles)
        assertEquals(listOf("A fork in the path.", "# opening", "* Left", "* Right"), live.shown())
        assertFalse(live.state.busy)
        assertEquals(CompileStatus.Ok, live.state.compile)
        assertTrue(live.state.transcriptIsCurrent)
        assertTrue(live.state.issues.isEmpty())
        assertEquals(0, live.transcript.liveTurn)
        assertEquals(1L, live.transcript.generation)
        assertNull(live.transcript.appendedFrom)
        assertFalse(live.transcript.isOver)
        assertEquals(listOf(PlayerChoice(0, "Left", emptyList(), 0), PlayerChoice(1, "Right", emptyList(), 0)), live.transcript.choices)
    }

    @Test
    fun anEditCompilesOnceThePauseIsOverAndNotBefore() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.type(LiveStories.forest.replace("A fork", "A second fork"))
        live.settle()
        assertTrue(live.state.busy)
        live.advance(499)
        assertEquals(1, live.engine.compiles)
        assertTrue(live.state.busy)
        assertEquals("A fork in the path.", live.shown().first())
        live.advance(1)
        assertEquals(2, live.engine.compiles)
        assertFalse(live.state.busy)
        assertEquals("A second fork in the path.", live.shown().first())
        assertEquals(2L, live.transcript.generation)
    }

    @Test
    fun typingWithoutAPauseNeverCompilesAndThenCompilesOnce() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        repeat(40) { i ->
            live.type(LiveStories.forest.replace("A fork", "Fork $i"))
            live.advance(300)
            assertEquals("while typing", 1, live.engine.compiles)
            assertTrue(live.state.busy)
        }
        live.advance(200)
        assertEquals(2, live.engine.compiles)
        assertEquals("Fork 39 in the path.", live.shown().first())
        assertFalse(live.state.busy)
        // nothing was left waiting
        live.advance(5_000)
        assertEquals(2, live.engine.compiles)
    }

    @Test
    fun onlyThePauseAfterTheLatestEditCounts() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.type(LiveStories.forest.replace("A fork", "One"))
        live.advance(400)
        live.type(LiveStories.forest.replace("A fork", "Two"))
        live.advance(100) // the first edit's pause would end here
        assertEquals(1, live.engine.compiles)
        assertTrue(live.state.busy)
        live.advance(400)
        assertEquals(2, live.engine.compiles)
        assertEquals("Two in the path.", live.shown().first())
    }

    @Test
    fun aReloadDoesNotWaitAndThePendingPauseThenHasNothingToDo() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.type(LiveStories.forest.replace("A fork", "Edited"))
        live.advance(100)
        live.session.reloadNow()
        live.settle()
        assertEquals(2, live.engine.compiles)
        assertEquals("Edited in the path.", live.shown().first())
        assertFalse(live.state.busy)
        live.advance(5_000)
        assertEquals(2, live.engine.compiles)
        assertFalse(live.state.busy)
    }

    @Test
    fun anUnchangedProjectIsNotCompiledTwice() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        val first = live.transcript
        // typed and taken back within the pause
        live.type(LiveStories.forest + "x")
        live.advance(100)
        live.typeAndWait(LiveStories.forest)
        assertEquals(1, live.engine.compiles)
        assertFalse(live.state.busy)
        // and the player was left alone
        assertSame(first, live.transcript)

        // an explicit reload replays, without compiling
        live.session.reloadNow()
        live.settle()
        assertEquals(1, live.engine.compiles)
        assertEquals(2L, live.transcript.generation)
        assertEquals(live.shown(first), live.shown())
    }

    // --- an edit that arrives while a compile runs -----------------------------------------------

    @Test
    fun aCompileOvertakenByAnEditIsThrownAway() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        val before = live.state
        live.type(LiveStories.forest.replace("A fork", "Half typed"))
        live.engine.duringCompile = {
            live.engine.duringCompile = {}
            live.type(LiveStories.forest.replace("A fork", "Fully typed"))
        }
        live.advance(500)
        // the compile of "Half typed" ran and was dropped: the state did not move, and stays busy
        assertEquals(2, live.engine.compiles)
        assertEquals(before.transcript, live.transcript)
        assertTrue(live.state.busy)
        live.advance(499)
        assertEquals(2, live.engine.compiles)
        live.advance(1)
        assertEquals(3, live.engine.compiles)
        assertEquals("Fully typed in the path.", live.shown().first())
        assertFalse(live.state.busy)
    }

    @Test
    fun aBrokenCompileOvertakenByAnEditPublishesNoErrors() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.type("{broken\n")
        live.engine.duringCompile = {
            live.engine.duringCompile = {}
            live.type(LiveStories.forest)
        }
        live.advance(500)
        assertEquals(CompileStatus.Ok, live.state.compile)
        assertTrue(live.state.issues.isEmpty())
        live.advance(500)
        assertEquals(CompileStatus.Ok, live.state.compile)
        assertFalse(live.state.busy)
        // the load, the broken text (dropped unseen), the good text again
        assertEquals(3, live.engine.compiles)
    }

    @Test
    fun editsDuringCompilesNeverQueueUp() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        var n = 0
        // every compile sees another edit arrive, ten times over
        live.engine.duringCompile = { if (n++ < 10) live.type(LiveStories.forest.replace("A fork", "Edit $n")) }
        live.type(LiveStories.forest.replace("A fork", "Edit 0"))
        repeat(10) {
            live.advance(500)
            assertTrue(live.state.busy)
            assertEquals("A fork in the path.", live.shown().first())
        }
        live.advance(500)
        assertEquals(12, live.engine.compiles) // the load, ten overtaken ones, the last
        assertEquals("Edit 10 in the path.", live.shown().first())
        assertFalse(live.state.busy)
        assertEquals(2L, live.transcript.generation)
    }

    // --- choices, replay, step back, rewind ------------------------------------------------------

    @Test
    fun aChoiceAppendsItsTurn() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.choose("Left")
        assertEquals(listOf("A fork in the path.", "# opening", "---", "You went left.", "* On"), live.shown())
        assertEquals(1, live.transcript.liveTurn)
        assertEquals(1L, live.transcript.generation)
        // what is new starts at the divider
        assertEquals(2, live.transcript.appendedFrom)
        assertTrue(live.state.canStepBack)
        assertEquals(listOf(PlayerChoice(0, "On", emptyList(), 1)), live.transcript.choices)

        live.choose("On")
        assertEquals(
            listOf("A fork in the path.", "# opening", "---", "You went left.", "---", "The end of it.", "END"),
            live.shown(),
        )
        assertEquals(4, live.transcript.appendedFrom)
        assertTrue(live.transcript.isOver)
        assertEquals(1, live.engine.compiles)
    }

    @Test
    fun aRecompileReplaysTheChoicesAndPublishesTheTranscriptWhole() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.choose("Right")
        live.choose("On")
        val keysBefore = live.transcript.items.map { it.key }

        live.typeAndWait(LiveStories.forest.replace("You went right.", "You turned right."))
        assertEquals(
            listOf("A fork in the path.", "# opening", "---", "You turned right.", "---", "The end of it.", "END"),
            live.shown(),
        )
        assertEquals(2, live.transcript.liveTurn)
        assertEquals(2L, live.transcript.generation)
        assertNull(live.transcript.appendedFrom)
        // the same places keep their keys, so a list swaps the new transcript in place
        assertEquals(keysBefore, live.transcript.items.map { it.key })
        assertEquals(keysBefore.size, keysBefore.toSet().size)
    }

    @Test
    fun everyPublishedTranscriptIsAFinishedOne() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.choose("Right")
        live.choose("Back")
        live.choose("On")
        val seen = ArrayList<PlayerTranscript>()
        val collector = backgroundScope.launchUndispatched { live.session.state.collect { seen += it.transcript } }
        live.typeAndWait(LiveStories.forest.replace("A fork", "The fork"))
        collector.cancel()
        // through the pause, the compile and the replay of three choices, the player saw exactly
        // two transcripts: the old one and the new one, complete
        assertEquals(2, seen.distinct().size)
        assertEquals(3, seen.first().liveTurn)
        assertEquals(3, seen.last().liveTurn)
        assertEquals("The fork in the path.", live.shown(seen.last()).first())
        assertEquals("END", live.shown(seen.last()).last())
    }

    @Test
    fun aRememberedChoiceThatIsGoneEndsTheReplayThere() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.choose("Right")
        live.choose("Back") // the second choice of the turn
        live.choose("On")
        assertEquals(3, live.transcript.liveTurn)

        // "Back" is deleted: turn 1 now has one choice only
        live.typeAndWait(LiveStories.forest.replace("* [Back] -> left\n", ""))
        assertEquals(listOf("A fork in the path.", "# opening", "---", "You went right.", "* On"), live.shown())
        assertEquals(1, live.transcript.liveTurn)

        // and the choices after it are forgotten, not kept to drift into later turns:
        // putting "Back" back does not bring the old ending back
        live.typeAndWait(LiveStories.forest)
        assertEquals(listOf("A fork in the path.", "# opening", "---", "You went right.", "* On", "* Back"), live.shown())
        // one step back is one turn back
        live.session.stepBack()
        live.settle()
        assertEquals(listOf("A fork in the path.", "# opening", "* Left", "* Right"), live.shown())
    }

    @Test
    fun aStoryThatNowEndsEarlierEndsTheReplayThere() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.choose("Left")
        live.choose("On")
        live.typeAndWait(LiveStories.forest.replace("You went left.\n* [On] -> finale", "You went left and fell.\n-> END"))
        assertEquals(listOf("A fork in the path.", "# opening", "---", "You went left and fell.", "END"), live.shown())
        assertEquals(1, live.transcript.liveTurn)
        assertTrue(live.transcript.choices.isEmpty())
    }

    @Test
    fun stepBackTakesBackOneChoiceWithoutCompiling() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.choose("Left")
        live.choose("On")
        live.session.stepBack()
        live.settle()
        assertEquals(listOf("A fork in the path.", "# opening", "---", "You went left.", "* On"), live.shown())
        assertEquals(1, live.transcript.liveTurn)
        assertNull(live.transcript.appendedFrom)
        assertEquals(2L, live.transcript.generation)
        assertEquals(1, live.engine.compiles)
        assertFalse(live.state.busy)

        live.session.stepBack()
        live.settle()
        assertEquals(listOf("A fork in the path.", "# opening", "* Left", "* Right"), live.shown())
        assertFalse(live.state.canStepBack)
    }

    @Test
    fun stepBackAtTheFirstTurnOnlyReloads() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.session.stepBack()
        live.session.stepBack()
        live.settle()
        assertEquals(listOf("A fork in the path.", "# opening", "* Left", "* Right"), live.shown())
        assertEquals(0, live.transcript.liveTurn)
        assertEquals(1, live.engine.compiles)
        assertFalse(live.state.busy)
    }

    @Test
    fun stepBackCompilesAtOnceWhenTheTextChanged() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.choose("Left")
        live.type(LiveStories.forest.replace("A fork", "Another fork"))
        live.advance(100)
        live.session.stepBack()
        live.settle()
        assertEquals(2, live.engine.compiles)
        assertEquals(listOf("Another fork in the path.", "# opening", "* Left", "* Right"), live.shown())
        assertFalse(live.state.busy)
        live.advance(1_000)
        assertEquals(2, live.engine.compiles)
    }

    @Test
    fun rewindForgetsEveryChoice() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.choose("Right")
        live.choose("Back")
        live.session.rewind()
        live.settle()
        assertEquals(listOf("A fork in the path.", "# opening", "* Left", "* Right"), live.shown())
        assertEquals(0, live.transcript.liveTurn)
        assertEquals(1, live.engine.compiles)
        // and a later recompile has nothing to replay
        live.typeAndWait(LiveStories.forest + "\n")
        assertEquals(0, live.transcript.liveTurn)
    }

    @Test
    fun theSeedIsKeptAcrossRecompilesAndDrawnAnewByRewind() = runTest {
        var next = 5
        val live = LiveHarness(this, LiveStories.dice, config = LiveConfig(newSeed = { next++ })).load()
        live.choose("Again")
        live.choose("Again")
        val rolls = live.shown()
        assertEquals(3, rolls.count { it.startsWith("Rolled") })

        live.typeAndWait(LiveStories.dice + "\n")
        assertEquals("a recompile replays the same rolls", rolls, live.shown())
        live.session.stepBack()
        live.settle()
        assertEquals(rolls.take(3), live.shown().take(3))
        assertEquals(listOf<Int?>(5, 5, 5), live.engine.seeds)

        live.session.rewind()
        live.settle()
        assertEquals(6, live.engine.seeds.last())
        assertNotEquals(rolls.first(), live.shown().first())
    }

    @Test
    fun aChoiceFromATranscriptThatWasReplacedIsIgnored() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        val left = live.transcript.choices[0]
        val right = live.transcript.choices[1]

        // the story changed under the player's finger: "Right" is now "Onwards"
        live.typeAndWait(LiveStories.forest.replace("[Right]", "[Onwards]"))
        live.session.choose(right)
        live.settle()
        assertEquals(0, live.transcript.liveTurn)

        // a choice that is still the same choice is taken
        live.session.choose(left)
        live.settle()
        assertEquals(1, live.transcript.liveTurn)

        // a choice of an earlier turn, a second tap on the same choice, an index out of range: ignored
        live.session.choose(left)
        live.session.choose(PlayerChoice(index = 7, text = "On", tags = emptyList(), turn = 1))
        live.settle()
        assertEquals(1, live.transcript.liveTurn)
        assertEquals(listOf("A fork in the path.", "# opening", "---", "You went left.", "* On"), live.shown())
    }

    @Test
    fun aChoiceTakenDuringThePauseIsReplayedByTheCompileThatFollows() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.type(LiveStories.forest.replace("You went left.", "You crept left."))
        live.advance(200)
        live.choose("Left") // on the story still shown
        assertEquals("You went left.", live.shown()[3])
        assertTrue(live.state.busy)
        live.advance(300)
        assertEquals(listOf("A fork in the path.", "# opening", "---", "You crept left.", "* On"), live.shown())
        assertFalse(live.state.busy)
    }

    // --- errors in the ink -----------------------------------------------------------------------

    @Test
    fun whileTheInkHasErrorsTheLastStoryStaysAndIsPlayable() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.choose("Right")
        val good = live.transcript

        live.typeAndWait(LiveStories.forest.replace("-> finale\n* [Back]", "-> nowhere\n* [Back]"))
        assertEquals(CompileStatus.Errors, live.state.compile)
        assertFalse(live.state.transcriptIsCurrent)
        assertFalse(live.state.busy)
        assertEquals(listOf(InkIssue(IssueKind.Error, "main.ink", 12, "Divert target not found: '-> nowhere'")), live.state.issues)
        assertSame(good, live.transcript)

        // the old story still plays
        live.choose("Back")
        assertEquals("You went left.", live.shown()[live.shown().size - 2])
        live.session.stepBack()
        live.settle()
        assertEquals(live.shown(good), live.shown())
        assertEquals(CompileStatus.Errors, live.state.compile)
        assertEquals(1, live.state.issues.size)
        live.session.rewind()
        live.settle()
        assertEquals(listOf("A fork in the path.", "# opening", "* Left", "* Right"), live.shown())
        assertEquals(2, live.engine.compiles)

        // fixed: the new story, replayed through what the player chose meanwhile
        live.choose("Right")
        live.typeAndWait(LiveStories.forest.replace("You went right.", "Right it is."))
        assertEquals(CompileStatus.Ok, live.state.compile)
        assertTrue(live.state.issues.isEmpty())
        assertEquals(listOf("A fork in the path.", "# opening", "---", "Right it is.", "* On", "* Back"), live.shown())
    }

    @Test
    fun errorsBeforeAnyStoryCompiledShowNoTranscript() = runTest {
        val live = LiveHarness(this, "{broken\n").load()
        assertEquals(CompileStatus.Errors, live.state.compile)
        assertTrue(live.state.issues.isNotEmpty())
        assertTrue(live.state.issues.all { it.kind == IssueKind.Error && it.file == "main.ink" })
        assertTrue(live.transcript.items.isEmpty())
        live.session.stepBack()
        live.session.rewind()
        live.settle()
        assertTrue(live.transcript.items.isEmpty())
        assertFalse(live.state.busy)

        live.typeAndWait(LiveStories.forest)
        assertEquals(CompileStatus.Ok, live.state.compile)
        assertEquals(4, live.shown().size)
    }

    @Test
    fun warningsAndTodosComeWithAStoryThatPlays() = runTest {
        val live = LiveHarness(this, "TODO: write more\nHello.\n== unfinished ==\nText\n").load()
        assertEquals(CompileStatus.Ok, live.state.compile)
        assertEquals(listOf(IssueKind.Todo, IssueKind.Warning), live.state.issues.map { it.kind })
        assertEquals(listOf("Hello.", "END"), live.shown())
    }

    // --- what the story does at runtime ----------------------------------------------------------

    @Test
    fun runtimeIssuesAreInTheTranscriptAndInTheIssueList() = runTest {
        val ink = "VAR s = \"a\"\nBefore.\n* [Go]\n- Then.\n~ s = s * 2\nNever.\n"
        val live = LiveHarness(this, ink).load()
        assertTrue(live.state.issues.isEmpty())
        live.choose("Go")
        val issue = InkIssue(IssueKind.RuntimeError, "main.ink", 5, "Cannot perform operation '*' on String")
        assertEquals(listOf("Before.", "---", "Then.", "RuntimeError: ${issue.message}", "END"), live.shown())
        assertEquals(issue, live.transcript.items.filterIsInstance<TranscriptItem.RuntimeIssue>().single().issue)
        assertEquals(listOf(issue), live.state.issues)
        assertTrue(live.transcript.isOver)

        // replayed: still once in the list
        live.typeAndWait(ink + "\n")
        assertEquals(listOf(issue), live.state.issues)
        live.session.stepBack()
        live.settle()
        assertTrue(live.state.issues.isEmpty())
    }

    @Test
    fun aStoryThatLoopsIsStoppedNotHung() = runTest {
        val limits = PlayLimits(maxLinesPerTurn = 50, turnBudgetMillis = 300)
        val live = LiveHarness(this, "- (top) Again.\n-> top\n", config = LiveConfig(limits = limits)).load()
        assertEquals(51, live.transcript.items.size)
        assertEquals(TranscriptItem.Stopped(key = 50, turn = 0, reason = StopReason.LineLimit, detail = null), live.transcript.items.last())
        assertTrue(live.transcript.isOver)
        assertTrue(live.transcript.choices.isEmpty())

        live.typeAndWait("VAR n = 0\n- (top)\n~ n++\n-> top\n")
        assertEquals(listOf("STOPPED TimeLimit"), live.shown())
        assertFalse(live.state.busy)

        live.typeAndWait("Fine again.\n")
        assertEquals(listOf("Fine again.", "END"), live.shown())
    }

    @Test
    fun aRuntimeThatThrowsEndsTheStoryWithItsMessage() = runTest {
        val live = LiveHarness(this, "VAR z = 0\nBefore.\n* [Divide]\n- {1 / z}\nAfter.\n").load()
        live.choose("Divide")
        assertEquals(listOf("Before.", "---", "RuntimeError: / by zero", "STOPPED EngineFailure: / by zero"), live.shown())
        assertEquals(listOf(InkIssue(IssueKind.RuntimeError, null, null, "/ by zero")), live.state.issues)
        assertTrue(live.transcript.isOver)
        // and the session goes on
        live.session.stepBack()
        live.settle()
        assertEquals(listOf("Before.", "* Divide"), live.shown())
    }

    @Test
    fun linesWithoutTextShowOnlyTheirTags() = runTest {
        val live = LiveHarness(this, "# title: T\nINCLUDE other.ink\nHello. # one # two\n")
        live.files["other.ink"] = "== other ==\n-> END\n"
        live.load()
        // "# title" above an INCLUDE is a line of its own without text
        assertEquals(listOf("# title: T", "Hello.", "# one, two", "END"), live.shown())
        val tags = live.transcript.items.first() as TranscriptItem.Tags
        assertEquals(SourceLocation("main.ink", 1), tags.source)
    }

    @Test
    fun textKnowsWhereItCameFrom() = runTest {
        val live = LiveHarness(this, "INCLUDE sub/more.ink\nFirst.\nSecond {shout()} here.\n")
        live.files["sub/more.ink"] = "== function shout ==\nLOUD\n"
        live.load()
        val (first, second) = live.transcript.items.filterIsInstance<TranscriptItem.Text>()
        assertEquals(SourceLocation("main.ink", 2), first.source)
        assertEquals("Second LOUD here.", second.text)
        assertEquals(SourceLocation("main.ink", 3), second.source)
        assertEquals(SourceLocation("sub/more.ink", 2), second.sourceAt(second.text.indexOf("LOUD")))
        assertEquals(SourceLocation("main.ink", 3), second.sourceAt(second.text.indexOf("here")))
    }

    // --- watches and the instruction prefix ------------------------------------------------------

    @Test
    fun watchesAreEvaluatedAfterEveryTurn() = runTest {
        val live = LiveHarness(this, LiveStories.forest)
        live.session.setWatches(listOf("x is {x}", "{nope}"))
        live.load()
        live.choose("Right")
        live.choose("On")
        assertEquals(
            listOf(
                "A fork in the path.", "# opening", "= x is 0", "! Unresolved variable: nope", "---",
                "You went right.", "= x is 2", "! Unresolved variable: nope", "---",
                "The end of it.", "= x is 2", "! Unresolved variable: nope", "END",
            ),
            live.shown(),
        )
        val pill = live.transcript.items[2] as TranscriptItem.Evaluation
        assertEquals("x is {x}", pill.expression)
        assertEquals("x is 0", pill.result)
        assertNull(pill.error)
        assertEquals(listOf("x is {x}", "{nope}"), live.state.watches)
        // a watch's problem is not a problem of the ink
        assertTrue(live.state.issues.isEmpty())
    }

    @Test
    fun changingTheWatchesReplaysAfterThePauseWithoutCompiling() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.choose("Left")
        live.session.setWatches(listOf("x={x}"))
        live.settle()
        assertTrue(live.state.busy)
        assertEquals(listOf("x={x}"), live.state.watches)
        assertFalse("not before the pause", live.shown().contains("= x=1"))
        live.advance(500)
        assertEquals(listOf("A fork in the path.", "# opening", "= x=0", "---", "You went left.", "= x=1", "* On"), live.shown())
        assertEquals(1, live.engine.compiles)
        assertEquals(2L, live.transcript.generation)
        assertFalse(live.state.busy)

        // removed again
        live.session.setWatches(emptyList())
        live.advance(500)
        assertEquals(listOf("A fork in the path.", "# opening", "---", "You went left.", "* On"), live.shown())
        assertEquals(1, live.engine.compiles)

        // the same list again: nothing to replay
        val shown = live.transcript
        live.session.setWatches(emptyList())
        live.advance(500)
        assertSame(shown, live.transcript)
        assertFalse(live.state.busy)
    }

    @Test
    fun watchesAreCleanedAsUpstreamsFieldCleansThem() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.session.setWatches(listOf("x is\r\n{x}", "   ", "", "{x}\n"))
        live.advance(500)
        assertEquals(listOf("x is {x}", "{x} "), live.state.watches)
        assertEquals(listOf("A fork in the path.", "# opening", "= x is 0", "= 0", "* Left", "* Right"), live.shown())
    }

    @Test
    fun watchesFollowAChoiceAtOnce() = runTest {
        val live = LiveHarness(this, LiveStories.forest)
        live.session.setWatches(listOf("{x}"))
        live.load()
        live.choose("Right")
        assertEquals(listOf("A fork in the path.", "# opening", "= 0", "---", "You went right.", "= 2", "* On", "* Back"), live.shown())
        assertEquals(3, live.transcript.appendedFrom)
    }

    @Test
    fun theInstructionPrefixMarksLinesInPlace() = runTest {
        val live = LiveHarness(this, ">>> CAMERA: wide\nHello.\n  >>> CUT\n* [Go]\n- >>> not at the start? yes it is\n").load()
        assertEquals(listOf(">>> CAMERA: wide", "Hello.", ">>> CUT", "* Go"), live.shown())
        val generation = live.transcript.generation

        live.session.setInstructionPrefix(">>>")
        live.settle()
        assertEquals(listOf(">> >>> CAMERA: wide", "Hello.", ">> >>> CUT", "* Go"), live.shown())
        assertEquals(generation, live.transcript.generation)
        assertEquals(1, live.engine.compiles)
        assertFalse(live.state.busy)

        live.choose("Go")
        assertEquals(">> >>> not at the start? yes it is", live.shown()[4])
        // kept through a replay
        live.session.stepBack()
        live.settle()
        assertEquals(listOf(">> >>> CAMERA: wide", "Hello.", ">> >>> CUT", "* Go"), live.shown())

        live.session.setInstructionPrefix("")
        live.settle()
        assertEquals(listOf(">>> CAMERA: wide", "Hello.", ">>> CUT", "* Go"), live.shown())
        live.session.setInstructionPrefix(null)
        live.settle()
        assertEquals(1, live.engine.compiles)
    }

    @Test
    fun choiceTagsComeWithTheChoices() = runTest {
        val live = LiveHarness(this, "Pick.\n* [One # first # best]\n* Two\n- Done.\n").load()
        assertEquals(listOf("Pick.", "* One #first #best", "* Two"), live.shown())
        assertEquals(listOf("first", "best"), live.transcript.choices[0].tags)
    }
}

private fun CoroutineScope.launchUndispatched(block: suspend CoroutineScope.() -> Unit) =
    launch(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED, block)
