package com.inkyandroid.core.engine

import com.bladecoder.ink.compiler.Compiler
import com.bladecoder.ink.compiler.IFileHandler
import com.bladecoder.ink.compiler.Stats
import com.bladecoder.ink.runtime.Error.ErrorHandler
import com.bladecoder.ink.runtime.Error.ErrorType
import com.bladecoder.ink.runtime.Story
import java.io.IOException

/**
 * [InkEngine] over blade-ink 1.3.3, the Java port of ink 1.2.0's compiler and runtime. This file
 * and its neighbours `BladePlaySession`, `ImmediateEvaluator` and `SourceSpans` are the only code
 * of the app that touches the library.
 */
internal object BladeInkEngine : InkEngine {

    override fun compile(sources: InkSources): CompileResult {
        val paths = IncludePaths(sources.mainPath)
        val sink = IssueSink(IssueText(paths))
        val issues = sink.target
        val mainText = sources.texts[sources.mainPath]
            ?: return CompileResult.Failed(listOf(InkIssue(IssueKind.Error, null, null, "Main file not found: '${sources.mainPath}'")))

        val compiler = Compiler(mainText, options(sources, paths, sink))
        val story: Story? = try {
            compiler.compile()
        } catch (e: RuntimeException) {
            if (e.message != PARSER_STACK_OVERFLOW) return crashed(e, issues)
            // The parser's state stack has a fixed depth (200, as in the reference): about 24 nested
            // `{…}` or 91 nested parentheses throw this out of compile(). The reference inklecate dies
            // of it; here it is what it is, an error in the ink, though one without a line.
            issues += InkIssue(IssueKind.Error, null, null, "The ink is nested too deeply for the compiler (braces or parentheses inside one another)")
            null
        } catch (e: Exception) {
            return crashed(e, issues)
        } catch (e: StackOverflowError) {
            return crashed(e, issues)
        }
        if (story == null || issues.any { it.kind == IssueKind.Error }) return CompileResult.Failed(issues.toList())
        return CompileResult.Success(BladeCompiledStory(sources, story, compiler, sink, paths), issues.toList())
    }

    /**
     * Counted on a parse alone, as inklecate `-s` does (`Compiler.parse()`, no code generation):
     * counting on the tree of a full compilation gives other numbers, because generating code adds
     * and rewrites nodes. The parse's issues are dropped; the live compilation reports them.
     */
    override fun statistics(sources: InkSources): InkStatistics? {
        val paths = IncludePaths(sources.mainPath)
        val mainText = sources.texts[sources.mainPath] ?: return null
        return try {
            val parsed = Compiler(mainText, options(sources, paths, IssueSink(IssueText(paths)))).parse() ?: return null
            val stats = Stats.generate(parsed)
            InkStatistics(stats.words, stats.knots, stats.stitches, stats.functions, stats.choices, stats.gathers, stats.diverts)
        } catch (e: Exception) {
            null
        } catch (e: StackOverflowError) {
            null
        }
    }

    private fun options(sources: InkSources, paths: IncludePaths, sink: IssueSink) = Compiler.Options().apply {
        // The name recorded in messages and debug metadata for the main file.
        sourceFilename = paths.mainName
        // Inky passes -c for playing, exporting and counting alike.
        countAllVisits = true
        // Without a handler the compiler throws on the first error.
        errorHandler = ErrorHandler(sink::report)
        // Without one the compiler would read the process's working directory.
        fileHandler = object : IFileHandler {
            // The result is only a key: to detect recursive includes and to load the text.
            override fun resolveInkFilename(includeName: String): String = paths.resolve(includeName)

            override fun loadInkFileContents(fullFilename: String): String =
                sources.texts[fullFilename] ?: throw IOException("Not in the project: $fullFilename")
        }
    }

    private fun crashed(failure: Throwable, issues: List<InkIssue>): CompileResult.Crashed =
        CompileResult.Crashed(failure.message ?: failure.javaClass.simpleName, diagnosticOf(failure), issues.toList())

    private const val PARSER_STACK_OVERFLOW = "Stack overflow in parser state"
}

/** The exception and the top of its stack: enough to report a failure of the engine, short enough to show. */
internal fun diagnosticOf(failure: Throwable): String =
    (listOf(failure.toString()) + failure.stackTrace.take(DIAGNOSTIC_FRAMES).map { "    at $it" }).joinToString("\n")

private const val DIAGNOSTIC_FRAMES = 12

/**
 * The compiler reports through one handler fixed when it is created; the same handler later
 * receives the errors of typed expressions resolved against the parsed story. This lets each phase
 * collect its own messages.
 */
internal class IssueSink(val text: IssueText) {
    var target: MutableList<InkIssue> = mutableListOf()

    fun report(message: String, type: ErrorType) {
        val fallback = when (type) {
            ErrorType.Author -> IssueKind.Todo
            ErrorType.Warning -> IssueKind.Warning
            ErrorType.Error -> IssueKind.Error
        }
        target += text.parse(message, fallback)
    }
}

/**
 * A successful compilation. The [compiler] is kept because evaluating typed expressions needs its
 * parsed story; it keeps the whole parse tree alive.
 */
internal class BladeCompiledStory(
    override val sources: InkSources,
    val story: Story,
    val compiler: Compiler,
    val sink: IssueSink,
    val paths: IncludePaths,
) : CompiledStory {
    private var json: String? = null

    override fun toJson(): String = json ?: story.toJson().also { json = it }

    override fun play(options: PlayOptions): PlaySession = BladePlaySession(this, options)
}
