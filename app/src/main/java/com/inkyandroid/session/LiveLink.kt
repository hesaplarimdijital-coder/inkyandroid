package com.inkyandroid.session

import com.inkyandroid.core.project.ProjectTexts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** What kind of problem an issue is, in upstream's order of importance. */
enum class IssueKind { Error, RuntimeError, Warning, RuntimeWarning, Todo }

/**
 * A problem the compiler or the running story reported.
 *
 * @property path the project file it is in, or null when the message names none.
 * @property line one-based; 0 when the message names none.
 */
data class SessionIssue(
    val kind: IssueKind,
    val path: String?,
    val line: Int,
    val message: String,
)

/**
 * What the project session needs of the live compile session (`core.live`): it is told about every
 * edit and reports the issues of the latest compile and run. The player's transcript and controls
 * are read from the live session itself by the screens.
 */
interface LiveLink {
    /** Something the compiler would see has changed; the live session recompiles after its own pause. */
    fun edited()

    /** The watch expressions evaluated after every turn. */
    fun setWatches(expressions: List<String>)

    /** The issues of the latest compile and run, in the order they were reported. */
    val issues: StateFlow<List<SessionIssue>>

    /** The project is closed: stop compiling. */
    fun close()
}

/** Creates the live session of a project. [sources] gives the project's texts at the moment it is called (on the main thread). */
fun interface LiveLinkFactory {
    fun create(scope: CoroutineScope, sources: () -> ProjectTexts): LiveLink
}
