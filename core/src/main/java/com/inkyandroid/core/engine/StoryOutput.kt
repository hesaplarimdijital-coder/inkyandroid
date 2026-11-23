package com.inkyandroid.core.engine

/** A place in the project's ink: the project path of a file and a 1-based line. */
data class SourceLocation(val file: String, val line: Int)

/** Characters `[start, end)` of a line's text, produced by the ink at [location] (null: unknown). */
data class SourceSpan(val start: Int, val end: Int, val location: SourceLocation?)

/**
 * One line of story output: what one step of the story produced.
 *
 * @property text the line as the runtime gives it, with its trailing `\n`. May be empty: a line of
 *   tags alone, or the line in which a runtime error happened.
 * @property tags the tags of the line (`# tag`), in order.
 * @property spans which ink produced which characters of [text]: one line of output mixes sources
 *   when it has inline logic, function calls or glue. Sorted, not overlapping, covering the text.
 * @property issues runtime errors and warnings raised while this line was produced.
 */
data class StoryLine(
    val text: String,
    val tags: List<String> = emptyList(),
    val spans: List<SourceSpan> = emptyList(),
    val issues: List<InkIssue> = emptyList(),
) {
    /** Where the line starts in the ink: what activating the line as a whole opens. */
    val source: SourceLocation? get() = spans.firstNotNullOfOrNull { it.location }

    /** Where the character at [offset] of [text] came from; [source] when that is unknown or [offset] is outside the text. */
    fun sourceAt(offset: Int): SourceLocation? =
        spans.firstOrNull { offset >= it.start && offset < it.end }?.location ?: source
}

/** A choice the story offers. [index] is what [PlaySession.choose] takes (0-based, the order ink lists them). */
data class StoryChoice(val index: Int, val text: String, val tags: List<String> = emptyList())

/** Why a turn was cut short instead of reaching choices or the end of the story. */
enum class StopReason {
    /** More than [PlayLimits.maxLinesPerTurn] lines without a choice: the story most likely loops. */
    LineLimit,

    /** The turn took longer than [PlayLimits.turnBudgetMillis]: the story most likely loops without output. */
    TimeLimit,

    /**
     * The runtime threw instead of reporting an error (an integer division by zero, a stack
     * overflow). The reference player crashes on these ("the ink compiler crashed").
     */
    EngineFailure,

    /**
     * Functions or tunnels called inside one another, or threads started, more than
     * [PlayLimits.maxCallDepth] times without returning: the story most likely calls itself
     * without end. ink keeps its call stack in memory, so such a story would fill the memory within
     * seconds, long before [TimeLimit]; the reference player runs on until it does.
     */
    DepthLimit,
}

/**
 * What [PlaySession.continueToChoice] produced: everything from one choice point to the next.
 *
 * Exactly one of three things ends a turn: [choices] is not empty (the story waits for a choice);
 * [ended] is true with [stop] null (the story ran out: "End of story", also after a runtime error,
 * which ends the flow); [stop] is set (the turn was cut short, the story is over, [ended] is true).
 *
 * @property lines the lines in order, each with the issues raised while it was produced.
 * @property issues issues that belong to no line: raised before the first line could be produced
 *   (an `EXTERNAL` function nothing binds and no ink function replaces) or by what stopped the turn.
 */
data class StoryTurn(
    val lines: List<StoryLine>,
    val choices: List<StoryChoice>,
    val ended: Boolean,
    val stop: StopReason? = null,
    val issues: List<InkIssue> = emptyList(),
) {
    /** Every issue of the turn in the order it was raised: those of the lines, then [issues]. */
    val allIssues: List<InkIssue> get() = lines.flatMap { it.issues } + issues
}
