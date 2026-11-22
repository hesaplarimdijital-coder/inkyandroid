package com.inkyandroid.core.live

import com.inkyandroid.core.engine.InkEngine
import com.inkyandroid.core.engine.InkSources
import com.inkyandroid.core.engine.InkStatistics
import com.inkyandroid.core.engine.IssueKind
import com.inkyandroid.core.engine.SourceLocation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Statistics, export and lookups through the session; and the session on a real thread. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionRequestsTest {

    @Test
    fun exportUsesTheLiveCompilationWhenTheProjectIsUnchanged() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        val export = async { live.session.exportJson() }
        runCurrent()
        val json = (export.await() as ExportResult.Json).json
        assertTrue(json.startsWith("{\"inkVersion\":21,"))
        assertEquals(1, live.engine.compiles)
    }

    @Test
    fun exportCompilesTheTextAsItIsNowWithoutDisturbingThePlayer() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.choose("Left")
        val shown = live.transcript
        live.type(LiveStories.forest.replace("The end of it.", "Exported ending."))
        val export = async { live.session.exportJson() }
        runCurrent()
        assertTrue((export.await() as ExportResult.Json).json.contains("Exported ending."))
        assertEquals(2, live.engine.compiles)
        // the player still waits for the pause, on the story it had
        assertEquals(shown, live.transcript)
        assertTrue(live.state.busy)
        live.advance(500)
        // and the pause then finds the text already compiled
        assertEquals(2, live.engine.compiles)
        assertEquals(2L, live.transcript.generation)
        live.choose("On")
        assertEquals("Exported ending.", live.shown()[live.shown().size - 2])
    }

    @Test
    fun exportIsRefusedWhileTheInkHasErrors() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        live.typeAndWait(LiveStories.forest.replace("-> finale\n* [Back]", "-> nowhere\n* [Back]"))
        val export = async { live.session.exportJson() }
        runCurrent()
        val refused = export.await() as ExportResult.HasErrors
        assertEquals(listOf(IssueKind.Error), refused.issues.map { it.kind })
        assertEquals(2, live.engine.compiles)
    }

    @Test
    fun statisticsCountTheProjectAsItIsNow() = runTest {
        val live = LiveHarness(this, LiveStories.forest).load()
        val before = async { live.session.statistics() }
        runCurrent()
        assertEquals(InkEngine.Default.statistics(InkSources("main.ink", mapOf("main.ink" to LiveStories.forest))), before.await())
        assertEquals(3, before.await()!!.knots)

        // also for ink with errors, and for text the pause has not reached yet
        live.type("One two three\n== knot ==\n-> nowhere\n")
        val after = async { live.session.statistics() }
        runCurrent()
        assertEquals(InkStatistics(words = 3, knots = 1, stitches = 0, functions = 0, choices = 0, gathers = 0, diverts = 1), after.await())
        assertEquals(2, live.engine.statisticsCalls)
        assertEquals(1, live.engine.compiles)
    }

    @Test
    fun runtimePathsAreLookedUpInTheStoryBeingPlayed() = runTest {
        val live = LiveHarness(this, LiveStories.forest)
        val early = async { live.session.locateRuntimePath("left") }
        runCurrent()
        assertNull("nothing is played yet", early.await())
        live.load()
        val found = async { live.session.locateRuntimePath("right") }
        val missing = async { live.session.locateRuntimePath("no.such.path") }
        runCurrent()
        assertEquals(SourceLocation("main.ink", 9), found.await())
        assertNull(missing.await())
    }

    /**
     * The same session on a real single thread, driven from several threads at once, with real
     * time. What it must not do: lose an edit, publish a half transcript, deadlock.
     */
    @Test
    fun onARealThreadEditsFromManyThreadsEndInTheLastText() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "ink-test") }
        val scope = CoroutineScope(Job())
        try {
            val text = AtomicReference(LiveStories.forest)
            val session = LiveSession(
                scope = scope,
                engineDispatcher = executor.asCoroutineDispatcher(),
                sources = { InkSources("main.ink", mapOf("main.ink" to text.get())) },
                config = LiveConfig(debounceMillis = 40),
            )
            session.reloadNow()
            runBlocking {
                withTimeout(30_000) { session.state.first { it.compile == CompileStatus.Ok && !it.busy } }
                session.choose(session.state.value.transcript.choices[0])
                withTimeout(30_000) { session.state.first { it.transcript.liveTurn == 1 } }
            }
            val typists = (1..4).map { typist ->
                thread {
                    repeat(50) { i ->
                        text.set(LiveStories.forest.replace("A fork", "Typist $typist stroke $i"))
                        session.edited()
                        if (i % 10 == 0) Thread.sleep(3)
                    }
                }
            }
            typists.forEach { it.join() }
            val last = LiveStories.forest.replace("A fork", "The last word")
            text.set(last)
            session.edited()
            val settled = runBlocking {
                withTimeout(30_000) {
                    session.state.first { !it.busy && (it.transcript.items.firstOrNull() as? TranscriptItem.Text)?.text == "The last word in the path." }
                }
            }
            assertEquals(CompileStatus.Ok, settled.compile)
            // the choice was replayed, whole
            assertEquals(1, settled.transcript.liveTurn)
            assertEquals("You went left.", (settled.transcript.items[3] as TranscriptItem.Text).text)
            assertEquals(1, settled.transcript.choices.size)
            assertFalse(settled.busy)
        } finally {
            scope.cancel()
            executor.shutdown()
        }
    }
}
