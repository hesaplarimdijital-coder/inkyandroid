package com.inkyandroid.core.engine

import com.inkyandroid.core.project.ProjectPaths

/**
 * How file names in the compiler's and the runtime's own words become project paths.
 *
 * inklecate resolves every `INCLUDE` (also one inside an included file) against the main file's
 * folder, and messages and debug metadata keep the name exactly as it was written after `INCLUDE`
 * (`'./sub/part.ink' line 4`). The same name therefore has to be resolved the same way wherever it
 * shows up: when loading, in issues, in source locations.
 */
internal class IncludePaths(mainPath: String) {
    private val mainFolder = ProjectPaths.folder(mainPath)

    /** The name the compiler records for the main file: its bare name, as inklecate passes it. */
    val mainName: String = ProjectPaths.name(mainPath)

    /** The project path [written] names, or null when it names nothing inside the project. */
    fun resolveOrNull(written: String): String? =
        ProjectPaths.normalizeInclude(if (mainFolder.isEmpty()) written else "$mainFolder/$written")

    /** As [resolveOrNull]; a name that leaves the project is kept as written, so a message about it still shows it. */
    fun resolve(written: String): String = resolveOrNull(written) ?: written
}

/**
 * Splits the messages of the library into [InkIssue]s. The formats, from its sources:
 *
 * - parser: `ERROR: 'file' line N: text` or `ERROR: line N: text` (`InkParser.onStringParserError`);
 * - meaning: `ERROR|WARNING|TODO: ['file' ]line N: text`, or without a place (`ParsedHierarchy.Story.error`);
 * - runtime: `RUNTIME ERROR|WARNING: 'file' line N: text`, `…: (path): text` or `…: text` (`Story.addError`).
 *
 * The file and the line only ever come together, right after the type, so they are matched as one
 * group: a message that merely starts with a quoted word is not taken for a file name (upstream's
 * regex reads `ERROR: 'knot' is not a knot` as file `knot`). The file name is matched lazily up to
 * the first `' line N: `, so names with an apostrophe in them still split.
 */
internal class IssueText(private val paths: IncludePaths) {

    /** Parses [raw]; a text of an unexpected shape becomes an issue of [fallback] holding all of it. */
    fun parse(raw: String, fallback: IssueKind): InkIssue {
        val match = ISSUE.matchEntire(raw) ?: return InkIssue(fallback, file = null, line = null, message = raw.trim())
        val (prefix, file, fileLine, bareLine, runtimePath, text) = match.destructured
        return InkIssue(
            kind = PREFIXES.getValue(prefix),
            file = file.ifEmpty { null }?.let(paths::resolve),
            line = fileLine.ifEmpty { bareLine }.toIntOrNull(),
            message = text.trim(),
            runtimePath = runtimePath.ifEmpty { null },
        )
    }

    private companion object {
        val PREFIXES = mapOf(
            "ERROR" to IssueKind.Error,
            "WARNING" to IssueKind.Warning,
            "TODO" to IssueKind.Todo,
            "RUNTIME ERROR" to IssueKind.RuntimeError,
            "RUNTIME WARNING" to IssueKind.RuntimeWarning,
        )

        // groups: 1 type, 2 file, 3 line (with a file), 4 line (without), 5 runtime path, 6 message
        val ISSUE = Regex(
            """^(RUNTIME ERROR|RUNTIME WARNING|ERROR|WARNING|TODO): """ +
                """(?:'(.*?)' line (\d+): |line (\d+): |\(([^)]*)\): )?(.*)$""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
