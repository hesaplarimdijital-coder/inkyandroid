package com.inkyandroid.core.engine

/**
 * The kinds of message ink reports, declared in the order upstream Inky ranks them
 * (`toolbarView.js: updateIssueSummary`): errors first, TODOs last.
 */
enum class IssueKind {
    /** `ERROR:` from the compiler. The story does not compile while there is one. */
    Error,

    /** `RUNTIME ERROR:` while playing. It ends the story's flow. */
    RuntimeError,

    /** `WARNING:` from the compiler. */
    Warning,

    /** `RUNTIME WARNING:` while playing. */
    RuntimeWarning,

    /** `TODO:` an author's note in the ink. Never stops compilation. */
    Todo,
}

/**
 * One message of the compiler or the runtime, split into its parts.
 *
 * @property file the project path of the file the message is about, or null when the message
 *   carries no place (a missing `EXTERNAL` binding, a failure inside the compiler).
 * @property line 1-based line in [file]; null when unknown.
 * @property message the text after the place, as ink words it (English; ink has no translations).
 * @property runtimePath set instead of a file and line when the runtime could only name its
 *   internal path (`RUNTIME ERROR: (knot.0.3): …`).
 */
data class InkIssue(
    val kind: IssueKind,
    val file: String?,
    val line: Int?,
    val message: String,
    val runtimePath: String? = null,
) {
    /** Compile-time or runtime error (what upstream counts under the error icon). */
    val isError: Boolean get() = kind == IssueKind.Error || kind == IssueKind.RuntimeError
}

/** Upstream's arrangement and counts of an issue list. */
object InkIssues {
    /**
     * The order of upstream's issue list and of "next issue": by kind ([IssueKind]'s order), then
     * by line (issues without a line first). Upstream leaves the file out of the comparison, so the
     * issues of several files interleave by line; here the file breaks ties after the line, which
     * keeps the order stable without changing upstream's.
     */
    val upstreamOrder: Comparator<InkIssue> =
        compareBy<InkIssue> { it.kind.ordinal }.thenBy { it.line ?: 0 }.thenBy { it.file ?: "" }

    /** [issues] in [upstreamOrder]. The sort is stable: equal issues keep their reported order. */
    fun sorted(issues: List<InkIssue>): List<InkIssue> = issues.sortedWith(upstreamOrder)

    /** What upstream's toolbar counts: errors include runtime errors; runtime warnings are counted nowhere. */
    fun counts(issues: List<InkIssue>): IssueCounts = IssueCounts(
        errors = issues.count { it.isError },
        warnings = issues.count { it.kind == IssueKind.Warning },
        todos = issues.count { it.kind == IssueKind.Todo },
    )
}

/** The three numbers of upstream's issue summary. */
data class IssueCounts(val errors: Int, val warnings: Int, val todos: Int) {
    /** Upstream shows "No issues." when all three are zero. */
    val isEmpty: Boolean get() = errors == 0 && warnings == 0 && todos == 0
}
