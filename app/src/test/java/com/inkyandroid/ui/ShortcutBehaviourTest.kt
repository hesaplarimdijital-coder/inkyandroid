package com.inkyandroid.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkyandroid.session.WindowProject
import com.inkyandroid.testPrefs
import desktopkit.input.CommonShortcuts
import desktopkit.components.CommandPaletteState
import desktopkit.shell.ShellTransitions
import desktopkit.theme.DensityPreference
import desktopkit.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Every key of the shortcut table does something: in a DeX window with a project open, a story that
 * has taken a choice and a file with an issue, each command of the table runs and reports that it
 * acted. The order sets up what the next one needs (a jump before Back, Back before Forward).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-w1920dp-h1080dp-mdpi")
class ShortcutBehaviourTest {

    // a test that hangs fails within its limit instead of holding the machine's shared build lock
    @get:Rule(order = Int.MIN_VALUE)
    val limit = TestTimeLimit()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ui = WindowUi()
    private val commands = CommandHost()
    private val palette = CommandPaletteState()

    @Test
    fun everyShortcutDoesSomething() {
        val main = "INCLUDE scenes.ink\nIt begins.\n-> start\n"
        val scenes = "== start ==\nTODO: describe the room\nA room.\n* [Leave] -> outside\n\n== outside ==\nRain.\n-> END\n"
        val holder = UiWorld.holder(UiWorld.project("Keys", main, mapOf("scenes.ink" to scenes)))
        UiWorld.resetSettings()
        val prefs = testPrefs(ThemeMode.Light, DensityPreference.Pointer)
        compose.setContent {
            InkyApp(holder, UiWorld.app.settings, UiWorld.app.storage, ui, prefs = prefs, transitions = ShellTransitions.None, palette = palette, commands = commands)
        }
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)
        val live = session.live.session!!
        // a choice taken: "step back" has a turn to take back
        compose.runOnUiThread { live.choose(live.state.value.transcript.choices.first()) }
        compose.waitFor { live.state.value.transcript.isOver }
        // the cursor on a divert target: "go to definition" has somewhere to go
        val editor = session.activeDocument.editorState!!
        compose.runOnUiThread { editor.select(editor.text.indexOf("start") + 1) }
        compose.waitForIdle()

        val order = listOf(
            Commands.GOTO_DEFINITION, Commands.BACK, Commands.FORWARD, Commands.NEXT_ISSUE,
            Commands.STEP_BACK, Commands.RESTART,
            Commands.UNDO, Commands.REDO, Commands.SELECT_ALL, Commands.COPY, Commands.CUT, Commands.PASTE,
            Commands.FIND, Commands.REPLACE, Commands.COMPLETE,
            Commands.ZOOM_IN, Commands.ZOOM_OUT, Commands.ZOOM_RESET,
            Commands.SHOW_ISSUES, Commands.SHOW_OUTLINE, Commands.SHOW_WATCHES, Commands.SHOW_PLAYER, Commands.TOGGLE_INSPECTOR,
            CommonShortcuts.TOGGLE_SIDEBAR, CommonShortcuts.SEARCH, Commands.GOTO_ANYTHING, Commands.GOTO_LINE,
            CommonShortcuts.SETTINGS, CommonShortcuts.SHORTCUT_LIST, Commands.DOCUMENTATION, Commands.STATISTICS,
            Commands.NEW_INCLUDE, Commands.NEW_PROJECT, Commands.OPEN_FOLDER, Commands.OPEN_FILE,
            Commands.SAVE, Commands.RELOAD, Commands.EXPORT_JSON, Commands.EXPORT_JS,
            Commands.CLOSE_PROJECT,
        )
        // every key of the table is in the run, or known to be missing from this build
        val missing = setOf(Commands.TOGGLE_COMMENT, CommonShortcuts.NEW_WINDOW)
        assertEquals(shortcuts.shortcuts.map { it.id }.toSet(), order.toSet() + missing)

        val failed = mutableListOf<String>()
        order.forEach { id ->
            var acted = false
            compose.runOnUiThread { acted = commands.perform(id) }
            compose.waitFor { true }
            if (!acted) failed += id
            // what the command opened is put away before the next one; the workspace is shown again
            compose.runOnUiThread {
                ui.dialog = null
                palette.close()
            }
            compose.waitForIdle()
            if (id in LEAVE_THE_WORKSPACE) {
                compose.runOnUiThread { commands.perform(Commands.SHOW_PLAYER) }
                compose.waitForIdle()
            }
        }
        assertTrue("these did nothing: $failed", failed.isEmpty())
        // the last one closed the project (autosave saved it first)
        compose.waitFor { holder.project.value is WindowProject.None }
    }

    private companion object {
        /** The commands that show another screen than the workspace. */
        val LEAVE_THE_WORKSPACE = setOf(CommonShortcuts.SETTINGS, Commands.DOCUMENTATION)
    }
}
