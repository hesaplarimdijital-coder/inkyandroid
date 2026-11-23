package com.inkyandroid.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkyandroid.session.ProjectSessionHolder
import com.inkyandroid.testPrefs
import desktopkit.shell.ShellTransitions
import desktopkit.theme.DensityPreference
import desktopkit.theme.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What the session's answers (app-session's review R4 and R6) look like on screen. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-w1920dp-h1080dp-mdpi")
class SessionScreensBehaviourTest {

    // a test that hangs fails within its limit instead of holding the machine's shared build lock
    @get:Rule(order = Int.MIN_VALUE)
    val limit = TestTimeLimit()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ui = WindowUi()
    private val commands = CommandHost()

    private fun launch(holder: ProjectSessionHolder) {
        UiWorld.resetSettings()
        val prefs = testPrefs(ThemeMode.Light, DensityPreference.Pointer)
        compose.setContent {
            InkyApp(holder, UiWorld.app.settings, UiWorld.app.storage, ui, prefs = prefs, transitions = ShellTransitions.None, commands = commands)
        }
    }

    /** A conflict answered with "Load from storage": the writer's text is kept, and Kept versions brings it back. */
    @Test
    fun theTextAConflictsAnswerReplacedComesBackFromKeptVersions() {
        val ref = UiWorld.project("Kept", "Hello.\n")
        UiWorld.app.settings.updateAutosave(false)
        val holder = UiWorld.holder(ref)
        launch(holder)
        UiWorld.app.settings.updateAutosave(false)
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)
        val editor = session.activeDocument.editorState!!
        compose.runOnUiThread { editor.replace(editor.length, editor.length, "My own line.\n") }
        compose.waitFor { session.project.state.value.hasUnsavedChanges }
        // someone else changed the file on storage meanwhile
        runBlocking { UiWorld.app.storage.internalProjects.store(ref).write(ref.mainPath, "Hello from elsewhere.\n") }
        compose.runOnUiThread { assertEquals(true, commands.perform(Commands.RELOAD)) }
        compose.waitFor { session.project.state.value.conflicts.isNotEmpty() }
        compose.onNodeWithText("Load from storage").performClick()
        compose.waitFor { editor.text.toString() == "Hello from elsewhere.\n" }
        compose.waitFor { session.kept.value.isNotEmpty() }
        compose.runOnUiThread { assertEquals(true, commands.perform(Commands.KEPT_VERSIONS)) }
        compose.waitForIdle()
        compose.onAllNodesWithText("Your text", substring = true)[0].assertExists()
        // the rows stand newest first, as the dialog orders them: the writer's own text is brought back
        val shown = session.kept.value.sortedByDescending { it.keptAt }
        val mine = shown.indexOfFirst { it.origin == com.inkyandroid.core.project.KeptVersion.Origin.Mine }
        compose.onAllNodesWithText("Bring back")[mine].performClick()
        compose.waitFor { editor.text.toString() == "Hello.\nMy own line.\n" }
        // as one edit: Undo takes it out again
        compose.runOnUiThread { editor.undo() }
        assertEquals("Hello from elsewhere.\n", editor.text.toString())
    }

    /** A window with a project asked for one another window has open keeps its own, and says so once. */
    @Test
    fun aProjectOpenInAnotherWindowIsRefusedWithANotice() {
        val first = UiWorld.project("First", "One.\n")
        val second = UiWorld.project("Second", "Two.\n")
        val other = UiWorld.holder(first)
        compose.waitFor { other.session != null }
        val holder = UiWorld.holder(second)
        launch(holder)
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)
        compose.runOnUiThread { holder.open(first.encode()) }
        compose.waitFor { compose.onAllNodesWithText("This project is open in another window").fetchSemanticsNodes().isNotEmpty() }
        assertSame(session, holder.session)
        compose.waitFor { holder.declined.value == null }
    }
}
