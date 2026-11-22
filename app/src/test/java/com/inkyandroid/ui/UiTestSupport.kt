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
import com.inkyandroid.session.ProjectSessionHolder
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

    /** A window's holder with [ref] being opened in it, as the activity's would be. */
    fun holder(ref: ProjectRef? = null): ProjectSessionHolder = ProjectSessionHolder(app.sessionEnvironment).also { holder ->
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
 * Waits until [condition] holds, running the main looper's due messages on every look: what the app
 * posts to `Dispatchers.Main` (a session that finished opening, a compile's result) runs only when
 * someone runs the looper, and Compose's own waitUntil only advances its frame clock.
 */
fun ComposeTestRule.waitFor(timeoutMillis: Long = TIMEOUT_MS, condition: () -> Boolean) {
    waitUntil(timeoutMillis) {
        org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle()
        condition()
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
