package com.inkyandroid.core.live

import com.inkyandroid.core.engine.CompileResult
import com.inkyandroid.core.engine.CompiledStory
import com.inkyandroid.core.engine.Evaluation
import com.inkyandroid.core.engine.InkEngine
import com.inkyandroid.core.engine.InkIssue
import com.inkyandroid.core.engine.InkSources
import com.inkyandroid.core.engine.InkStatistics
import com.inkyandroid.core.engine.IssueKind
import com.inkyandroid.core.engine.PlayLimits
import com.inkyandroid.core.engine.PlayOptions
import com.inkyandroid.core.engine.PlaySession
import com.inkyandroid.core.engine.SourceLocation
import com.inkyandroid.core.engine.StopReason
import com.inkyandroid.core.engine.StoryTurn
import com.inkyandroid.core.engine.diagnosticOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * @property debounceMillis the pause after the last edit before the ink is compiled again.
 *   Upstream checks every 250 ms for a pause of more than 500 ms.
 * @property limits the bounds on a runaway story.
 * @property newSeed gives the random seed of a playthrough. One seed is kept from a rewind to the
 *   next, so that recompiling replays the same shuffles and random numbers (upstream draws a new
 *   seed for every recompile, which changes text the player has already read).
 */
data class LiveConfig(
    val debounceMillis: Long = 500,
    val limits: PlayLimits = PlayLimits(),
    val newSeed: () -> Int = { kotlin.random.Random.nextInt(0, 100) },
)

/** What [LiveSession.exportJson] answers. */
sealed interface ExportResult {
    /** The compiled story as ink JSON: the text of "Export to JSON", and the input of `InkExport`. */
    data class Json(val json: String) : ExportResult

    /** Upstream: "Ink has errors - please fix them before exporting." */
    data class HasErrors(val issues: List<InkIssue>) : ExportResult

    /** Upstream: "Unexpected error". */
    data class Crashed(val message: String) : ExportResult
}

/**
 * The live session: upstream's `liveCompiler.js` over the embedded engine. It compiles the project
 * as it is edited, replays the player's choices through every new compilation and publishes what
 * the player shows as one immutable [state].
 *
 * **Threading.** Every function may be called from any thread and returns at once (the `suspend`
 * ones suspend, never block). All ink work runs on [engineDispatcher], which must run one thing at
 * a time (a single-thread dispatcher); [state] may be collected anywhere. [sources] is called on
 * the engine dispatcher: it must be safe to call from there (switch to the thread that owns the
 * project inside it).
 *
 * **Time.** The pause before a recompile is a `delay` on [engineDispatcher], so a test dispatcher
 * gives virtual time. (The bounds on a runaway story, [PlayLimits], are real time by nature.)
 *
 * **Lifetime.** The session lives in [scope]: cancelling the scope, or [close], ends it; after that
 * every call is ignored and the `suspend` functions throw `CancellationException`. Nothing is
 * compiled until the first [reloadNow] or [edited].
 *
 * **Failures.** Nothing the ink or the engine does escapes into [scope]: a failure becomes part of
 * [state] and the session goes on.
 *
 * How it works: one coroutine (the only code that touches the engine and the only writer of
 * [state]) takes commands from a queue, one at a time. The public functions only enqueue. An edit
 * also bumps a counter and starts the pause timer; the compile that follows remembers the counter
 * it started from, and is thrown away if the counter has moved when it returns.
 *
 * @param sources gives the project's text at the moment it is called.
 */
class LiveSession(
    scope: CoroutineScope,
    private val engineDispatcher: CoroutineDispatcher,
    private val sources: suspend () -> InkSources,
    private val engine: InkEngine = InkEngine.Default,
    private val config: LiveConfig = LiveConfig(),
) {
    private val published = MutableStateFlow(LiveState())

    /** What the player, the issue list and the busy indicator show. */
    val state: StateFlow<LiveState> = published.asStateFlow()

    private val commands = Channel<Command>(Channel.UNLIMITED)

    /** Counts the changes that call for a compile or replay after a pause. Written from any thread. */
    private val changes = AtomicLong(0)

    /**
     * Held while an edit is counted and its pause timer replaces the previous one, so that the
     * timer left running always belongs to the latest change. (Without it, two threads editing at
     * the same moment could leave the older change's timer, whose pause then answers nothing, and
     * the session would stay busy until the next edit.)
     */
    private val pauseLock = Any()

    /** The running pause timer, if any. Guarded by [pauseLock]. */
    private var pause: Job? = null

    // Everything below is touched by the session's coroutine alone.

    /** The value of [changes] that the transcript and issues in [state] answer. */
    private var answered = 0L

    /** The latest compilation and what it was compiled from: an unchanged project is not compiled twice. */
    private var compiledSources: InkSources? = null
    private var compiled: CompileResult? = null

    /** The story being played: the last one that compiled. */
    private var story: CompiledStory? = null
    private var play: PlaySession? = null

    /** The choices the player took, by index, one per finished turn. */
    private var choices = ArrayList<Int>()

    /** What each turn of the playthrough produced: the transcript is built from these. */
    private var turns = ArrayList<TurnRecord>()

    /** The choices, the watches or the seed changed since [turns] was played. */
    private var replayNeeded = false
    private var watches: List<String> = emptyList()
    private var instructionPrefix: String? = null
    private var seed = config.newSeed()
    private var generation = 0L

    /** The session's own lifetime inside the caller's scope: [close] ends this and nothing else. */
    private val job: Job = Job(scope.coroutineContext[Job])
    private val scope = CoroutineScope(scope.coroutineContext + job + engineDispatcher)

    init {
        this.scope.launch { run() }
    }

    /**
     * Something the story depends on changed (a file's text, the set of files). The ink is compiled
     * again once [LiveConfig.debounceMillis] have passed without another call; typing without a
     * pause never compiles. A compilation that an edit overtakes is thrown away when it returns.
     */
    fun edited() {
        synchronized(pauseLock) {
            val change = changes.incrementAndGet()
            commands.trySend(Command.Changed)
            // Started on the caller's thread, so the pause counts from the edit even while the engine
            // thread is busy compiling. (It runs here only until `delay` suspends.)
            val timer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                delay(config.debounceMillis)
                commands.send(Command.PauseElapsed(change))
            }
            pause?.cancel()
            pause = timer
        }
    }

    /**
     * Compiles and replays now, without waiting for a pause: for the first load of a project and
     * for "reload". An unchanged project is not compiled twice; its story is only replayed.
     */
    fun reloadNow() {
        commands.trySend(Command.Reload)
    }

    /**
     * Takes [choice]: the story plays on and the new turn is appended to the transcript
     * ([PlayerTranscript.appendedFrom]). Ignored when [choice] is not one of the choices the
     * session's story currently offers (it came from a transcript that has since been replaced by
     * a different story).
     */
    fun choose(choice: PlayerChoice) {
        commands.trySend(Command.Choose(choice))
    }

    /** Takes back the last choice (upstream's "rewind a single choice") and replays now. Without a choice to take back it only reloads. */
    fun stepBack() {
        commands.trySend(Command.StepBack)
    }

    /** Forgets every choice and plays from the start with a new random seed (upstream's "restart story"), now. */
    fun rewind() {
        commands.trySend(Command.Rewind)
    }

    /**
     * Sets the watch expressions, evaluated after every turn (`x is {x}`: the inside of an ink
     * string). Line breaks become spaces as upstream's field does; blank entries are dropped. The
     * transcript follows after the usual pause, without compiling when the ink is unchanged.
     */
    fun setWatches(expressions: List<String>) {
        val cleaned = expressions.map { it.replace(LINE_BREAKS, " ") }.filter { it.isNotBlank() }
        commands.trySend(Command.SetWatches(cleaned))
        edited()
    }

    /**
     * Sets the project's `instructionPrefix` (null or empty: none). Only [TranscriptItem.Text.isInstruction]
     * depends on it; the transcript shown is updated in place, nothing is compiled.
     */
    fun setInstructionPrefix(prefix: String?) {
        commands.trySend(Command.SetInstructionPrefix(prefix?.takeIf { it.isNotEmpty() }))
    }

    /**
     * "Word count and more" for the project as it is now. Null only when the compiler fails on the
     * text altogether.
     */
    suspend fun statistics(): InkStatistics? = ask { Command.Statistics(it) }

    /**
     * The compiled JSON of the project as it is now, for the three exports. Uses the live
     * compilation when the project has not changed since, otherwise compiles afresh (without
     * disturbing the player).
     */
    suspend fun exportJson(): ExportResult = ask { Command.Export(it) }

    /**
     * Where the content at an internal runtime path (`knot.0.c-1`: upstream's "go to anything"
     * accepts them) was written, in the story being played; null when unknown.
     */
    suspend fun locateRuntimePath(path: String): SourceLocation? = ask { Command.Locate(path, it) }

    /** Ends the session. The last [state] stays readable. */
    fun close() {
        job.cancel()
    }

    // ---------------------------------------------------------------------------------------------
    // The session's coroutine

    private sealed interface Command {
        /** [changes] moved: the state is now behind. */
        data object Changed : Command

        /** The pause after change number [change] is over. */
        class PauseElapsed(val change: Long) : Command
        data object Reload : Command
        class Choose(val choice: PlayerChoice) : Command
        data object StepBack : Command
        data object Rewind : Command
        class SetWatches(val expressions: List<String>) : Command
        class SetInstructionPrefix(val prefix: String?) : Command
        class Statistics(val reply: CompletableDeferred<InkStatistics?>) : Command
        class Export(val reply: CompletableDeferred<ExportResult>) : Command
        class Locate(val path: String, val reply: CompletableDeferred<SourceLocation?>) : Command
    }

    /** What one turn produced: the story's output and the watches' values after it. */
    private class TurnRecord(val turn: StoryTurn, val evaluations: List<Pair<String, Evaluation>>)

    private suspend fun <T> ask(command: (CompletableDeferred<T>) -> Command): T {
        // A child of the session: when the session ends, whoever waits is cancelled instead of left waiting.
        val reply = CompletableDeferred<T>(job)
        commands.trySend(command(reply))
        return reply.await()
    }

    private suspend fun run() {
        for (command in commands) {
            try {
                handle(command)
            } catch (e: Throwable) {
                if (endsSession(e)) throw e
                // Every handler already turns what the engine may do into state; this is for what
                // nobody foresaw. The session must outlive it.
                failUnexpectedly(command, e)
            }
        }
    }

    /**
     * The session itself was cancelled (its scope ended, or [close]): the one failure that ends it.
     * Any other cancellation that reaches it, such as a timeout inside [sources] or a scope of the
     * caller's that ended there, is a failure like any other, or the session would stop compiling
     * for good without a word.
     */
    private fun endsSession(failure: Throwable): Boolean = failure is CancellationException && !job.isActive

    private suspend fun handle(command: Command) {
        when (command) {
            Command.Changed -> publishBusy()
            is Command.PauseElapsed ->
                // Only the pause after the latest change counts, and only if nothing answered it meanwhile.
                if (command.change == changes.get() && command.change != answered) refresh() else publishBusy()
            Command.Reload -> {
                replayNeeded = true
                refresh()
            }
            Command.StepBack -> {
                choices.removeLastOrNull()
                replayNeeded = true
                refresh()
            }
            Command.Rewind -> {
                choices.clear()
                seed = config.newSeed()
                replayNeeded = true
                refresh()
            }
            is Command.Choose -> take(command.choice)
            is Command.SetWatches -> if (command.expressions != watches) {
                watches = command.expressions
                replayNeeded = true
                published.value = published.value.copy(watches = watches)
            }
            is Command.SetInstructionPrefix -> if (command.prefix != instructionPrefix) {
                instructionPrefix = command.prefix
                if (turns.isNotEmpty()) publishTranscript(appendedFrom = published.value.transcript.appendedFrom)
            }
            is Command.Statistics -> command.reply.complete(guarded(null) { engine.statistics(sources()) })
            is Command.Export -> command.reply.complete(export())
            is Command.Locate -> command.reply.complete(guarded(null) { play?.locate(command.path) })
        }
    }

    /** Busy is "the state is behind the project": a change is waiting for its pause or its compile. */
    private fun publishBusy(busy: Boolean = changes.get() != answered) {
        if (published.value.busy != busy) published.value = published.value.copy(busy = busy)
    }

    /** Brings the state up to the project as it is now: compile unless unchanged, replay, publish. */
    private suspend fun refresh() {
        val change = changes.get()
        publishBusy(true)
        val result = try {
            compile(sources())
        } catch (e: Throwable) {
            if (endsSession(e)) throw e
            crashed(e)
        }
        // An edit arrived while this ran: its text is not what was compiled. Stay busy; the pause
        // after that edit ends in another refresh.
        if (changes.get() != change) return
        answered = change

        when (result) {
            is CompileResult.Success -> {
                if (result.story !== story || replayNeeded) {
                    story = result.story
                    replay(result.story)
                }
                published.value = published.value.copy(
                    busy = false,
                    compile = CompileStatus.Ok,
                    issues = result.issues + turns.flatMap { it.turn.allIssues },
                    transcript = if (generationPublished()) published.value.transcript else transcript(appendedFrom = null),
                )
            }
            is CompileResult.Failed -> {
                val playing = story
                when {
                    // The player stays on the last story that compiled, and stays usable there.
                    playing != null && replayNeeded -> replay(playing)
                    // A crash report is not a story to stay on.
                    playing == null && published.value.transcript.items.isNotEmpty() -> generation++
                }
                published.value = published.value.copy(
                    busy = false,
                    compile = CompileStatus.Errors,
                    issues = result.issues,
                    transcript = if (generationPublished()) published.value.transcript else transcript(appendedFrom = null),
                )
            }
            is CompileResult.Crashed -> {
                story = null
                play = null
                turns = ArrayList()
                generation++
                published.value = published.value.copy(
                    busy = false,
                    compile = CompileStatus.Crashed,
                    issues = result.issues,
                    transcript = PlayerTranscript(
                        items = listOf(TranscriptItem.CompilerCrash(key = 0, turn = 0, result.message, result.diagnostic)),
                        choices = emptyList(),
                        liveTurn = 0,
                        generation = generation,
                    ),
                )
            }
        }
    }

    /** The transcript in [state] already is the playthrough in [turns]: nothing was replayed. */
    private fun generationPublished(): Boolean = published.value.transcript.generation == generation

    private fun compile(current: InkSources): CompileResult {
        compiled?.let { if (current == compiledSources) return it }
        val result = try {
            engine.compile(current)
        } catch (e: Throwable) {
            crashed(e)
        }
        // A crash is not remembered: asking again (reload, step back) is the way to try again.
        if (result is CompileResult.Crashed) {
            compiledSources = null
            compiled = null
        } else {
            compiledSources = current
            compiled = result
        }
        return result
    }

    private fun crashed(failure: Throwable): CompileResult.Crashed =
        CompileResult.Crashed(failure.message ?: failure.javaClass.simpleName, diagnosticOf(failure), emptyList())

    /**
     * Plays [story] from the start through the remembered choices. A choice that is no longer
     * there (or a story that now ends earlier) stops the replay at that turn, and the choices after
     * it are forgotten: the transcript ends where the player can go on from.
     */
    private fun replay(story: CompiledStory) {
        val played = ArrayList<TurnRecord>()
        val kept = ArrayList<Int>()
        var session: PlaySession? = null
        try {
            session = story.play(PlayOptions(config.limits, seed))
            played += record(session, session.continueToChoice())
            for (choice in choices) {
                if (!session.choose(choice)) break
                kept += choice
                played += record(session, session.continueToChoice())
            }
        } catch (e: Throwable) {
            played += failedTurn(e)
            session = null
        }
        play = session
        choices = kept
        turns = played
        replayNeeded = false
        generation++
    }

    private fun take(choice: PlayerChoice) {
        val session = play ?: return
        // The choice must be one the story offers now: same turn, same place, same words.
        if (choice.turn != turns.lastIndex) return
        if (session.choices.getOrNull(choice.index)?.text != choice.text) return
        val appendedFrom = published.value.transcript.items.size
        try {
            if (!session.choose(choice.index)) return
            choices += choice.index
            turns += record(session, session.continueToChoice())
        } catch (e: Throwable) {
            turns += failedTurn(e)
            play = null
        }
        publishTranscript(appendedFrom)
    }

    private fun record(session: PlaySession, turn: StoryTurn): TurnRecord {
        // A stopped story has nothing sound to evaluate against.
        val evaluations = if (turn.stop != null) emptyList() else watches.map { it to session.evaluateWatch(it) }
        return TurnRecord(turn, evaluations)
    }

    /** A turn that is nothing but the engine's failure, so that the transcript says what happened. */
    private fun failedTurn(failure: Throwable): TurnRecord {
        val message = failure.message ?: failure.javaClass.simpleName
        val turn = StoryTurn(
            lines = emptyList(),
            choices = emptyList(),
            ended = true,
            stop = StopReason.EngineFailure,
            issues = listOf(InkIssue(IssueKind.RuntimeError, null, null, message)),
        )
        return TurnRecord(turn, emptyList())
    }

    private fun publishTranscript(appendedFrom: Int?) {
        val current = published.value
        published.value = current.copy(
            transcript = transcript(appendedFrom),
            // While the ink has errors the story shown is an older one: its runtime issues are not issues of the ink as it is.
            issues = if (current.compile == CompileStatus.Ok) compiled?.issues.orEmpty() + turns.flatMap { it.turn.allIssues } else current.issues,
        )
    }

    private fun transcript(appendedFrom: Int?): PlayerTranscript {
        val liveTurn = (turns.size - 1).coerceAtLeast(0)
        return PlayerTranscript(
            items = items(),
            choices = play?.choices.orEmpty().map { PlayerChoice(it.index, it.text, it.tags, liveTurn) },
            liveTurn = liveTurn,
            generation = generation,
            appendedFrom = appendedFrom,
        )
    }

    private fun items(): List<TranscriptItem> {
        val items = ArrayList<TranscriptItem>()
        turns.forEachIndexed { turnIndex, record ->
            var slot = 0L
            fun key(): Long = (turnIndex.toLong() shl TURN_SHIFT) or slot++
            for (line in record.turn.lines) {
                val text = line.text.trimEnd('\n', '\r')
                if (text.isNotBlank()) {
                    items += TranscriptItem.Text(key(), turnIndex, text, line, isInstruction(text))
                }
                if (line.tags.isNotEmpty()) items += TranscriptItem.Tags(key(), turnIndex, line.tags, line.source)
                for (issue in line.issues) items += TranscriptItem.RuntimeIssue(key(), turnIndex, issue)
            }
            for (issue in record.turn.issues) items += TranscriptItem.RuntimeIssue(key(), turnIndex, issue)
            for ((expression, evaluation) in record.evaluations) {
                items += when (evaluation) {
                    is Evaluation.Value -> TranscriptItem.Evaluation(key(), turnIndex, expression, evaluation.text, null)
                    is Evaluation.Failed -> TranscriptItem.Evaluation(key(), turnIndex, expression, null, evaluation.message)
                    is Evaluation.Diverted -> TranscriptItem.Evaluation(key(), turnIndex, expression, null, evaluation.path)
                }
            }
            val stop = record.turn.stop
            when {
                stop != null -> {
                    val detail = record.turn.issues.lastOrNull()?.message.takeIf { stop == StopReason.EngineFailure }
                    items += TranscriptItem.Stopped(key(), turnIndex, stop, detail)
                }
                record.turn.ended -> items += TranscriptItem.End(key(), turnIndex)
            }
            if (turnIndex < turns.lastIndex) {
                items += TranscriptItem.Divider((turnIndex.toLong() shl TURN_SHIFT) or DIVIDER_SLOT, turnIndex)
            }
        }
        return items
    }

    // upstream, playerView.js: text.trim().startsWith(instructionPrefix)
    private fun isInstruction(text: String): Boolean =
        instructionPrefix?.let { text.trim().startsWith(it) } ?: false

    private suspend fun export(): ExportResult = try {
        when (val result = compile(sources())) {
            is CompileResult.Success -> ExportResult.Json(result.story.toJson())
            is CompileResult.Failed -> ExportResult.HasErrors(result.issues)
            is CompileResult.Crashed -> ExportResult.Crashed(result.message)
        }
    } catch (e: Throwable) {
        if (endsSession(e)) throw e
        ExportResult.Crashed(e.message ?: e.javaClass.simpleName)
    }

    private suspend fun <T> guarded(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: Throwable) {
        if (endsSession(e)) throw e
        fallback
    }

    private fun failUnexpectedly(command: Command, failure: Throwable) {
        when (command) {
            is Command.Statistics -> command.reply.complete(null)
            is Command.Export -> command.reply.complete(ExportResult.Crashed(failure.message ?: failure.javaClass.simpleName))
            is Command.Locate -> command.reply.complete(null)
            else -> Unit
        }
        answered = changes.get()
        turns = ArrayList<TurnRecord>().apply { add(failedTurn(failure)) }
        play = null
        generation++
        published.value = published.value.copy(busy = false, transcript = transcript(appendedFrom = null))
    }

    private companion object {
        /** A turn's items are numbered within the turn; the turn is the upper half of the key. */
        const val TURN_SHIFT = 32
        const val DIVIDER_SLOT = 0xFFFF_FFFFL
        val LINE_BREAKS = Regex("[\\r\\n]+")
    }
}
