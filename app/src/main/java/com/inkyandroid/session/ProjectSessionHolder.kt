package com.inkyandroid.session

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inkyandroid.core.project.DraftStore
import com.inkyandroid.core.project.FileDraftStore
import com.inkyandroid.core.project.ProjectStore
import com.inkyandroid.core.project.TextHash
import com.inkyandroid.settings.AppSettings
import com.inkyandroid.storage.ProjectRef
import com.inkyandroid.storage.ProjectStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/**
 * A project reference resolved to its storage.
 *
 * @property singleFile the project is one file opened without its folder: nothing beside it can be
 *   created or read (its INCLUDEs do not resolve) until the folder is granted.
 */
class OpenedProject(val store: ProjectStore, val mainPath: String, val singleFile: Boolean = false)

/**
 * Turns a project reference in its string form into the project's storage: the storage package's
 * part of opening a project. [StorageProjectOpener] is the app's; tests use their own.
 */
interface ProjectOpener {
    /** The storage of the project with [reference], for a window that opens it. */
    @Throws(IOException::class)
    suspend fun open(reference: String): OpenedProject

    /** The window that opened [reference] shows it no longer. */
    fun close(reference: String) {}

    /**
     * What identifies the project behind [reference] for as long as it is the same project: its
     * drafts are filed under it. The reference itself unless the opener knows better (a reference
     * may carry a display name that changes).
     */
    fun identity(reference: String): String = reference
}

/** Opens projects through the app's [ProjectStorage]: a reference is `ProjectRef.encode()`. */
class StorageProjectOpener(private val storage: ProjectStorage) : ProjectOpener {

    override suspend fun open(reference: String): OpenedProject {
        val ref = ProjectRef.decode(reference) ?: throw IOException("Not a project reference")
        return OpenedProject(storage.open(ref), ref.mainPath, singleFile = ref is ProjectRef.SingleDocument)
    }

    override fun close(reference: String) {
        ProjectRef.decode(reference)?.let(storage::close)
    }

    override fun identity(reference: String): String = ProjectRef.decode(reference)?.key ?: reference
}

/**
 * What every window's session is built from; one for the process, made by the application.
 *
 * @property live creates each project's live compile session: `LiveSessionLink.factory(inkDispatcher)`
 *   with the app's one ink thread.
 * @property draftsDirectory the app-private directory under which each project gets its own drafts
 *   folder (`filesDir/drafts`).
 * @property io where the drafts are read and written.
 * @property parseContext where the files' symbols are parsed.
 * @property sessionContext the sessions' thread (the main thread); a `Job` in it is the parent of every
 *   session (a test ends it to play the death of the process).
 */
class SessionEnvironment(
    val settings: AppSettings,
    val opener: ProjectOpener,
    val editors: EditorBufferFactory,
    val live: LiveLinkFactory,
    val draftsDirectory: File,
    val io: CoroutineDispatcher = Dispatchers.IO,
    val parseContext: CoroutineContext = Dispatchers.Default,
    val sessionContext: CoroutineContext = Dispatchers.Main.immediate,
) {
    /** The drafts store of the project with [reference]: a folder named after the hash of the project's identity. */
    fun draftsFor(reference: String): DraftStore = FileDraftStore(draftsFolder(reference), io)

    /** The folder [draftsFor] keeps the drafts of [reference] in. */
    fun draftsFolder(reference: String): File =
        File(draftsDirectory, TextHash.of(opener.identity(reference)).take(DRAFT_FOLDER_LENGTH))

    /** Project identity → the window that has it open, and the job of its last flush once it gives it back. */
    private class Claim(val window: Any, var givingBack: Job? = null)

    private val windows = HashMap<String, Claim>()

    /** What a window that asks for a project is told ([claim]). */
    internal sealed interface ClaimAnswer {
        /** The project is the window's now. */
        data object Granted : ClaimAnswer

        /** The window that has it is giving it back: ask again when [lastFlush] has ended. */
        data class Wait(val lastFlush: Job) : ClaimAnswer

        /** Another window has it open. */
        data object Refused : ClaimAnswer
    }

    /**
     * [window] asks for [reference]'s project. One window per project, so two sessions never share
     * one drafts folder (each would restore, and on saving remove, the other's drafts). Upstream
     * opens a second window and notes it should switch to the first instead.
     */
    internal fun claim(reference: String, window: Any): ClaimAnswer = synchronized(windows) {
        val identity = opener.identity(reference)
        val claim = windows[identity]
        val lastFlush = claim?.givingBack
        when {
            claim == null || claim.window === window || (lastFlush != null && !lastFlush.isActive) -> {
                windows[identity] = Claim(window)
                ClaimAnswer.Granted
            }
            lastFlush != null -> ClaimAnswer.Wait(lastFlush)
            else -> ClaimAnswer.Refused
        }
    }

    /** [window] gives [reference]'s project back once [lastFlush] has ended; a window asking for it meanwhile waits. */
    internal fun givingBack(reference: String, window: Any, lastFlush: Job) {
        synchronized(windows) {
            windows[opener.identity(reference)]?.takeIf { it.window === window }?.givingBack = lastFlush
        }
    }

    /** [window] no longer has [reference] open. */
    internal fun release(reference: String, window: Any) {
        synchronized(windows) {
            val identity = opener.identity(reference)
            if (windows[identity]?.window === window) windows.remove(identity)
        }
    }

    /** Whether [one] and [other] are references of the same project. */
    internal fun isSameProject(one: String, other: String): Boolean = opener.identity(one) == opener.identity(other)

    /**
     * Removes the drafts and kept versions of [reference]'s project: it was deleted. Call it once
     * no window has the project open any more (after the job of [ProjectSessionHolder.closed]).
     */
    suspend fun forgetDrafts(reference: String) {
        val folder = draftsFolder(reference)
        withContext(io) {
            if (!folder.deleteRecursively() && folder.exists()) throw IOException("Cannot remove ${folder.path}")
        }
    }

    /** Whether a window other than [window] has the project of [reference] open (and is not giving it back). */
    internal fun isOpenElsewhere(reference: String, window: Any): Boolean = synchronized(windows) {
        val claim = windows[opener.identity(reference)] ?: return false
        claim.window !== window && claim.givingBack == null
    }

    companion object {
        private const val DRAFT_FOLDER_LENGTH = 32

        @Volatile
        private var shared: SessionEnvironment? = null

        /**
         * The app's environment, one for the process (every window's holder uses it): the app's
         * settings, its project storage, the kit's editors, one thread for all ink work (the ink
         * library must never run on two threads at once) and the drafts under `filesDir/drafts`.
         */
        fun of(context: Context): SessionEnvironment = shared ?: synchronized(this) {
            shared ?: context.applicationContext.let { app ->
                SessionEnvironment(
                    settings = AppSettings.of(app),
                    opener = StorageProjectOpener(ProjectStorage.from(app)),
                    editors = KitEditorBuffer.Factory,
                    live = LiveSessionLink.factory(
                        Executors.newSingleThreadExecutor { work -> Thread(work, "ink").apply { isDaemon = true } }.asCoroutineDispatcher(),
                    ),
                    draftsDirectory = File(app.filesDir, "drafts"),
                )
            }.also { shared = it }
        }
    }
}

/**
 * The project is open in another window of the app ([WindowProject.Failed.cause]): a project is open
 * in one window at a time.
 */
class ProjectOpenElsewhereException(val reference: String) : IOException("The project is open in another window")

/** What a window is doing about its project. */
sealed interface WindowProject {
    /** No project is open (the first start, or the last one was closed). */
    data object None : WindowProject

    /** The project with [reference] is being opened. */
    data class Opening(val reference: String) : WindowProject

    /** The project is open. */
    data class Open(val session: ProjectSession) : WindowProject

    /**
     * The project with [reference] could not be opened; [message] is storage's reason, [cause] the
     * failure itself for a sentence in the user's language (`StorageProblem.of(cause)`): the main
     * file is gone, is not text, the permission was withdrawn….
     *
     * @property keptDrafts the files of the project whose unsaved text is kept in the drafts: it is
     *   not lost, and comes back when the project can be opened again (tell the user so).
     */
    data class Failed(
        val reference: String,
        val message: String,
        val cause: IOException? = null,
        val keptDrafts: List<String> = emptyList(),
    ) : WindowProject
}

/**
 * Keeps a window's [ProjectSession] for as long as the window exists: across configuration changes
 * (it is a `ViewModel` of the activity), one per window.
 *
 * The activity calls:
 * - [restore] once in `onCreate`, with the reference it saved in its instance state (null on a
 *   fresh start): the window reopens that project, or the one of the last session;
 * - [onWindowFocusGained] from `onWindowFocusChanged(true)`: storage is looked at for outside changes;
 * - [onBackground] from `onStop`: drafts are written and, with autosave, files saved;
 * - [reference] in `onSaveInstanceState`, to get the window's project back after the system
 *   killed the process.
 */
class ProjectSessionHolder(private val environment: SessionEnvironment) : ViewModel() {

    private val mutableProject = MutableStateFlow<WindowProject>(WindowProject.None)
    private var opening: Job? = null
    private var syncing: Job? = null
    private var restored = false

    /** What the window shows: nothing, a project being opened, an open project, or why it could not be opened. */
    val project: StateFlow<WindowProject> = mutableProject.asStateFlow()

    private val mutableDeclined = MutableStateFlow<IOException?>(null)

    /**
     * Why the window's last [open] or [switchTo] was not carried out while the window kept the
     * project it shows: [ProjectOpenElsewhereException] (another window has that project open).
     * Shown once, then [dismissDeclined]. (Without a project to keep, [project] is `Failed` instead.)
     */
    val declined: StateFlow<IOException?> = mutableDeclined.asStateFlow()

    /** The window has told the user why its last request was declined. */
    fun dismissDeclined() {
        mutableDeclined.value = null
    }

    /** The open session, or null. */
    val session: ProjectSession? get() = (mutableProject.value as? WindowProject.Open)?.session

    /** The reference of the project the window shows or is opening: what the activity saves in its instance state. */
    val reference: String?
        get() = when (val current = mutableProject.value) {
            is WindowProject.Open -> current.session.reference
            is WindowProject.Opening -> current.reference
            is WindowProject.Failed -> current.reference
            WindowProject.None -> null
        }

    /**
     * Reopens what the window had open: [savedReference] (from the activity's saved instance state)
     * or else the project of the last session. Does nothing after the first call, or once a project
     * was opened explicitly, so it is safe in every `onCreate`.
     */
    fun restore(savedReference: String?) {
        if (restored) return
        restored = true
        val reference = savedReference ?: environment.settings.lastSession?.project ?: return
        // a new window beside the one that has the last project open starts without a project
        if (environment.isOpenElsewhere(reference, this)) return
        open(reference)
    }

    /**
     * Opens the project with [reference] in this window, replacing the one that is open. The open
     * session is ended without asking ([ProjectSession.abandon]: with autosave its files are saved,
     * its drafts are kept either way): ask it first with [ProjectSession.requestClose] when the
     * user's unsaved changes should be confirmed.
     */
    fun open(reference: String) {
        start(reference, carried = null)
    }

    /**
     * Opens [reference] as the same project in another place: a single file whose folder was
     * granted afterwards, an internal project copied out to a folder. The unsaved changes of the
     * open session move along by file path, as unsaved changes of the new one; nothing is asked
     * and nothing is saved to the old place.
     */
    fun switchTo(reference: String) {
        start(reference, carried = session)
    }

    private fun start(reference: String, carried: ProjectSession?) {
        // what the window shows now was chosen: there is nothing left to restore
        restored = true
        // one window per project: asked for while another window has it, nothing changes here
        val answer = environment.claim(reference, this)
        if (answer is SessionEnvironment.ClaimAnswer.Refused) {
            val elsewhere = ProjectOpenElsewhereException(reference)
            // a window that shows a project, or is opening one, goes on with it
            if (session != null || opening?.isActive == true) {
                mutableDeclined.value = elsewhere
            } else {
                mutableProject.value = WindowProject.Failed(reference, elsewhere.message.orEmpty(), elsewhere)
            }
            return
        }
        opening?.cancel()
        val previous = session
        val ended = if (carried == null) previous?.let { end(it, next = reference) } else null
        mutableProject.value = WindowProject.Opening(reference)
        opening = viewModelScope.launch {
            // the project that was open has written its last drafts before anything reads drafts again
            ended?.join()
            // the claim on [reference] is the new session's, or given back, once this is true
            var settled = false
            try {
                if (answer is SessionEnvironment.ClaimAnswer.Wait && !claimAfter(answer, reference)) {
                    val elsewhere = ProjectOpenElsewhereException(reference)
                    settled = true
                    mutableProject.value = WindowProject.Failed(reference, elsewhere.message.orEmpty(), elsewhere)
                    return@launch
                }
                val drafts = environment.draftsFor(reference)
                if (carried != null) {
                    // the drafts move to where the new place files them, unless that is where they are
                    val target = drafts.takeIf { environment.draftsFolder(reference) != environment.draftsFolder(carried.reference) }
                    try {
                        // never cut half way by a newer request: the drafts are moved or not at all
                        withContext(NonCancellable) { carried.handOver(target, successor = reference) }
                    } catch (failed: IOException) {
                        // the unsaved text could not be carried over: the window keeps the project it has
                        carried.draftsFailed(failed)
                        mutableProject.value = WindowProject.Open(carried)
                        settled = true
                        releaseIfUnused(reference)
                        return@launch
                    }
                    environment.opener.close(carried.reference)
                    releaseIfUnused(carried.reference)
                }
                val session = try {
                    openSession(reference, drafts)
                } catch (failed: IOException) {
                    val kept = try {
                        drafts.load().map { it.path }
                    } catch (unreadable: IOException) {
                        emptyList()
                    }
                    settled = true
                    environment.release(reference, this@ProjectSessionHolder)
                    mutableProject.value = WindowProject.Failed(
                        reference,
                        failed.message?.takeIf { it.isNotBlank() } ?: failed.javaClass.simpleName,
                        failed,
                        kept,
                    )
                    return@launch
                }
                settled = true
                mutableProject.value = WindowProject.Open(session)
            } finally {
                // given up for a newer request of this window, which may want the same project
                if (!settled) releaseIfUnused(reference)
            }
        }
    }

    /**
     * Waits until the window that has [reference]'s project has given it back (its last flush has
     * ended), then claims it. False when another window took it meanwhile.
     */
    private suspend fun claimAfter(wait: SessionEnvironment.ClaimAnswer.Wait, reference: String): Boolean {
        var answer: SessionEnvironment.ClaimAnswer = wait
        while (answer is SessionEnvironment.ClaimAnswer.Wait) {
            answer.lastFlush.join()
            answer = environment.claim(reference, this)
        }
        return answer is SessionEnvironment.ClaimAnswer.Granted
    }

    /** Gives [reference]'s project back to the other windows, unless this window shows or opens it now. */
    private fun releaseIfUnused(reference: String) {
        val current = this.reference
        if (current != null && environment.isSameProject(current, reference)) return
        environment.release(reference, this)
    }

    /** Opens the session of [reference]; storage's count of the project as open is given back when that fails. */
    private suspend fun openSession(reference: String, drafts: DraftStore): ProjectSession {
        val opened = environment.opener.open(reference)
        return try {
            ProjectSession.open(
                reference = reference,
                store = opened.store,
                mainPath = opened.mainPath,
                singleFile = opened.singleFile,
                drafts = drafts,
                settings = environment.settings,
                editors = environment.editors,
                live = environment.live,
                context = environment.sessionContext,
                parseContext = environment.parseContext,
            )
        } catch (failed: Throwable) {
            environment.opener.close(reference)
            throw failed
        }
    }

    /**
     * Ends [session] without asking and gives its project back (to storage, and to the other
     * windows) once the session's last flush (drafts, and with autosave a save) is done: storage
     * must stay usable, and the drafts nobody else's, until then.
     */
    private fun end(session: ProjectSession, next: String? = null): Job = session.abandon().also { last ->
        // a window that opens the same project again keeps it; otherwise others wait for the last flush
        if (next == null || !environment.isSameProject(next, session.reference)) {
            environment.givingBack(session.reference, this, last)
        }
        last.invokeOnCompletion {
            environment.opener.close(session.reference)
            releaseIfUnused(session.reference)
        }
    }

    /**
     * The window shows no project any more: after [ProjectSession.requestClose] or
     * [ProjectSession.answerClose] returned true, or to leave a failed opening. The returned job
     * ends when the project's last flush is done (null when none was open): join it before deleting
     * the project, then [SessionEnvironment.forgetDrafts].
     */
    fun closed(): Job? {
        opening?.cancel()
        val open = session
        mutableProject.value = WindowProject.None
        return open?.let(::end)
    }

    /** The window has focus again: files may have changed underneath. One look at a time. */
    fun onWindowFocusGained() {
        val session = session ?: return
        if (syncing?.isActive == true) return
        syncing = viewModelScope.launch { session.syncWithStorage() }
    }

    /** The window is no longer visible: the system may kill the app from here on. */
    fun onBackground() {
        session?.flushInBackground()
    }

    override fun onCleared() {
        // the window is gone for good (not a configuration change: the holder outlives those);
        // unsaved text is in the drafts and, with autosave, is saved once more
        val open = session
        mutableProject.value = WindowProject.None
        open?.let(::end)
    }

    /** Creates the holder for an activity: `ViewModelProvider(activity, ProjectSessionHolder.Factory(environment))[ProjectSessionHolder::class.java]`. */
    class Factory(private val environment: SessionEnvironment) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ProjectSessionHolder(environment) as T
    }
}
