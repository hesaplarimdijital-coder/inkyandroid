package com.inkyandroid.core.live

import com.inkyandroid.core.engine.InkEngine
import com.inkyandroid.core.engine.InkIssue
import com.inkyandroid.core.engine.InkSources
import com.inkyandroid.core.engine.InkStatistics
import com.inkyandroid.core.engine.PlayLimits
import com.inkyandroid.core.engine.SourceLocation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

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
 * **Lifetime.** The session lives in [scope]: cancelling the scope, or [close], ends it; after that
 * every call is ignored and the `suspend` functions throw `CancellationException`. Nothing is
 * compiled until the first [reloadNow] or [edited].
 *
 * @param sources gives the project's text at the moment it is called.
 */
class LiveSession(
    private val scope: CoroutineScope,
    private val engineDispatcher: CoroutineDispatcher,
    private val sources: suspend () -> InkSources,
    private val engine: InkEngine = InkEngine.Default,
    private val config: LiveConfig = LiveConfig(),
) {
    /** What the player, the issue list and the busy indicator show. */
    val state: StateFlow<LiveState> get() = TODO()

    /**
     * Something the story depends on changed (a file's text, the set of files). The ink is compiled
     * again once [LiveConfig.debounceMillis] have passed without another call; typing without a
     * pause never compiles. A compilation that an edit overtakes is thrown away when it returns.
     */
    fun edited(): Unit = TODO()

    /**
     * Compiles and replays now, without waiting for a pause: for the first load of a project and
     * for "reload". An unchanged project is not compiled twice; its story is only replayed.
     */
    fun reloadNow(): Unit = TODO()

    /**
     * Takes [choice]: the story plays on and the new turn is appended to the transcript
     * ([PlayerTranscript.appendedFrom]). Ignored when [choice] is not one of the choices the
     * session's story currently offers (it came from a transcript that has since been replaced by
     * a different story).
     */
    fun choose(choice: PlayerChoice): Unit = TODO()

    /** Takes back the last choice (upstream's "rewind a single choice") and replays now. Without a choice to take back it only reloads. */
    fun stepBack(): Unit = TODO()

    /** Forgets every choice and plays from the start with a new random seed (upstream's "restart story"), now. */
    fun rewind(): Unit = TODO()

    /**
     * Sets the watch expressions, evaluated after every turn (`x is {x}`: the inside of an ink
     * string). Line breaks become spaces as upstream's field does; blank entries are dropped. The
     * transcript follows after the usual pause, without compiling when the ink is unchanged.
     */
    fun setWatches(expressions: List<String>): Unit = TODO()

    /**
     * Sets the project's `instructionPrefix` (null or empty: none). Only [TranscriptItem.Text.isInstruction]
     * depends on it; the transcript shown is updated in place, nothing is compiled.
     */
    fun setInstructionPrefix(prefix: String?): Unit = TODO()

    /**
     * "Word count and more" for the project as it is now. Null only when the compiler fails on the
     * text altogether.
     */
    suspend fun statistics(): InkStatistics? = TODO()

    /**
     * The compiled JSON of the project as it is now, for the three exports. Uses the live
     * compilation when the project has not changed since, otherwise compiles afresh (without
     * disturbing the player).
     */
    suspend fun exportJson(): ExportResult = TODO()

    /**
     * Where the content at an internal runtime path (`knot.0.c-1`: upstream's "go to anything"
     * accepts them) was written, in the story being played; null when unknown.
     */
    suspend fun locateRuntimePath(path: String): SourceLocation? = TODO()

    /** Ends the session. The last [state] stays readable. */
    fun close(): Unit = TODO()
}
