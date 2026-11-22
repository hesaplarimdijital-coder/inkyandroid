package com.inkyandroid.core.engine

import com.bladecoder.ink.runtime.Error.ErrorHandler
import com.bladecoder.ink.runtime.Error.ErrorType
import com.bladecoder.ink.runtime.Path
import com.bladecoder.ink.runtime.Story
import com.bladecoder.ink.runtime.StoryException
import com.bladecoder.ink.runtime.inkySetStorySeed

/**
 * [PlaySession] over blade-ink's `Story`.
 *
 * Each session builds its own `Story` over the compilation's runtime tree, not from the JSON: the
 * JSON carries no debug metadata, so source locations would be lost.
 */
internal class BladePlaySession(
    private val compiled: BladeCompiledStory,
    private val options: PlayOptions,
) : PlaySession {
    private val limits = options.limits
    private val issueText = compiled.sink.text

    private val pendingIssues = mutableListOf<InkIssue>()
    private val runtimeErrors = ErrorHandler { message, type ->
        val fallback = if (type == ErrorType.Error) IssueKind.RuntimeError else IssueKind.RuntimeWarning
        pendingIssues += issueText.parse(message, fallback)
    }

    internal var story: Story = newStory().also { fresh -> options.seed?.let { fresh.inkySetStorySeed(it) } }
        private set

    /** The state at the last moment the story was known to be sound: where it is rebuilt after the runtime threw. */
    private var lastGoodState: String = story.state.toJson()

    /** The story was stopped or threw: it is over, whatever the rebuilt `Story` object says. */
    private var over = false

    private val evaluator = ImmediateEvaluator(compiled, this, limits.evaluationBudgetMillis)

    override val choices: List<StoryChoice>
        get() = if (over) emptyList() else story.currentChoices.map { StoryChoice(it.index, it.text, it.tags.orEmpty()) }

    override val ended: Boolean
        get() = over || (!story.canContinue() && story.currentChoices.isEmpty())

    override val globalTags: List<String>
        get() = try {
            story.globalTags.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }

    override fun continueToChoice(): StoryTurn {
        if (over) return StoryTurn(emptyList(), emptyList(), ended = true)
        val lines = mutableListOf<StoryLine>()
        val loose = mutableListOf<InkIssue>()
        var stop: StopReason? = null
        var broken = false
        val deadline = System.nanoTime() + limits.turnBudgetMillis * NANOS_PER_MILLI
        try {
            while (story.canContinue()) {
                if (lines.size >= limits.maxLinesPerTurn) {
                    stop = StopReason.LineLimit
                    break
                }
                if (!continueOneLine(deadline)) {
                    stop = StopReason.TimeLimit
                    break
                }
                lines += currentLine(drainIssues())
            }
        } catch (e: StoryException) {
            // The story's own refusal to run, worded by the runtime with its type ("ERROR: Missing
            // function binding for external: …"): inklecate reports it as an issue and ends the story.
            broken = true
            loose += issueText.parse(e.message ?: e.toString(), IssueKind.RuntimeError)
        } catch (e: Exception) {
            // The runtime threw where it should have reported (ArithmeticException "/ by zero").
            stop = StopReason.EngineFailure
            loose += InkIssue(IssueKind.RuntimeError, null, null, e.message ?: e.toString())
        } catch (e: StackOverflowError) {
            stop = StopReason.EngineFailure
            loose += InkIssue(IssueKind.RuntimeError, null, null, STACK_OVERFLOW)
        }
        loose += drainIssues()
        if (stop != null || broken) {
            // The Story object may be in the middle of a line (an async continue still active, a state
            // snapshot pending): it cannot be used again. Rebuild it at the last sound state; the
            // playthrough ends here.
            recover(lastGoodState)
            over = true
            return StoryTurn(lines, emptyList(), ended = true, stop = stop, issues = loose)
        }
        lastGoodState = story.state.toJson()
        val offered = choices
        return StoryTurn(lines, offered, ended = offered.isEmpty(), issues = loose)
    }

    override fun choose(index: Int): Boolean {
        if (over || index !in story.currentChoices.indices) return false
        return try {
            story.chooseChoiceIndex(index)
            true
        } catch (e: Exception) {
            recover(lastGoodState)
            false
        }
    }

    override fun evaluate(input: String): Evaluation = evaluator.evaluate(input, allowDivert = true)

    // Upstream sends `"${expressionText}"` to inklecate: the watch is the inside of an ink string.
    override fun evaluateWatch(template: String): Evaluation = evaluator.evaluate("\"$template\"", allowDivert = false)

    override fun locate(runtimePath: String): SourceLocation? = try {
        story.contentAtPath(Path(runtimePath))?.obj?.let { SourceSpans.locationOf(it, compiled.paths) }
    } catch (e: Exception) {
        null
    }

    override fun saveState(): String = story.state.toJson()

    override fun loadState(state: String): Boolean {
        val before = story.state.toJson()
        return try {
            story.state.loadJson(state)
            lastGoodState = state
            over = false
            true
        } catch (e: Exception) {
            recover(before)
            false
        }
    }

    /** Jumps to a runtime path, as inklecate does for `-> path` typed in play mode. Throws what the runtime throws. */
    internal fun divertTo(path: String) {
        story.choosePathString(path)
        over = false
    }

    internal fun drainIssues(): List<InkIssue> = pendingIssues.toList().also { pendingIssues.clear() }

    /**
     * Replaces the `Story` object, which an exception escaping the runtime leaves unusable, with a
     * fresh one at [stateJson].
     */
    internal fun recover(stateJson: String) {
        story = newStory()
        story.state.loadJson(stateJson)
        pendingIssues.clear()
    }

    /** One step of the story in slices, so that a loop without output cannot hold the thread; false when [deadline] passed first. */
    private fun continueOneLine(deadline: Long): Boolean {
        do {
            story.continueAsync(SLICE_MILLIS)
            if (story.asyncContinueComplete()) return true
        } while (System.nanoTime() < deadline)
        return false
    }

    private fun currentLine(issues: List<InkIssue>): StoryLine {
        val text = story.currentText
        return StoryLine(text, story.currentTags.toList(), SourceSpans.of(story.state.outputStream, text, compiled.paths), issues)
    }

    private fun newStory(): Story =
        Story(compiled.story.mainContentContainer, compiled.story.listDefinitions?.lists).apply {
            resetState()
            // Without a handler a runtime error makes the story throw.
            onError = runtimeErrors
            // Inky plays stories whose EXTERNAL functions nothing binds: inklecate turns fallbacks on,
            // so an EXTERNAL with an ink function of the same name runs that function.
            setAllowExternalFunctionFallbacks(true)
        }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /** What `continueAsync` gets at a time; the deadline is checked between slices. */
        const val SLICE_MILLIS = 50f
        const val STACK_OVERFLOW = "Stack overflow"
    }
}
