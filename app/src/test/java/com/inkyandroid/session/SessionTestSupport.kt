package com.inkyandroid.session

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.core.app.ApplicationProvider
import com.inkyandroid.core.engine.InkIssue
import com.inkyandroid.core.engine.InkSources
import com.inkyandroid.core.live.LiveSession
import com.inkyandroid.core.project.DirectoryProjectStore
import com.inkyandroid.core.project.FileDraftStore
import com.inkyandroid.core.project.ProjectStore
import com.inkyandroid.core.project.StoredFile
import com.inkyandroid.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.IOException

/** An editor as the session sees one. [type] is the user typing; everything else is the session's doing. */
class FakeEditorBuffer(initial: String) : EditorBuffer {
    private var current = initial
    val loads = ArrayList<String>()
    val edits = ArrayList<String>()

    override val text: CharSequence get() = current
    override var cursor = TextPosition(0, 0)
    override var firstVisibleRow = 0
    override var onTextChanged: (() -> Unit)? = null

    fun type(newText: String) {
        current = newText
        onTextChanged?.invoke()
    }

    override fun load(text: String) {
        current = text
        loads.add(text)
    }

    override fun edit(start: Int, end: Int, replacement: String) {
        current = current.replaceRange(start, end, replacement)
        edits.add(replacement)
        onTextChanged?.invoke()
    }
}

/** The live compile session as the project session sees it, recording what it is told. */
class FakeLiveLink : LiveLink {
    var reloads = 0
    var edits = 0
    var closed = false
    var watched: List<String> = emptyList()
    var prefix: String? = null
    var lastSources: (() -> InkSources)? = null
    override val issues = MutableStateFlow<List<InkIssue>>(emptyList())
    override val session: LiveSession? = null

    override fun reloadNow() {
        reloads++
    }

    override fun edited() {
        edits++
    }

    override fun setWatches(expressions: List<String>) {
        watched = expressions
    }

    override fun setInstructionPrefix(prefix: String?) {
        this.prefix = prefix
    }

    override fun close() {
        closed = true
    }
}

/** A real directory store whose writes and listings the test can make fail or take time. */
class ScriptedStore(private val real: ProjectStore, private val root: File) : ProjectStore by real {
    var writeFailure: IOException? = null
    var listFailure: IOException? = null
    var writeDelayMillis = 0L
    val writes = ArrayList<String>()

    /**
     * Storage that writes in place, as the Storage Access Framework does: a write puts this many
     * bytes of the new text into the file and then fails with [writeFailure], or, without one,
     * never returns (the process dies while storage is being written). Null: writes are atomic.
     */
    var tornAfterBytes: Int? = null

    override suspend fun list(): List<StoredFile> {
        listFailure?.let { throw it }
        return real.list()
    }

    override suspend fun write(path: String, text: String): StoredFile {
        tornAfterBytes?.let { bytes ->
            File(root, path).writeBytes(text.toByteArray().copyOf(bytes))
            writeFailure?.let { throw it }
            awaitCancellation()
        }
        writeFailure?.let { throw it }
        delay(writeDelayMillis)
        writes.add(path)
        return real.write(path, text)
    }
}

/**
 * The world of a session test: a project folder and a drafts folder on disk, the app's settings,
 * and virtual time. Sessions are opened on it as the app would, and "killed" by ending their job
 * without a word, as the system ends a process.
 */
class SessionWorld(val scope: TestScope) {
    private val context: Context = ApplicationProvider.getApplicationContext()
    val dispatcher = StandardTestDispatcher(scope.testScheduler)
    val root: File = File(context.cacheDir, "project").apply { deleteRecursively(); mkdirs() }
    val draftsDirectory: File = File(context.filesDir, "drafts/project").apply { deleteRecursively() }
    val settings = AppSettings(context.getSharedPreferences("session-test", Context.MODE_PRIVATE))
    val store = ScriptedStore(DirectoryProjectStore(root, io = dispatcher), root)

    /** The editors and live links of every session opened so far, newest last. */
    val editors = LinkedHashMap<String, FakeEditorBuffer>()
    val lives = ArrayList<FakeLiveLink>()
    val live: FakeLiveLink get() = lives.last()
    private val lifetimes = ArrayList<Job>()
    private var clock = System.currentTimeMillis()

    fun file(path: String) = File(root, path)

    /** A file as it is on storage before the app looks, or as another app changes it. */
    fun onStorage(path: String, text: String) {
        file(path).apply { parentFile?.mkdirs() }.writeText(text)
        // a time stamp of its own, however fast the test runs
        clock += 2_000
        file(path).setLastModified(clock)
    }

    /** The app's settings as a process started later reads them. */
    fun settingsAfterRestart() = AppSettings(context.getSharedPreferences("session-test", Context.MODE_PRIVATE))

    /** The kit's editors of the sessions opened with `kit = true`, by path. */
    val kitEditors = LinkedHashMap<String, KitEditorBuffer>()

    /**
     * Opens a session as the app does. With [kit] the files get the kit's real editor
     * ([KitEditorBuffer], in [kitEditors]) instead of the plain fakes in [editors].
     */
    suspend fun open(
        reference: String = "the-project",
        mainPath: String = "main.ink",
        settings: AppSettings = this.settings,
        kit: Boolean = false,
        live: LiveLinkFactory? = null,
    ): ProjectSession {
        editors.clear()
        kitEditors.clear()
        val lifetime = Job()
        lifetimes.add(lifetime)
        return ProjectSession.open(
            reference = reference,
            store = store,
            mainPath = mainPath,
            drafts = FileDraftStore(draftsDirectory, dispatcher),
            settings = settings,
            editors = if (kit) {
                EditorBufferFactory { path, text -> KitEditorBuffer(text).also { kitEditors[path] = it } }
            } else {
                EditorBufferFactory { path, text -> FakeEditorBuffer(text).also { editors[path] = it } }
            },
            live = live ?: LiveLinkFactory { _: CoroutineScope, sources: () -> InkSources -> FakeLiveLink().also { it.lastSources = sources; lives.add(it) } },
            context = dispatcher + lifetime,
            parseContext = dispatcher,
        )
    }

    /** The system kills the process: everything the sessions were doing stops where it is. */
    fun kill() {
        lifetimes.forEach(Job::cancel)
        lifetimes.clear()
        // a dead process's editors report nothing any more
        kitEditors.values.forEach(KitEditorBuffer::close)
    }

    /** The kit's editor of [path] in [session] (opened with `kit = true`), opened if it was not. */
    fun kitEditor(session: ProjectSession, path: String): KitEditorBuffer {
        checkNotNull(session.document(path)) { "$path is not in the project" }
        return kitEditors.getValue(path)
    }

    /** What Compose does within the frame after state changed: the kit editor's change reaches the session. */
    fun applied() {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The editor of [path] in [session], opened if it was not. */
    fun editor(session: ProjectSession, path: String): FakeEditorBuffer {
        checkNotNull(session.document(path)) { "$path is not in the project" }
        return editors.getValue(path)
    }

    /** Lets [millis] of virtual time pass and everything due run. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun pass(millis: Long) {
        scope.advanceTimeBy(millis)
        scope.runCurrent()
    }

    /** The drafts on disk, by path. */
    suspend fun drafts(): Map<String, String> = FileDraftStore(draftsDirectory, dispatcher).load().associate { it.path to it.text }
}

fun sessionTest(block: suspend SessionWorld.() -> Unit) = runTest {
    val world = SessionWorld(this)
    try {
        world.block()
    } finally {
        world.kill()
    }
}
