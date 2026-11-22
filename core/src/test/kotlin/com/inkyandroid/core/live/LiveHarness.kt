package com.inkyandroid.core.live

import com.inkyandroid.core.engine.CompileResult
import com.inkyandroid.core.engine.CompiledStory
import com.inkyandroid.core.engine.InkEngine
import com.inkyandroid.core.engine.InkSources
import com.inkyandroid.core.engine.InkStatistics
import com.inkyandroid.core.engine.PlayOptions
import com.inkyandroid.core.engine.PlaySession
import com.inkyandroid.core.engine.StoryTurn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * The real engine with a window into it: counts what the session asks for, and can be made to
 * fail or to see an edit arrive in the middle of a compile.
 */
class ObservedEngine(private val real: InkEngine = InkEngine.Default) : InkEngine {
    var compiles = 0
    var statisticsCalls = 0
    val compiledTexts = ArrayList<String>()
    val seeds = ArrayList<Int?>()

    /** Runs while a compile is under way: what an edit on another thread would do then. */
    var duringCompile: () -> Unit = {}

    /** Thrown by the next compiles while set. */
    var compileFailure: Throwable? = null

    /** Thrown when a playthrough is started, while set. */
    var playFailure: Throwable? = null

    /** Thrown by a playthrough's next turns while set. */
    var turnFailure: Throwable? = null

    override fun compile(sources: InkSources): CompileResult {
        compiles++
        compiledTexts += sources.texts.getValue(sources.mainPath)
        compileFailure?.let { throw it }
        val result = real.compile(sources)
        duringCompile()
        return when (result) {
            is CompileResult.Success -> CompileResult.Success(ObservedStory(result.story), result.issues)
            else -> result
        }
    }

    override fun statistics(sources: InkSources): InkStatistics? {
        statisticsCalls++
        return real.statistics(sources)
    }

    private inner class ObservedStory(private val story: CompiledStory) : CompiledStory by story {
        override fun play(options: PlayOptions): PlaySession {
            seeds += options.seed
            playFailure?.let { throw it }
            return ObservedPlay(story.play(options))
        }
    }

    private inner class ObservedPlay(private val play: PlaySession) : PlaySession by play {
        override fun continueToChoice(): StoryTurn {
            turnFailure?.let { throw it }
            return play.continueToChoice()
        }
    }
}

/** A live session over a project of files held here, on the test's virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveHarness(
    private val test: TestScope,
    mainText: String,
    val engine: ObservedEngine = ObservedEngine(),
    config: LiveConfig = LiveConfig(newSeed = { 42 }),
) {
    val files = LinkedHashMap<String, String>().apply { put("main.ink", mainText) }

    /** Thrown by the sources provider while set. */
    var sourcesFailure: Throwable? = null
    var sourcesCalls = 0

    val session = LiveSession(
        scope = test.backgroundScope,
        engineDispatcher = StandardTestDispatcher(test.testScheduler),
        sources = {
            sourcesCalls++
            sourcesFailure?.let { throw it }
            InkSources("main.ink", files.toMap())
        },
        engine = engine,
        config = config,
    )

    val state: LiveState get() = session.state.value
    val transcript: PlayerTranscript get() = state.transcript

    /** Lets everything that is due now run, without moving the clock. */
    fun settle() = test.runCurrent()

    /** Moves the clock by [millis] and runs what became due, also what is due exactly then. */
    fun advance(millis: Long) {
        test.advanceTimeBy(millis)
        test.runCurrent()
    }

    /** The first load of a project. */
    fun load(): LiveHarness {
        session.reloadNow()
        settle()
        return this
    }

    /** Types [text] into the main file. */
    fun type(text: String) {
        files["main.ink"] = text
        session.edited()
    }

    /** Types [text] and waits for the pause and what follows it. */
    fun typeAndWait(text: String) {
        type(text)
        advance(500)
    }

    /** Takes the live turn's choice whose text is [text]. */
    fun choose(text: String) {
        session.choose(transcript.choices.single { it.text == text })
        settle()
    }

    /** The transcript as a reader of the test would write it down. */
    fun shown(transcript: PlayerTranscript = this.transcript): List<String> =
        transcript.items.map(::describe) + transcript.choices.map { "* ${it.text}" + it.tags.joinToString("") { tag -> " #$tag" } }

    companion object {
        fun describe(item: TranscriptItem): String = when (item) {
            is TranscriptItem.Text -> if (item.isInstruction) ">> ${item.text}" else item.text
            is TranscriptItem.Tags -> "# " + item.tags.joinToString(", ")
            is TranscriptItem.RuntimeIssue -> "${item.issue.kind}: ${item.issue.message}"
            is TranscriptItem.Evaluation -> item.result?.let { "= $it" } ?: "! ${item.error}"
            is TranscriptItem.Divider -> "---"
            is TranscriptItem.End -> "END"
            is TranscriptItem.Stopped -> "STOPPED ${item.reason}" + (item.detail?.let { ": $it" } ?: "")
            is TranscriptItem.CompilerCrash -> "CRASH ${item.message}"
        }
    }
}

/** The stories the live session's tests play. */
object LiveStories {
    val forest = """
        VAR x = 0
        A fork in the path. # opening
        * [Left] -> left
        * [Right] -> right
        == left ==
        ~ x = 1
        You went left.
        * [On] -> finale
        == right ==
        ~ x = 2
        You went right.
        * [On] -> finale
        * [Back] -> left
        == finale ==
        The end of it.
        -> END
    """.trimIndent() + "\n"

    /** A story whose every turn depends on the random seed. */
    val dice = """
        - (top) Rolled {RANDOM(1, 1000000)}.
        + [Again] -> top
    """.trimIndent() + "\n"
}
