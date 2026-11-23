package com.inkyandroid.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkyandroid.session.ProjectSessionHolder
import com.inkyandroid.testPrefs
import desktopkit.shell.ShellTransitions
import desktopkit.theme.DensityPreference
import desktopkit.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The project and its files from the screens that change them: the Projects screen, the rename and
 * delete dialogs, a large window's sidebar. Taken over from review-ui's findings 3 to 5.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = FilesAndProjectsBehaviourTest.PHONE)
class FilesAndProjectsBehaviourTest {

    // a test that hangs fails within its limit instead of holding the machine's shared build lock
    @get:Rule(order = Int.MIN_VALUE)
    val limit = TestTimeLimit()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ui = WindowUi()
    private val commands = CommandHost()

    private fun launch(holder: ProjectSessionHolder, density: DensityPreference = DensityPreference.Touch) {
        UiWorld.resetSettings()
        val prefs = testPrefs(ThemeMode.Light, density)
        compose.setContent {
            InkyApp(holder, UiWorld.app.settings, UiWorld.app.storage, ui, prefs = prefs, transitions = ShellTransitions.None, commands = commands)
        }
    }

    /** The project marked as open, chosen on the Projects screen, stays open as it is: no close question, nothing saved or dropped. */
    @Test
    fun choosingTheOpenProjectOnTheProjectsScreenLeavesItOpen() {
        val holder = UiWorld.holder(UiWorld.project("Tale", "Hello.\n"))
        launch(holder)
        UiWorld.app.settings.updateAutosave(false)
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)
        val editor = session.activeDocument.editorState!!
        compose.runOnUiThread { editor.replace(editor.length, editor.length, "Unsaved words.\n") }
        compose.waitFor { session.project.state.value.hasUnsavedChanges }
        compose.runOnUiThread { assertTrue(commands.perform(Commands.OPEN_RECENT)) }
        compose.waitForIdle()
        compose.onAllNodesWithText("Tale")[0].performClick()
        compose.waitForIdle()
        assertFalse("tapping the project marked as open asked to save before closing it", session.state.value.closeQuestion)
        assertSame(session, holder.session)
        assertTrue(session.project.state.value.hasUnsavedChanges)
        assertEquals(Routes.WRITE, ui.route)
    }

    /** A new name typed for a file in a folder keeps the file in that folder. */
    @Test
    fun renamingAFileInAFolderKeepsItInThatFolder() {
        val main = "INCLUDE chapters/one.ink\n-> one\n"
        val holder = UiWorld.holder(UiWorld.project("Folders", main, mapOf("chapters/one.ink" to "== one ==\nOne.\n-> END\n")))
        launch(holder)
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)
        compose.runOnUiThread { ui.dialog = WindowDialog.Rename("chapters/one.ink") }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextReplacement("two.ink")
        compose.onNodeWithText("Rename").performClick()
        compose.waitFor { session.project.state.value.files.none { it.path == "chapters/one.ink" } }
        val paths = session.project.state.value.files.map { it.path }
        assertTrue("after the rename the project has $paths", "chapters/two.ink" in paths)
    }

    /** "Undo" on the notice of a deletion puts the file back into its own project only: not into one opened meanwhile. */
    @Test
    fun undoingADeletionAfterAnotherProjectOpenedLeavesThatProjectAlone() {
        val first = UiWorld.project("First", "INCLUDE notes.ink\nHello.\n", mapOf("notes.ink" to "Some notes.\n"))
        val second = UiWorld.project("Second", "Other.\n")
        val holder = UiWorld.holder(first)
        launch(holder)
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)
        compose.runOnUiThread { ui.dialog = WindowDialog.Delete("notes.ink") }
        compose.waitForIdle()
        compose.onNodeWithText("Delete").performClick()
        compose.waitFor { "notes.ink" !in session.project }
        // the notice still stands while another project is opened in the window
        compose.runOnUiThread { holder.open(second.encode()) }
        compose.waitFor { holder.session?.let { it !== session && "Second.ink" in it.project } == true }
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        val other = holder.session!!
        compose.waitFor { true }
        assertFalse("the deleted file came back in another project", "notes.ink" in other.project)
    }

    /** DeX: the file shown is deleted from its sidebar entry's menu; the main file is shown and marked. */
    @Test
    @Config(qualifiers = DEX)
    fun deletingTheFileShownInALargeWindowShowsTheMainFile() {
        val holder = UiWorld.holder(UiWorld.project("Files", "INCLUDE intro.ink\n-> intro\n", mapOf("intro.ink" to "== intro ==\nHi.\n-> END\n")))
        launch(holder, DensityPreference.Pointer)
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)
        compose.onNode(hasText("intro.ink") and isSelectable()).performClick()
        compose.waitFor { session.state.value.activePath == "intro.ink" }
        compose.runOnUiThread { ui.dialog = WindowDialog.Delete("intro.ink") }
        compose.waitForIdle()
        compose.onNodeWithText("Delete").performClick()
        compose.waitFor { "intro.ink" !in session.project }
        compose.waitForIdle()
        assertEquals("Files.ink", session.state.value.activePath)
        compose.onNode(hasText("Files.ink") and isSelectable()).assertIsSelected()
    }

    /** DeX: the file shown is renamed; the editor and the sidebar's mark follow it. */
    @Test
    @Config(qualifiers = DEX)
    fun renamingTheFileShownInALargeWindowFollowsIt() {
        val holder = UiWorld.holder(UiWorld.project("Files", "INCLUDE intro.ink\n-> intro\n", mapOf("intro.ink" to "== intro ==\nHi.\n-> END\n")))
        launch(holder, DensityPreference.Pointer)
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)
        compose.onNode(hasText("intro.ink") and isSelectable()).performClick()
        compose.waitFor { session.state.value.activePath == "intro.ink" }
        compose.runOnUiThread { ui.dialog = WindowDialog.Rename("intro.ink") }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextReplacement("opening.ink")
        compose.onNodeWithText("Rename").performClick()
        compose.waitFor(10_000) { session.project.state.value.files.any { it.path == "opening.ink" } }
        compose.waitForIdle()
        assertEquals("renamed, but the editor shows another file", "opening.ink", session.state.value.activePath)
        compose.onNode(hasText("opening.ink") and isSelectable()).assertIsSelected()
        assertEquals(Routes.file("opening.ink"), ui.route)
    }

    companion object {
        const val PHONE = "en-w411dp-h891dp-xxhdpi"
        const val DEX = "en-w1920dp-h1080dp-mdpi"
    }
}
