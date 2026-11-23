package com.inkyandroid.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkyandroid.testPrefs
import desktopkit.shell.ShellTransitions
import desktopkit.theme.DensityPreference
import desktopkit.theme.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Kept versions through the screens: bringing one back is itself an answer that keeps what it replaces (review-final F2). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-w1920dp-h1080dp-mdpi")
class KeptVersionsTest {

    @get:Rule(order = Int.MIN_VALUE)
    val limit = TestTimeLimit()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ui = WindowUi()
    private val commands = CommandHost()

    @Test
    fun aTapOnTheRowOnlyLooksAndBringingBackKeepsTheTextItReplaces() {
        val ref = UiWorld.project("Kept", "Hello.\n")
        val holder = UiWorld.holder(ref)
        UiWorld.resetSettings()
        UiWorld.app.settings.updateAutosave(false)
        val prefs = testPrefs(ThemeMode.Light, DensityPreference.Pointer)
        compose.setContent {
            InkyApp(holder, UiWorld.app.settings, UiWorld.app.storage, ui, prefs = prefs, transitions = ShellTransitions.None, commands = commands)
        }
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)
        val editor = session.activeDocument.editorState!!
        compose.runOnUiThread { editor.replace(editor.length, editor.length, "My own line.\n") }
        compose.waitFor { session.project.state.value.hasUnsavedChanges }
        runBlocking { UiWorld.app.storage.internalProjects.store(ref).write(ref.mainPath, "Hello from elsewhere.\n") }
        compose.runOnUiThread { assertEquals(true, commands.perform(Commands.RELOAD)) }
        compose.waitFor { session.project.state.value.conflicts.isNotEmpty() }
        compose.onAllNodesWithText("Keep mine")[0].performClick()
        compose.waitFor { session.kept.value.isNotEmpty() }
        // the writer goes on writing, then opens Kept versions and taps the row to look at it
        compose.runOnUiThread { editor.replace(editor.length, editor.length, "An hour more.\n") }
        compose.runOnUiThread { assertEquals(true, commands.perform(Commands.KEPT_VERSIONS)) }
        compose.waitForIdle()

        compose.onAllNodesWithText("From storage", substring = true)[0].performClick()
        compose.waitForIdle()
        assertTrue("a tap on the row replaces nothing", "An hour more." in editor.text)

        compose.onAllNodesWithText("Bring back")[0].performClick()
        compose.waitFor { editor.text == "Hello from elsewhere.\n" }
        compose.waitFor { session.kept.value.any { "An hour more." in it.text } }
    }

    @Test
    fun aKeptTextOfAFileNoLongerInTheProjectTooLongForTheClipboardIsNotPutThere() {
        val ref = UiWorld.project("Long", "INCLUDE part.ink\n", others = mapOf("part.ink" to "A part.\n"))
        val holder = UiWorld.holder(ref)
        UiWorld.resetSettings()
        UiWorld.app.settings.updateAutosave(false)
        val prefs = testPrefs(ThemeMode.Light, DensityPreference.Pointer)
        compose.setContent {
            InkyApp(holder, UiWorld.app.settings, UiWorld.app.storage, ui, prefs = prefs, transitions = ShellTransitions.None, commands = commands)
        }
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)
        val part = session.document("part.ink")!!.editorState!!
        compose.runOnUiThread { part.replace(part.length, part.length, "My line.\n") }
        compose.waitFor { session.project.state.value.hasUnsavedChanges }
        // a whole book written into that file elsewhere, kept by "keep mine"
        val book = "Bir varmış, bir yokmuş.\n".repeat(10_000)
        runBlocking { UiWorld.app.storage.internalProjects.store(ref).write("part.ink", book) }
        compose.runOnUiThread { assertEquals(true, commands.perform(Commands.RELOAD)) }
        compose.waitFor { session.project.state.value.conflicts.isNotEmpty() }
        compose.onAllNodesWithText("Keep mine")[0].performClick()
        compose.waitFor { session.kept.value.any { it.text == book } }
        // then the file leaves the project
        compose.runOnUiThread { ui.dialog = WindowDialog.Delete("part.ink") }
        compose.onAllNodesWithText("Delete")[0].performClick()
        compose.waitFor { "part.ink" !in session.project }

        compose.runOnUiThread { assertEquals(true, commands.perform(Commands.KEPT_VERSIONS)) }
        compose.waitForIdle()
        compose.onAllNodesWithText("Bring back")[0].performClick()

        compose.waitFor { compose.onAllNodesWithText("too long for the clipboard", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val clipboard = UiWorld.context.getSystemService(android.content.ClipboardManager::class.java)
        assertTrue("nothing that large goes to the clipboard", clipboard.primaryClip == null)
    }
}
