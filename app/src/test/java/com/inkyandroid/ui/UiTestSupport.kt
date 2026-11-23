package com.inkyandroid.ui

import android.content.Context
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.inkyandroid.InkyApplication
import com.inkyandroid.core.live.CompileStatus
import com.inkyandroid.core.live.LiveState
import com.inkyandroid.session.ProjectSession
import com.inkyandroid.session.KitEditorBuffer
import com.inkyandroid.session.LiveSessionLink
import com.inkyandroid.session.ProjectSessionHolder
import com.inkyandroid.session.SessionEnvironment
import com.inkyandroid.session.StorageProjectOpener
import kotlinx.coroutines.asCoroutineDispatcher
import com.inkyandroid.session.WindowProject
import com.inkyandroid.storage.ProjectRef
import kotlinx.coroutines.runBlocking

/** The app as a UI test sees it: its real settings, storage, session environment and ink thread. */
object UiWorld {
    val context: Context get() = ApplicationProvider.getApplicationContext()
    val app: InkyApplication get() = context as InkyApplication

    /**
     * A project kept inside the app, with [main] as its main file's text and [others] as further
     * files by path. Robolectric gives every test a fresh app, so names never collide.
     */
    fun project(name: String, main: String, others: Map<String, String> = emptyMap()): ProjectRef.Internal = runBlocking {
        val ref = app.storage.internalProjects.create(name, main)
        val store = app.storage.internalProjects.store(ref)
        others.forEach { (path, text) -> store.write(path, text) }
        ref
    }

    /**
     * The app's settings back at their defaults. There is one instance per process, so what one test
     * chose (an inspector tab, hidden tags) would otherwise be there in the next.
     */
    fun resetSettings() {
        app.settings.apply {
            updateAutosave(true)
            updateSoftWrap(true)
            updateLineNumbers(true)
            updateAutoComplete(true)
            updateKeyboardSuggestions(true)
            updatePlayAnimation(true)
            updateTagsVisible(true)
            updateInspectorTab(com.inkyandroid.settings.InspectorTab.Player)
            updateInspectorCollapsed(false)
            updateInspectorShare(0.5f)
            updateLastSession(null)
        }
    }

    /** The one ink thread of the tests, as the app has one: the ink library must never run on two threads at once. */
    private val ink = java.util.concurrent.Executors.newSingleThreadExecutor { work -> Thread(work, "ink").apply { isDaemon = true } }.asCoroutineDispatcher()

    private val environments = java.util.WeakHashMap<InkyApplication, SessionEnvironment>()

    /**
     * The session environment of this test's app, made as the app's own is. Not the process's
     * ([SessionEnvironment.of]): that one keeps the first application it saw, and every test has
     * an application of its own, with its own files.
     */
    val environment: SessionEnvironment
        get() = environments.getOrPut(app) {
            SessionEnvironment(
                settings = app.settings,
                opener = StorageProjectOpener(app.storage),
                editors = KitEditorBuffer.Factory,
                live = LiveSessionLink.factory(ink),
                draftsDirectory = java.io.File(app.filesDir, "drafts"),
            )
        }

    /** A window's holder with [ref] being opened in it, as the activity's would be. */
    fun holder(ref: ProjectRef? = null): ProjectSessionHolder = ProjectSessionHolder(environment).also { holder ->
        if (ref != null) holder.open(ref.encode())
    }
}

/** Waits until [holder] has its project open; the session. */
fun ComposeTestRule.awaitSession(holder: ProjectSessionHolder): ProjectSession {
    waitOrExplain("the project ${holder.project.value} to open") { holder.session != null || holder.project.value is WindowProject.Failed }
    (holder.project.value as? WindowProject.Failed)?.let { failed -> throw AssertionError("the project did not open: " + failed.message) }
    return holder.session!!
}

/** Waits until the live story of [session] has compiled and played as far as it goes; its state. */
fun ComposeTestRule.awaitStory(session: ProjectSession, status: CompileStatus = CompileStatus.Ok): LiveState {
    val live = session.live.session!!
    waitOrExplain("the story to come to $status") { live.state.value.let { !it.busy && it.compile == status } }
    waitForIdle()
    return live.state.value
}

/**
 * Waits until [condition] holds, on every look sending the snapshot system's apply notifications and
 * running the main looper's due messages: a change made outside a composition (an edit through the
 * editor's state) reaches its watchers (`snapshotFlow`) only with the notifications, which Compose
 * sends from a process-wide manager that earlier tests in the same JVM can leave behind; and what
 * the app posts to `Dispatchers.Main` (a session that finished opening, a compile's result) runs only
 * when someone runs the looper, while Compose's own waitUntil only advances its frame clock.
 */
fun ComposeTestRule.waitFor(timeoutMillis: Long = TIMEOUT_MS, condition: () -> Boolean) {
    waitUntil(timeoutMillis) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        runDueMainTasks()
        condition()
    }
}

/**
 * Runs the main looper's messages that are due, at most [limit] of them. Never the looper's own
 * `idle()`: a popup that is open re-posts its position check every frame, and an unbounded idle
 * then spins for ever (once seen: a test held the machine's build lock for a quarter of an hour).
 */
fun runDueMainTasks(limit: Int = 1_000) {
    val looper = org.robolectric.shadows.ShadowLooper.shadowMainLooper()
    var ran = 0
    while (ran < limit && !looper.isIdle) {
        looper.runOneTask()
        ran++
    }
}

/**
 * Fails a test that runs longer than [seconds] instead of letting it hold the machine's shared build
 * lock: the test's thread is interrupted (the waits here sleep between looks, so they notice), and
 * if it is still running half a minute later, stuck where nothing notices, the test JVM is ended
 * with the threads' stacks on stderr, so that Gradle reports the failure and lets go of the lock.
 */
class TestTimeLimit(private val seconds: Long = 90) : org.junit.rules.TestRule {
    override fun apply(base: org.junit.runners.model.Statement, description: org.junit.runner.Description) = object : org.junit.runners.model.Statement() {
        override fun evaluate() {
            val test = Thread.currentThread()
            val guard = Thread({
                try {
                    Thread.sleep(seconds * 1_000)
                    test.interrupt()
                    Thread.sleep(30_000)
                    System.err.println("${description.displayName} still runs ${seconds + 30} s after it started; ending the test JVM")
                    Thread.getAllStackTraces().forEach { (thread, stack) -> System.err.println(thread.name + "\n  " + stack.take(120).joinToString("\n  ")) }
                    Runtime.getRuntime().halt(3)
                } catch (finished: InterruptedException) {
                    // the test ended in time
                }
            }, "test time limit").apply { isDaemon = true }
            guard.start()
            try {
                base.evaluate()
            } finally {
                guard.interrupt()
            }
            if (Thread.interrupted()) throw AssertionError("${description.displayName} ran longer than $seconds s")
        }
    }
}

/** Clicks the node with exactly [text], once it is there (the story's next turn arrives a moment after a choice). */
fun ComposeTestRule.clickText(text: String) {
    waitFor { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText(text).performClick()
}

/** [waitFor], and if the time runs out, what the threads and the main looper were doing then, to say why. */
private fun ComposeTestRule.waitOrExplain(what: String, condition: () -> Boolean) {
    try {
        waitFor(TIMEOUT_MS, condition)
    } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
        val threads = Thread.getAllStackTraces().filterKeys { thread ->
            thread.name.startsWith("ink") || thread.name.startsWith("DefaultDispatcher") || thread.name.contains("main", ignoreCase = true)
        }
        val dump = threads.entries.joinToString("\n\n") { (thread, stack) -> thread.name + " " + thread.state + "\n  " + stack.take(25).joinToString("\n  ") }
        val looper = org.robolectric.shadows.ShadowLooper.shadowMainLooper()
        val clock = "main looper idle=" + looper.isIdle + " next task at " + looper.nextScheduledTaskTime + " now " + android.os.SystemClock.uptimeMillis()
        throw AssertionError("waited in vain for $what\n$clock\n$dump", timeout)
    }
}

/** The first compile in a process warms the ink library up; a slow build machine gets the time it needs. */
private const val TIMEOUT_MS = 30_000L
