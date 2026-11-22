package com.inkyandroid.core.engine

/**
 * The ink compiler and runtime, as the rest of the app sees them. [Default] is the embedded
 * library; the interface exists so that what sits on top (the live session) can be tested against
 * an engine that is slow or fails.
 *
 * **Threading.** Nothing here is thread-safe and nothing switches threads: call everything, from
 * [compile] to the last call on a [PlaySession], on one thread (a single-thread dispatcher). The
 * library keeps a lazily built static table that is not safe to build from two threads at once.
 *
 * **Failures.** No function here throws for anything the ink does or contains: bad ink, a story
 * that loops for ever, an error inside the library all come back as values.
 */
interface InkEngine {
    /**
     * Compiles [sources] the way Inky runs inklecate: counting all visits (`-c`), every `INCLUDE`
     * read from [sources] (a missing file is the compiler's own error "Failed to load"), and every
     * error, warning and author's note collected in the order the compiler reports them.
     *
     * Synchronous; it cannot be cancelled or interrupted. A large story (100 KB, 1,600 lines) takes
     * about 0.1 s on a warm JVM and 0.4 s the first time in a process.
     */
    fun compile(sources: InkSources): CompileResult

    /**
     * The numbers of inklecate `-s` ("Word count and more"), counted from a parse alone as
     * inklecate does, so they are available for ink that has errors too. Null only when the
     * compiler itself failed on the text (see [CompileResult.Crashed]).
     */
    fun statistics(sources: InkSources): InkStatistics?

    companion object {
        /** The embedded ink compiler and runtime (ink 1.2.0). */
        val Default: InkEngine get() = BladeInkEngine
    }
}

/** How a compilation ended. */
sealed interface CompileResult {
    /** Everything the compiler reported, in its order. */
    val issues: List<InkIssue>

    /** The ink compiled. [issues] holds warnings and TODOs at most. */
    class Success(val story: CompiledStory, override val issues: List<InkIssue>) : CompileResult

    /**
     * The ink has errors; [issues] holds at least one [IssueKind.Error]. As in ink itself, warnings,
     * TODOs and errors of meaning (unknown variables, missing targets) only appear once nothing
     * fails to parse.
     */
    class Failed(override val issues: List<InkIssue>) : CompileResult

    /**
     * The compiler itself failed: the equivalent of upstream's "the ink compiler crashed".
     *
     * @property message one line saying what failed (the exception's text).
     * @property diagnostic the details upstream would print under "Here is some diagnostic
     *   information:" (the exception and the top of its stack).
     */
    class Crashed(val message: String, val diagnostic: String, override val issues: List<InkIssue>) : CompileResult
}

/**
 * A story that compiled. It keeps the compiler's whole result alive (about four times the size of
 * the story), so let go of it when a newer compilation replaces it.
 */
interface CompiledStory {
    /** The sources this was compiled from. */
    val sources: InkSources

    /**
     * The story in ink's JSON format, exactly what "Export to JSON" writes (see `InkExport` for the
     * other exports). Built on first use and remembered.
     */
    fun toJson(): String

    /**
     * Starts a playthrough from the beginning. Sessions of one story are independent, but only one
     * of them may be in use at a time (see [InkEngine] on threading; evaluation briefly mounts
     * content in the tree all sessions share).
     */
    fun play(options: PlayOptions = PlayOptions()): PlaySession
}

/**
 * The bounds that keep a runaway story from hanging the player. The reference player has none: it
 * only slows down after 1,000 lines.
 *
 * @property maxLinesPerTurn lines one turn may produce before it is stopped with [StopReason.LineLimit].
 * @property turnBudgetMillis real time one line may take before the turn is stopped with [StopReason.TimeLimit].
 * @property evaluationBudgetMillis real time one evaluated expression may take.
 */
data class PlayLimits(
    val maxLinesPerTurn: Int = 5_000,
    val turnBudgetMillis: Long = 5_000,
    val evaluationBudgetMillis: Long = 500,
)

/**
 * @property seed the story's random seed (shuffles, `RANDOM`, `LIST_RANDOM`). The same story played
 *   with the same seed and the same choices produces the same text. Null: a new seed, as ink
 *   chooses one for every playthrough.
 */
data class PlayOptions(
    val limits: PlayLimits = PlayLimits(),
    val seed: Int? = null,
)

/**
 * One playthrough of a [CompiledStory]: what `inklecate -p` is to desktop Inky.
 *
 * A new session stands before the story's first line: call [continueToChoice] to produce the first
 * turn, [choose] one of its choices, [continueToChoice] again, and so on.
 */
interface PlaySession {
    /** The choices the story is waiting at; empty before the first turn, after the end, and between [choose] and [continueToChoice]. */
    val choices: List<StoryChoice>

    /** No more content and no choices: the story ended, a runtime error ended its flow, or a turn was stopped. */
    val ended: Boolean

    /** The tags at the very top of the main file (`# title: …`), before any content and before any `INCLUDE`. */
    val globalTags: List<String>

    /**
     * Runs the story to its next choice point or its end and returns what it produced. Runtime
     * errors and warnings are in the result, never thrown; a story that does not stop is cut off
     * by [PlayLimits]. Calling it again without a [choose] in between returns an empty turn with
     * the same choices.
     */
    fun continueToChoice(): StoryTurn

    /**
     * Takes the choice with this [StoryChoice.index]; the next [continueToChoice] plays on from it.
     * Returns false, changing nothing, when there is no such choice.
     */
    fun choose(index: Int): Boolean

    /**
     * Evaluates what a person types against the story as it stands:
     *
     * - an ink expression (`x + 1`, `LIST_COUNT(inventory)`, `some_knot`, `double(x)`): its value as
     *   ink prints it, after any text a called function printed;
     * - `-> path`: the story jumps there, and the next [continueToChoice] plays on from it.
     *
     * An expression never changes the story (variables, visit counts, random seed and output are as
     * before, also when a called function assigns something), so assignments are refused. Problems
     * (unknown names, a syntax error, a runtime error, taking longer than
     * [PlayLimits.evaluationBudgetMillis]) come back as [Evaluation.Failed].
     */
    fun evaluate(input: String): Evaluation

    /**
     * Evaluates a watch expression as upstream Inky does: [template] is the inside of an ink string,
     * so `x is {x}` gives `x is 5` and `{x}` gives `5`. Never diverts. (As upstream, a `"` in the
     * template ends the string early and is reported as an error.)
     */
    fun evaluateWatch(template: String): Evaluation

    /**
     * Where the content at an internal runtime path (`knot.0.c-1`, as runtime errors and "go to
     * anything" use them) was written; null when the path does not exist or has no known source.
     */
    fun locate(runtimePath: String): SourceLocation?

    /** The whole state of the playthrough as ink's save format (JSON). */
    fun saveState(): String

    /**
     * Returns to a state [saveState] gave, of this story. Returns false, leaving the session as it
     * was, when [state] cannot be read.
     */
    fun loadState(state: String): Boolean
}

/** What [PlaySession.evaluate] and [PlaySession.evaluateWatch] answer. */
sealed interface Evaluation {
    /** The value, printed as ink prints `{expression}`. [issues] holds runtime warnings, if any. */
    data class Value(val text: String, val issues: List<InkIssue> = emptyList()) : Evaluation

    /** `-> path` was followed; [path] is the runtime path the story now stands at. */
    data class Diverted(val path: String) : Evaluation

    /** Nothing was evaluated or changed. [issues] says why; it is never empty. */
    data class Failed(val issues: List<InkIssue>) : Evaluation {
        /** The first problem's text: what upstream shows in a watch's red pill. */
        val message: String get() = issues.firstOrNull()?.message.orEmpty()
    }
}

/**
 * inklecate's statistics. Upstream's note applies: "Words should be accurate. Knots include
 * functions. Gathers and diverts may include some implicitly added ones by the compiler, for
 * example in weave. Diverts include END and DONE."
 */
data class InkStatistics(
    val words: Int,
    val knots: Int,
    val stitches: Int,
    val functions: Int,
    val choices: Int,
    val gathers: Int,
    val diverts: Int,
)
