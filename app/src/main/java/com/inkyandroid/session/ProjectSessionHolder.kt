package com.inkyandroid.session

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

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
}

/**
 * What every window's session is built from; one for the process, made by the application.
 *
 * @property draftsDirectory the app-private directory under which each project gets its own drafts
 *   folder (`filesDir/drafts`).
 */
class SessionEnvironment(
    val settings: AppSettings,
    val opener: ProjectOpener,
    val editors: EditorBufferFactory,
    val live: LiveLinkFactory,
    val draftsDirectory: File,
) {
    /** The drafts store of the project with [reference]: a folder named after the reference's hash. */
    fun draftsFor(reference: String): DraftStore = FileDraftStore(File(draftsDirectory, TextHash.of(reference).take(DRAFT_FOLDER_LENGTH)))

    private companion object {
        const val DRAFT_FOLDER_LENGTH = 32
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

    /** The project with [reference] could not be opened; [message] is storage's reason. */
    data class Failed(val reference: String, val message: String) : WindowProject
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
        if (carried == null) previous?.abandon()
        mutableProject.value = WindowProject.Opening(reference)
        opening = viewModelScope.launch {
            mutableProject.value = try {
                val drafts = environment.draftsFor(reference)
                if (carried != null) for (draft in carried.handOver()) drafts.put(draft)
                if (previous != null) environment.opener.close(previous.reference)
                val opened = environment.opener.open(reference)
                WindowProject.Open(
                    ProjectSession.open(
                        reference = reference,
                        store = opened.store,
                        mainPath = opened.mainPath,
                        drafts = drafts,
                        settings = environment.settings,
                        editors = environment.editors,
                        live = environment.live,
                    ),
                )
            } catch (failed: IOException) {
                WindowProject.Failed(reference, failed.message?.takeIf { it.isNotBlank() } ?: failed.javaClass.simpleName)
            }
        }
    }

    /** The window shows no project any more: after [ProjectSession.requestClose] or [ProjectSession.answerClose] returned true, or to leave a failed opening. */
    fun closed() {
        opening?.cancel()
        session?.let { session ->
            session.abandon()
            environment.opener.close(session.reference)
        }
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
        // the window is gone for good; unsaved text is in the drafts and, with autosave, is saved once more
        session?.let { session ->
            session.abandon()
            environment.opener.close(session.reference)
        }
    }

    /** Creates the holder for an activity: `ViewModelProvider(activity, ProjectSessionHolder.Factory(environment))[ProjectSessionHolder::class.java]`. */
    class Factory(private val environment: SessionEnvironment) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ProjectSessionHolder(environment) as T
    }
}
