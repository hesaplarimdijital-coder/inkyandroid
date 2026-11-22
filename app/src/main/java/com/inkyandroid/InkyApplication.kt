package com.inkyandroid

import android.app.Application
import com.inkyandroid.session.LiveSessionLink
import com.inkyandroid.session.SessionEnvironment
import com.inkyandroid.session.StorageProjectOpener
import com.inkyandroid.settings.AppSettings
import com.inkyandroid.storage.ProjectStorage
import com.inkyandroid.ui.workspace.editorBuffers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors

/**
 * What every window of the app shares: the settings, the storage of projects, and the one thread
 * all ink work runs on (the ink library must never run on two threads at once, and a story is
 * played by one thread from its start; `ink-engine.md`).
 */
class InkyApplication : Application() {

    val settings: AppSettings by lazy { AppSettings.of(this) }

    val storage: ProjectStorage by lazy { ProjectStorage.from(this) }

    /**
     * The ink thread. Its stack is larger than a thread's default: the ink compiler and runtime
     * recurse with the depth of the story (nested logic, tunnels, recursive functions), and a
     * story that needs more than this is reported by the engine as a stopped turn, never a crash.
     */
    private val inkDispatcher: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { work -> Thread(null, work, "ink", INK_STACK_BYTES).apply { isDaemon = true } }.asCoroutineDispatcher()
    }

    /** What a window's project session is made of. */
    val sessionEnvironment: SessionEnvironment by lazy {
        SessionEnvironment(
            settings = settings,
            opener = StorageProjectOpener(storage),
            editors = editorBuffers(),
            live = LiveSessionLink.factory(inkDispatcher),
            draftsDirectory = File(filesDir, "drafts"),
        )
    }

    private companion object {
        const val INK_STACK_BYTES = 8L * 1024 * 1024
    }
}
