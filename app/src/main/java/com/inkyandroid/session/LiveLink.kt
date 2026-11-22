package com.inkyandroid.session

import com.inkyandroid.core.engine.InkIssue
import com.inkyandroid.core.engine.InkSources
import com.inkyandroid.core.live.LiveSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the project session needs of the live compile session ([LiveSession]): it tells it about
 * edits, settings and watches, and reads the issues. The player's transcript and controls are the
 * screens' business with the live session itself ([session]).
 *
 * The seam keeps the session's own rules (unsaved state, drafts, autosave, restore) testable
 * without compiling ink.
 */
interface LiveLink {
    /** The project is loaded: compile and play at once. */
    fun reloadNow()

    /** Something the compiler would see has changed; the live session recompiles after its own pause. */
    fun edited()

    /** The watch expressions evaluated after every turn. */
    fun setWatches(expressions: List<String>)

    /** The project's `instructionPrefix` setting. */
    fun setInstructionPrefix(prefix: String?)

    /** The issues of the latest compile and run, as reported. */
    val issues: StateFlow<List<InkIssue>>

    /** The live session behind the link, for the player; null when the link is not backed by one (a test of the session alone). */
    val session: LiveSession?

    /** The project is closed: stop compiling. */
    fun close()
}

/**
 * Creates the live link of a project. [sources] gives the project's texts at the moment it is
 * called and must be called on the main thread.
 */
fun interface LiveLinkFactory {
    fun create(scope: CoroutineScope, sources: () -> InkSources): LiveLink
}

/**
 * The link to a real [LiveSession].
 *
 * @param engineDispatcher the app's one ink thread, shared by every window: the ink library must
 *   never run on two threads at once.
 */
class LiveSessionLink(
    scope: CoroutineScope,
    engineDispatcher: CoroutineDispatcher,
    sources: () -> InkSources,
) : LiveLink {

    // the live session asks for the sources on the ink thread; the project lives on the main thread
    override val session = LiveSession(scope, engineDispatcher, sources = { withContext(Dispatchers.Main.immediate) { sources() } })

    private val mutableIssues = MutableStateFlow<List<InkIssue>>(emptyList())
    override val issues: StateFlow<List<InkIssue>> = mutableIssues.asStateFlow()

    init {
        scope.launch { session.state.collect { mutableIssues.value = it.issues } }
    }

    override fun reloadNow() = session.reloadNow()

    override fun edited() = session.edited()

    override fun setWatches(expressions: List<String>) = session.setWatches(expressions)

    override fun setInstructionPrefix(prefix: String?) = session.setInstructionPrefix(prefix)

    override fun close() = session.close()

    companion object {
        /** The factory for the app: every project's live session on [engineDispatcher]. */
        fun factory(engineDispatcher: CoroutineDispatcher) = LiveLinkFactory { scope, sources -> LiveSessionLink(scope, engineDispatcher, sources) }
    }
}
