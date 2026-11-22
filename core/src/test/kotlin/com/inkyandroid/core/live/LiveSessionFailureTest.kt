package com.inkyandroid.core.live

import com.inkyandroid.core.engine.StopReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whatever fails underneath, the session publishes it as state and goes on; and it ends cleanly with its scope. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionFailureTest {

    @Test
    fun aCompilerThatThrowsIsACrashReportAndTheSessionGoesOn() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.choose("Left")

        live.engine.compileFailure = IllegalStateException("boom in the compiler")
        live.typeAndWait(LiveStories.forest.replace("A fork", "The fork"))
        assertEquals(CompileStatus.Crashed, live.state.compile)
        assertFalse(live.state.busy)
        assertFalse(live.state.transcriptIsCurrent)
        val crash = live.transcript.items.single() as TranscriptItem.CompilerCrash
        assertEquals("boom in the compiler", crash.message)
        assertTrue(crash.diagnostic, crash.diagnostic.startsWith("java.lang.IllegalStateException: boom in the compiler\n    at "))
        assertTrue(live.transcript.choices.isEmpty())
        assertTrue(live.transcript.isOver)

        // nothing to play, nothing breaks
        live.session.choose(PlayerChoice(0, "On", emptyList(), 1))
        live.session.stepBack()
        live.settle()
        assertEquals(CompileStatus.Crashed, live.state.compile)

        // the compiler works again: the story is back, replayed through the choices that are left
        live.engine.compileFailure = null
        live.session.rewind()
        live.settle()
        assertEquals(CompileStatus.Ok, live.state.compile)
        assertEquals(listOf("The fork in the path.", "# opening", "* Left", "* Right"), live.shown())
    }

    @Test
    fun aCrashKeepsTheRememberedChoicesForTheNextStory() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.choose("Left")
        live.engine.compileFailure = StackOverflowError()
        live.typeAndWait(LiveStories.forest + "\n")
        assertEquals("CRASH StackOverflowError", live.shown().single())
        live.engine.compileFailure = null
        live.typeAndWait(LiveStories.forest)
        assertEquals(listOf("A fork in the path.", "# opening", "---", "You went left.", "* On"), live.shown())
    }

    @Test
    fun errorsAfterACrashClearTheCrashReport() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.engine.compileFailure = IllegalStateException("boom")
        live.typeAndWait(LiveStories.forest + "\n")
        assertEquals(1, live.transcript.items.size)
        live.engine.compileFailure = null
        live.typeAndWait("{broken\n")
        assertEquals(CompileStatus.Errors, live.state.compile)
        assertTrue(live.transcript.items.isEmpty())
        assertTrue(live.state.issues.isNotEmpty())
    }

    @Test
    fun sourcesThatCannotBeReadAreACrashReportToo() = runTest {
        val live = LiveHarness(this, LiveStories.forest)
        live.sourcesFailure = java.io.IOException("storage went away")
        live.load()
        assertEquals(CompileStatus.Crashed, live.state.compile)
        assertEquals("CRASH storage went away", live.shown().single())
        assertEquals(0, live.engine.compiles)
        assertFalse(live.state.busy)

        live.sourcesFailure = null
        live.session.reloadNow()
        live.settle()
        assertEquals(CompileStatus.Ok, live.state.compile)
        assertEquals(4, live.shown().size)
    }

    @Test
    fun aCancellationThatIsNotTheSessionsOwnIsAFailureLikeAnyOther() = runTest {
        // A timeout inside the sources provider, or a scope of the caller's that ended there: the
        // session lives on, says what happened, and works again once the provider does.
        val live = LiveHarness(this, LiveStories.forest)
        live.sourcesFailure = CancellationException("the project's scope ended")
        live.load()
        assertEquals(CompileStatus.Crashed, live.state.compile)
        assertEquals("CRASH the project's scope ended", live.shown().single())
        assertFalse(live.state.busy)

        val statistics = async { live.session.statistics() }
        val export = async { live.session.exportJson() }
        live.settle()
        assertNull(statistics.await())
        assertEquals(ExportResult.Crashed("the project's scope ended"), export.await())

        live.sourcesFailure = null
        live.session.reloadNow()
        live.settle()
        assertEquals(CompileStatus.Ok, live.state.compile)
        assertEquals(4, live.shown().size)
    }

    @Test
    fun aPlaythroughThatCannotStartIsAStoppedStory() = runTest {
        val live = LiveHarness(this, LiveStories.forest)
        live.engine.playFailure = IllegalStateException("no playthrough")
        live.load()
        assertEquals(CompileStatus.Ok, live.state.compile)
        assertEquals(listOf("RuntimeError: no playthrough", "STOPPED EngineFailure: no playthrough"), live.shown())
        assertEquals(StopReason.EngineFailure, (live.transcript.items.last() as TranscriptItem.Stopped).reason)
        assertFalse(live.state.busy)
        live.engine.playFailure = null
        live.session.reloadNow()
        live.settle()
        assertEquals(4, live.shown().size)
    }

    @Test
    fun aTurnThatThrowsDuringAReplayEndsTheTranscriptThere() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.choose("Left")
        live.engine.turnFailure = OutOfMemoryError("pretend")
        live.typeAndWait(LiveStories.forest + "\n")
        assertEquals(listOf("RuntimeError: pretend", "STOPPED EngineFailure: pretend"), live.shown())
        assertTrue(live.transcript.choices.isEmpty())
        assertFalse(live.state.busy)

        live.engine.turnFailure = null
        live.session.reloadNow()
        live.settle()
        // the choice was never replayed, so it is forgotten: the story stands at its start
        assertEquals(listOf("A fork in the path.", "# opening", "* Left", "* Right"), live.shown())
    }

    @Test
    fun aTurnThatThrowsAfterAChoiceIsAppendedAsAStop() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.engine.turnFailure = IllegalStateException("mid-turn")
        live.choose("Left")
        assertEquals(
            listOf("A fork in the path.", "# opening", "---", "RuntimeError: mid-turn", "STOPPED EngineFailure: mid-turn"),
            live.shown(),
        )
        assertEquals(2, live.transcript.appendedFrom)
        assertTrue(live.transcript.choices.isEmpty())
        // later choices fall on nothing
        live.session.choose(PlayerChoice(0, "On", emptyList(), 1))
        live.settle()
        assertEquals(1, live.transcript.liveTurn)

        live.engine.turnFailure = null
        live.session.stepBack()
        live.settle()
        assertEquals(listOf("A fork in the path.", "# opening", "* Left", "* Right"), live.shown())
    }

    @Test
    fun aFailureNobodyForesawStillLeavesAWorkingSession() = runTest {
        var draws = 0
        val config = LiveConfig(newSeed = { if (draws++ == 1) error("no more seeds") else 3 })
        val live = LiveHarness(this, LiveStories.forest, config = config).load()
        live.choose("Left")
        live.session.rewind() // draws the second seed
        live.settle()
        assertEquals(listOf("RuntimeError: no more seeds", "STOPPED EngineFailure: no more seeds"), live.shown())
        assertFalse(live.state.busy)

        live.session.rewind()
        live.settle()
        assertEquals(listOf("A fork in the path.", "# opening", "* Left", "* Right"), live.shown())
        live.typeAndWait(LiveStories.forest.replace("A fork", "Still here: a fork"))
        assertEquals("Still here: a fork in the path.", live.shown().first())
    }

    @Test
    fun requestsAnswerEvenWhenTheEngineThrows() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.engine.compileFailure = IllegalStateException("boom")
        live.files["main.ink"] = LiveStories.forest + "\n"
        val export = async { live.session.exportJson() }
        runCurrent()
        live.sourcesFailure = IllegalStateException("no sources")
        val statistics = async { live.session.statistics() }
        val exportWithoutSources = async { live.session.exportJson() }
        runCurrent()
        assertEquals(ExportResult.Crashed("no sources"), exportWithoutSources.await())
        assertEquals(ExportResult.Crashed("boom"), export.await())
        assertNull(statistics.await())
        // the player was not touched by either
        assertEquals(CompileStatus.Ok, live.state.compile)
        assertEquals(4, live.shown().size)
    }

    @Test
    fun cancellingTheScopeEndsTheSessionQuietly() = runTest {
        val scope = CoroutineScope(Job())
        val texts = mapOf("main.ink" to LiveStories.forest)
        val session = LiveSession(scope, StandardTestDispatcher(testScheduler), { com.inkyandroid.core.engine.InkSources("main.ink", texts) })
        session.reloadNow()
        runCurrent()
        val last = session.state.value
        assertEquals(CompileStatus.Ok, last.compile)

        session.edited() // a pause is running
        val waiting = async { runCatching { session.statistics() } }
        scope.cancel()
        advanceUntilIdle()
        assertTrue(waiting.await().exceptionOrNull() is CancellationException)

        // everything is ignored from now on, nothing throws, nothing is left running
        session.edited()
        session.reloadNow()
        session.rewind()
        session.stepBack()
        session.choose(last.transcript.choices[0])
        session.setWatches(listOf("{x}"))
        session.setInstructionPrefix(">>>")
        session.close()
        advanceUntilIdle()
        assertEquals(last.transcript, session.state.value.transcript)
        assertTrue(runCatching { session.exportJson() }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { session.locateRuntimePath("left") }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun closingEndsTheSessionAndLeavesTheScopeAlone() = runTest {
        val scope = CoroutineScope(Job())
        val live = LiveSession(scope, StandardTestDispatcher(testScheduler), { com.inkyandroid.core.engine.InkSources("main.ink", mapOf("main.ink" to "Hello.\n")) })
        live.reloadNow()
        runCurrent()
        live.edited()
        live.close()
        advanceUntilIdle()
        assertTrue("the caller's scope lives on", scope.coroutineContext[Job]!!.isActive)
        assertTrue(scope.coroutineContext[Job]!!.children.none { it.isActive })
        assertEquals(CompileStatus.Ok, live.state.value.compile)
        assertTrue(runCatching { live.statistics() }.exceptionOrNull() is CancellationException)
        scope.cancel()
    }

    @Test
    fun cancellationInsideTheSourcesProviderEndsTheSessionWithoutACrashReport() = runTest {
        val scope = CoroutineScope(Job())
        val session = LiveSession(scope, StandardTestDispatcher(testScheduler), {
            scope.cancel()
            kotlinx.coroutines.yield()
            error("not reached")
        })
        session.reloadNow()
        advanceUntilIdle()
        assertEquals(CompileStatus.None, session.state.value.compile)
        assertTrue(session.state.value.transcript.items.isEmpty())
    }
}
