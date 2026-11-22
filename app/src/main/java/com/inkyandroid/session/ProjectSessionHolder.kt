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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/** A project reference resolved to its storage. */
class OpenedProject(val store: ProjectStore, val mainPath: String)

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
        return OpenedProject(storage.open(ref), ref.mainPath)
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
 */
class SessionEnvironment(
    val settings: AppSettings,
    val opener: ProjectOpener,
    val editors: EditorBufferFactory,
    val live: LiveLinkFactory,
    val draftsDirectory: File,
    val io: CoroutineDispatcher = Dispatchers.IO,
    val parseContext: CoroutineContext = Dispatchers.Default,
) {
    /** The drafts store of the project with [reference]: a folder named after the hash of the project's identity. */
    fun draftsFor(reference: String): DraftStore = FileDraftStore(draftsFolder(reference), io)

    /** The folder [draftsFor] keeps the drafts of [reference] in. */
    fun draftsFolder(reference: String): File =
        File(draftsDirectory, TextHash.of(opener.identity(reference)).take(DRAFT_FOLDER_LENGTH))

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
     */
    data class Failed(val reference: String, val message: String, val cause: IOException? = null) : WindowProject
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
     * or else the project of the last session. Does nothing after the first call, so it is safe in
     * every `onCreate`.
     */
    fun restore(savedReference: String?) {
        if (restored) return
        restored = true
        val reference = savedReference ?: environment.settings.lastSession?.project ?: return
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
        opening?.cancel()
        val previous = session
        if (carried == null) previous?.let(::end)
        mutableProject.value = WindowProject.Opening(reference)
        opening = viewModelScope.launch {
            val drafts = environment.draftsFor(reference)
            if (carried != null) {
                // the drafts move to where the new place files them, unless that is where they are
                val target = drafts.takeIf { environment.draftsFolder(reference) != environment.draftsFolder(carried.reference) }
                try {
                    carried.handOver(target)
                } catch (failed: IOException) {
                    // the unsaved text could not be carried over: the window keeps the project it has
                    carried.draftsFailed(failed)
                    mutableProject.value = WindowProject.Open(carried)
                    return@launch
                }
                environment.opener.close(carried.reference)
            }
            mutableProject.value = try {
                val opened = environment.opener.open(reference)
                val session = try {
                    ProjectSession.open(
                        reference = reference,
                        store = opened.store,
                        mainPath = opened.mainPath,
                        drafts = drafts,
                        settings = environment.settings,
                        editors = environment.editors,
                        live = environment.live,
                        parseContext = environment.parseContext,
                    )
                } catch (failed: Throwable) {
                    // storage counts the project as open in a window: it is not
                    environment.opener.close(reference)
                    throw failed
                }
                WindowProject.Open(session)
            } catch (failed: IOException) {
                WindowProject.Failed(reference, failed.message?.takeIf { it.isNotBlank() } ?: failed.javaClass.simpleName, failed)
            }
        }
    }

    /**
     * Ends [session] without asking and gives its project's storage back once the session's last
     * flush (drafts, and with autosave a save) is done: storage must stay usable until then.
     */
    private fun end(session: ProjectSession) {
        session.abandon().invokeOnCompletion { environment.opener.close(session.reference) }
    }

    /** The window shows no project any more: after [ProjectSession.requestClose] or [ProjectSession.answerClose] returned true, or to leave a failed opening. */
    fun closed() {
        opening?.cancel()
        session?.let(::end)
        mutableProject.value = WindowProject.None
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
        session?.let(::end)
    }

    /** Creates the holder for an activity: `ViewModelProvider(activity, ProjectSessionHolder.Factory(environment))[ProjectSessionHolder::class.java]`. */
    class Factory(private val environment: SessionEnvironment) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ProjectSessionHolder(environment) as T
    }
}
