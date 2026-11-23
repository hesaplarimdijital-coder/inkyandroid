package com.inkyandroid.core.live

import com.inkyandroid.core.engine.InkIssue
import com.inkyandroid.core.engine.SourceLocation
import com.inkyandroid.core.engine.StopReason
import com.inkyandroid.core.engine.StoryLine

/**
 * Everything the live session shows, at one moment. Immutable; a new one is published for every
 * change, and each one is consistent in itself (the transcript, its choices and the issues belong
 * together).
 *
 * @property busy an edit is waiting to be compiled, or a compile or replay is running: upstream's
 *   busy spinner.
 * @property compile how the latest compilation ended.
 * @property issues the issues of the latest compilation followed by the runtime issues of the
 *   playthrough shown, in the order they were reported (`InkIssues.sorted` gives upstream's list
 *   order). While the ink has errors these are the compiler's issues alone: the transcript then
 *   shows an older story.
 * @property transcript what the player shows.
 * @property watches the watch expressions in use (as [LiveSession.setWatches] cleaned them).
 */
data class LiveState(
    val busy: Boolean = false,
    val compile: CompileStatus = CompileStatus.None,
    val issues: List<InkIssue> = emptyList(),
    val transcript: PlayerTranscript = PlayerTranscript.Empty,
    val watches: List<String> = emptyList(),
) {
    /** There is a choice to take back: "step back" does something. */
    val canStepBack: Boolean get() = transcript.liveTurn > 0

    /**
     * The transcript plays the ink as it is now. False while the ink has errors (the transcript is
     * the last story that compiled, still playable) and before the first compilation.
     */
    val transcriptIsCurrent: Boolean get() = compile == CompileStatus.Ok
}

/** How the latest compilation ended. */
enum class CompileStatus {
    /** Nothing was compiled yet. */
    None,

    /** The ink compiled; the transcript plays it. */
    Ok,

    /** The ink has errors; the transcript still shows the last story that compiled. */
    Errors,

    /** The compiler itself failed; the transcript holds one [TranscriptItem.CompilerCrash]. */
    Crashed,
}

/**
 * What the player shows: upstream's play view as data.
 *
 * A **turn** is everything the story produces from one choice point to the next. Turn `k` ends
 * with the player's `k`-th choice; the last turn, [liveTurn], is the one that waits for a choice
 * (or ended the story). [items] holds all turns in display order: for each turn its text lines,
 * tag lines and runtime issues as they came, then one [TranscriptItem.Evaluation] per watch, then
 * [TranscriptItem.End] or [TranscriptItem.Stopped] if the story is over; between two turns a
 * [TranscriptItem.Divider]. [choices] belong to the live turn and are shown after the items.
 *
 * @property liveTurn the index of the last turn, which is the number of choices taken so far.
 * @property generation counts the playthroughs built from the start (every compile, step back and
 *   rewind builds one and publishes it whole). It does not change when a choice appends a turn.
 * @property appendedFrom when this transcript came from taking a choice: the index in [items] of
 *   the first new item (the divider that closes the turn before), so that the player can fade in
 *   and scroll to what is new. Null when the whole transcript was replaced (a recompile, step back,
 *   rewind), which the player shows without animation and without moving the scroll position.
 *   The value stays until the transcript changes again, so act on it once per [generation] and
 *   [liveTurn].
 */
data class PlayerTranscript(
    val items: List<TranscriptItem>,
    val choices: List<PlayerChoice>,
    val liveTurn: Int,
    val generation: Long,
    val appendedFrom: Int? = null,
) {
    /** The story is over: it ended, it was stopped, or the compiler crashed. Nothing more can be chosen. */
    val isOver: Boolean
        get() = when (items.lastOrNull()) {
            is TranscriptItem.End, is TranscriptItem.Stopped, is TranscriptItem.CompilerCrash -> true
            else -> false
        }

    companion object {
        /** Before the first compilation: nothing to show. */
        val Empty = PlayerTranscript(items = emptyList(), choices = emptyList(), liveTurn = 0, generation = 0)
    }
}

/**
 * A choice of the live turn. Hand it back to [LiveSession.choose] as it is: the session checks
 * that it still is a choice of the story it plays.
 *
 * @property index 0-based position among the turn's choices.
 * @property turn the turn it belongs to ([PlayerTranscript.liveTurn] when it was published).
 */
data class PlayerChoice(
    val index: Int,
    val text: String,
    val tags: List<String>,
    val turn: Int,
)

/**
 * One thing in the player. [key] is stable for the same place in the playthrough (turn and
 * position in the turn) across recompiles, so a list keyed by it swaps a recompiled transcript in
 * place.
 */
sealed interface TranscriptItem {
    val key: Long

    /** The turn the item belongs to; a [Divider] belongs to the turn it closes. */
    val turn: Int

    /**
     * A paragraph of story text.
     *
     * @property text the line without its line end; never blank (a line without text shows only
     *   its tags and issues).
     * @property line the line as the engine produced it, with where each character came from.
     *   Offsets into [text] are offsets into `line.text`.
     * @property isInstruction the text starts with the project's `instructionPrefix` (upstream
     *   sets such lines apart, e.g. `>>> CAMERA: wide shot`).
     */
    data class Text(
        override val key: Long,
        override val turn: Int,
        val text: String,
        val line: StoryLine,
        val isInstruction: Boolean,
    ) : TranscriptItem {
        /** The file and line the paragraph starts at, for "jump to source"; null when unknown. */
        val source: SourceLocation? get() = line.source

        /** The file and line that produced the character at [offset] of [text] (a tap on a word). */
        fun sourceAt(offset: Int): SourceLocation? = line.sourceAt(offset)
    }

    /** The tags of the line above it (or of a line without text). Upstream shows `# a, b`. */
    data class Tags(
        override val key: Long,
        override val turn: Int,
        val tags: List<String>,
        val source: SourceLocation?,
    ) : TranscriptItem

    /** A runtime error or warning, where the story was when it happened. Upstream shows `Line N: message` as a link. */
    data class RuntimeIssue(
        override val key: Long,
        override val turn: Int,
        val issue: InkIssue,
    ) : TranscriptItem

    /**
     * The value of a watch expression after this turn. Exactly one of [result] and [error] is set.
     *
     * @property expression the watch as the user wrote it.
     */
    data class Evaluation(
        override val key: Long,
        override val turn: Int,
        val expression: String,
        val result: String?,
        val error: String?,
    ) : TranscriptItem

    /** The line between two turns. */
    data class Divider(override val key: Long, override val turn: Int) : TranscriptItem

    /** "End of story". */
    data class End(override val key: Long, override val turn: Int) : TranscriptItem

    /**
     * The story was cut off: it produced lines without end or took too long ([StopReason.LineLimit],
     * [StopReason.TimeLimit]; `- (top) again -> top`), it called itself without end
     * ([StopReason.DepthLimit]; a function that returns a call of itself), or the runtime failed
     * ([StopReason.EngineFailure], with the failure's text in [detail] and as a [RuntimeIssue] above).
     * Localise by [reason]; [detail] is null except for [StopReason.EngineFailure].
     */
    data class Stopped(
        override val key: Long,
        override val turn: Int,
        val reason: StopReason,
        val detail: String?,
    ) : TranscriptItem

    /**
     * The compiler itself failed (upstream: "Sorry, the ink compiler crashed", then "Here is some
     * diagnostic information:" and the text). The only item of its transcript.
     */
    data class CompilerCrash(
        override val key: Long,
        override val turn: Int,
        val message: String,
        val diagnostic: String,
    ) : TranscriptItem
}
