package com.inkyandroid.core.engine

import com.bladecoder.ink.compiler.InkParser
import com.bladecoder.ink.compiler.ParsedHierarchy.Divert
import com.bladecoder.ink.compiler.ParsedHierarchy.Expression
import com.bladecoder.ink.compiler.ParsedHierarchy.ParsedObject
import com.bladecoder.ink.runtime.Container
import com.bladecoder.ink.runtime.ControlCommand

/**
 * What inklecate's play mode does with typed input (`InkParser.CommandLineUserInput` and
 * `Compiler.handleInput`), without its defect.
 *
 * inklecate compiles the input against the parsed story and runs it with
 * `Story.evaluateExpression`, which swaps the story's root for a temporary container. Every
 * absolute path then resolves inside that container: a function call (`double(x)`) jumps back to
 * the start of the expression and recurses until memory runs out. The reference inklecate 1.2.0
 * does the same (it spins at full CPU), so desktop Inky's watches cannot call functions.
 *
 * Here the compiled expression is mounted under the real root as a named container that ends in
 * "print the value" and "done", and run in a flow of its own under a time budget. Afterwards the
 * container is unmounted and the whole state is restored from a snapshot taken before, so an
 * evaluation never changes the session: not its variables, not its random seed, not its output.
 * Inky's watches are read-only too (they are string templates), so only expressions are accepted.
 *
 * Compiling an input costs as much as running it (about a millisecond each), and a replay asks for
 * the same few watches after every turn, so a compiled watch is kept for the playthrough.
 */
internal class ImmediateEvaluator(
    private val compiled: BladeCompiledStory,
    private val session: BladePlaySession,
    private val budgetMillis: Long,
    private val maxCallDepth: Int,
) {
    private var counter = 0

    /** An input made ready to run, or why it cannot run. */
    private sealed interface Prepared {
        /** An expression, compiled into [container]; [issues] are what compiling it reported (warnings at most). */
        class Expression(val container: Container, val issues: List<InkIssue>) : Prepared

        /** `-> path`. */
        class Divert(val path: String, val issues: List<InkIssue>) : Prepared

        class Refused(val failure: Evaluation.Failed) : Prepared
    }

    /** The watches compiled for this playthrough (they depend on the compiled story alone), most recently used last. */
    private val preparedWatches = object : LinkedHashMap<String, Prepared>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Prepared>): Boolean = size > MAX_PREPARED_WATCHES
    }

    /** The places in the messages are places in the typed text ("line 1"), which mean nothing to the reader: dropped. */
    fun evaluate(input: String, allowDivert: Boolean): Evaluation =
        when (val result = evaluatePrepared(input, allowDivert)) {
            is Evaluation.Value -> result.copy(issues = result.issues.map(::withoutPlace))
            is Evaluation.Failed -> result.copy(issues = result.issues.map(::withoutPlace))
            is Evaluation.Diverted -> result
        }

    private fun withoutPlace(issue: InkIssue) = issue.copy(file = null, line = null, runtimePath = null)

    private fun evaluatePrepared(input: String, allowDivert: Boolean): Evaluation {
        // Only watches are kept: they are asked for again and again, and they cannot divert.
        val prepared = if (allowDivert) prepare(input, allowDivert = true) else preparedWatches.getOrPut(input) { prepare(input, allowDivert = false) }
        return when (prepared) {
            is Prepared.Refused -> prepared.failure
            is Prepared.Expression -> run(prepared.container, prepared.issues.toMutableList())
            is Prepared.Divert -> try {
                session.divertTo(prepared.path)
                Evaluation.Diverted(prepared.path)
            } catch (e: Exception) {
                Evaluation.Failed(prepared.issues + error(e.message ?: e.toString()))
            }
        }
    }

    /** Parses [input] and resolves its names against the parsed story; changes nothing. */
    private fun prepare(input: String, allowDivert: Boolean): Prepared {
        val issues = mutableListOf<InkIssue>()
        val parsed = parse(input, allowDivert, issues) ?: return Prepared.Refused(Evaluation.Failed(issues))

        val parsedStory = compiled.compiler.parsedStory
        // Resolving names reports through the compiler's own error handler: collect those messages
        // for this evaluation only.
        val sink = compiled.sink
        val previousTarget = sink.target
        sink.target = issues
        val runtimeObject = try {
            parsed.parent = parsedStory
            parsed.runtimeObject.also { parsed.resolveReferences(parsedStory) }
        } catch (e: Exception) {
            issues += error(e.message ?: e.toString())
            null
        } finally {
            sink.target = previousTarget
        }
        if (parsedStory.hadError() || runtimeObject == null || issues.any { it.kind == IssueKind.Error }) {
            parsedStory.resetError()
            if (issues.isEmpty()) issues += error(NOT_AN_EXPRESSION)
            return Prepared.Refused(Evaluation.Failed(issues))
        }

        if (parsed is Divert) {
            return try {
                Prepared.Divert(parsed.runtimeDivert.targetPath.toString(), issues)
            } catch (e: Exception) {
                Prepared.Refused(Evaluation.Failed(issues + error(e.message ?: e.toString())))
            }
        }
        val container = runtimeObject as? Container
            ?: return Prepared.Refused(Evaluation.Failed(issues + error(NOT_AN_EXPRESSION)))
        // [EvalStart, expr, EvalEnd] becomes [EvalStart, expr, EvalOutput, EvalEnd, Done]: the value
        // is printed into the flow's output as `{expr}` prints it in ink, then the flow stops cleanly.
        // Running it does not change it, so it can be run again.
        container.name = "$MOUNT_PREFIX${counter++}"
        container.insertContent(ControlCommand(ControlCommand.CommandType.EvalOutput), container.content.size - 1)
        container.addContent(ControlCommand(ControlCommand.CommandType.Done))
        return Prepared.Expression(container, issues)
    }

    /**
     * Parses one input: a `-> divert` through the play-mode grammar, anything else as an expression.
     *
     * inklecate's grammar tries a divert, then an assignment, then an expression; its assignment
     * rule throws an `IndexOutOfBoundsException` on `x == 1` instead of giving way to the expression
     * rule. Expressions are therefore parsed with the parser's own (protected) expression rule, and
     * the whole input must be used up.
     */
    private fun parse(input: String, allowDivert: Boolean, issues: MutableList<InkIssue>): ParsedObject? {
        val handler = com.bladecoder.ink.runtime.Error.ErrorHandler { message, type ->
            val fallback = if (type == com.bladecoder.ink.runtime.Error.ErrorType.Warning) IssueKind.Warning else IssueKind.Error
            issues += compiled.sink.text.parse(message, fallback)
        }
        val parsed: ParsedObject? = try {
            if (allowDivert && input.trimStart().startsWith("->")) {
                InkParser(input, null, handler, null).CommandLineUserInput()?.userImmediateModeStatement as? Divert
            } else {
                ExpressionParser(input, handler).wholeExpression(issues)
            }
        } catch (e: RuntimeException) {
            issues += error(e.message ?: e.toString())
            return null
        }
        if (parsed == null && issues.isEmpty()) issues += error(NOT_AN_EXPRESSION)
        return parsed.takeIf { issues.none { it.kind == IssueKind.Error } }
    }

    // Inside a subclass of InkParser a bare `ErrorHandler` would mean StringParser.ErrorHandler.
    private class ExpressionParser(text: String, handler: com.bladecoder.ink.runtime.Error.ErrorHandler) :
        InkParser(text, null, handler, null) {
        fun wholeExpression(issues: MutableList<InkIssue>): Expression? {
            val expression = expression() ?: return null
            whitespace()
            if (!isEndOfInput) {
                issues += InkIssue(IssueKind.Error, null, null, "Unexpected text after the expression: '${remainingString()}'")
            }
            return expression
        }
    }

    /** Mounts [expression] (made by [prepare]) under the story's root, runs it in a flow of its own and puts everything back. */
    private fun run(expression: Container, issues: MutableList<InkIssue>): Evaluation {
        val story = session.story
        val root = story.mainContentContainer
        val name = expression.name

        val saved = session.stateNow()
        val text = StringBuilder()
        var completed = false
        var tooDeep = false
        var failure: String? = null
        root.addToNamedContentOnly(expression)
        try {
            story.switchFlow(EVAL_FLOW)
            story.choosePathString(name)
            // made in the evaluation's own flow, whose call stack is the one that grows
            val depth = CallDepthGuard(story, maxCallDepth)
            val deadline = System.nanoTime() + budgetMillis * 1_000_000
            while (story.canContinue() && System.nanoTime() < deadline) {
                story.continueAsync(SLICE_MILLIS)
                if (story.asyncContinueComplete()) {
                    text.append(story.currentText)
                } else if (depth.exceeded()) {
                    tooDeep = true
                    break
                }
            }
            completed = !story.canContinue()
        } catch (e: Exception) {
            failure = e.message ?: e.toString()
        } catch (e: StackOverflowError) {
            failure = "Stack overflow"
        } finally {
            root.namedContent.remove(name)
        }
        issues += session.drainIssues()
        if (completed && failure == null) {
            // The usual way back: leave the evaluation's flow and restore the snapshot. Most watches
            // only read, and then the state already is what it was: loading it again, the larger
            // part of an evaluation's cost, is left out.
            try {
                story.switchToDefaultFlow()
                story.removeFlow(EVAL_FLOW)
                if (story.state.toJson() != saved) story.state.loadJson(saved)
            } catch (e: Exception) {
                session.recover(saved)
            }
        } else {
            // Out of time in the middle of a line, or the runtime threw: the Story object itself is unsound.
            session.recover(saved)
        }
        return when {
            failure != null -> Evaluation.Failed(issues + InkIssue(IssueKind.RuntimeError, null, null, failure))
            tooDeep -> Evaluation.Failed(issues + InkIssue(IssueKind.RuntimeError, null, null, "The evaluation called functions inside one another more than $maxCallDepth deep: does one call itself without end?"))
            !completed -> Evaluation.Failed(issues + InkIssue(IssueKind.RuntimeError, null, null, "The evaluation took too long"))
            issues.any { it.kind == IssueKind.RuntimeError } -> Evaluation.Failed(issues.sortedBy { it.kind != IssueKind.RuntimeError })
            else -> Evaluation.Value(text.toString().trimEnd('\n'), issues)
        }
    }

    private fun error(message: String) = InkIssue(IssueKind.Error, null, null, message)

    private companion object {
        const val MOUNT_PREFIX = "__inky_eval_"
        const val EVAL_FLOW = "__inky_eval"
        const val SLICE_MILLIS = 20f
        const val NOT_AN_EXPRESSION = "Not an ink expression"

        /** More different watches than anyone keeps; only a caller sending many different inputs reaches it. */
        const val MAX_PREPARED_WATCHES = 64
    }
}
