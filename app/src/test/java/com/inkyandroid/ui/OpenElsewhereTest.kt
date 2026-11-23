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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * One window per project, the way a writer meets it in DeX: a project another window has open is
 * refused with a notice, and this window's own project goes on working (review-final F1, F3).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-w1920dp-h1080dp-mdpi")
class OpenElsewhereTest {

    @get:Rule(order = Int.MIN_VALUE)
    val limit = TestTimeLimit()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ui = WindowUi()
    private val commands = CommandHost()

    private fun stored(ref: com.inkyandroid.storage.ProjectRef.Internal): String =
        runBlocking { UiWorld.app.storage.internalProjects.store(ref).read(ref.mainPath) }

    @Test
    fun choosingInRecentProjectsAProjectOpenInAnotherWindowKeepsThisWindowsProjectWorking() {
        val alpha = UiWorld.project("Alpha", "One.\n")
        val beta = UiWorld.project("Beta", "Two.\n")
        val other = UiWorld.holder(alpha)
        compose.waitFor { other.session != null }
        val holder = UiWorld.holder(beta)
        UiWorld.resetSettings()
        val prefs = testPrefs(ThemeMode.Light, DensityPreference.Pointer)
        compose.setContent {
            InkyApp(holder, UiWorld.app.settings, UiWorld.app.storage, ui, prefs = prefs, transitions = ShellTransitions.None, commands = commands)
        }
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)

        // File › Recent projects › Alpha, which the other window has open
        compose.runOnUiThread { assertEquals(true, commands.perform(Commands.OPEN_RECENT)) }
        compose.waitForIdle()
        compose.onAllNodesWithText("Alpha")[0].performClick()
        compose.waitFor { compose.onAllNodesWithText("This project is open in another window").fetchSemanticsNodes().isNotEmpty() }

        assertSame("the window keeps its project", session, holder.session)
        assertFalse("and its session is alive", session.state.value.closed)
        // what the writer types now is saved, as anywhere else
        val editor = session.activeDocument.editorState!!
        compose.runOnUiThread { editor.replace(editor.length, editor.length, "A line typed after the notice.\n") }
        compose.waitFor { session.project.state.value.hasUnsavedChanges }
        compose.runOnUiThread { commands.perform(Commands.SAVE) }
        compose.waitFor { "after the notice" in stored(beta) }
        assertSame("the other window's project is untouched", other.session, other.session)
        assertEquals("One.\n", stored(alpha))
    }
}
