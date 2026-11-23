package com.inkyandroid.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkyandroid.core.project.NewProject
import com.inkyandroid.session.ProjectSessionHolder
import com.inkyandroid.testPrefs
import desktopkit.shell.ShellTransitions
import desktopkit.theme.DensityPreference
import desktopkit.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** How the window's room shapes the workspace when it changes: narrowed from the sidebar layout, or low (a phone on its side). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-w1920dp-h1080dp-mdpi")
class LayoutBehaviourTest {

    // a test that hangs fails within its limit instead of holding the machine's shared build lock
    @get:Rule(order = Int.MIN_VALUE)
    val limit = TestTimeLimit()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ui = WindowUi()

    private var width by mutableStateOf(1920.dp)
    private var height by mutableStateOf(1080.dp)

    private fun launch(holder: ProjectSessionHolder, density: DensityPreference) {
        UiWorld.resetSettings()
        val prefs = testPrefs(ThemeMode.Light, density)
        compose.setContent {
            // the window's room as the app's root measures it: the layout follows the size given here
            Box(Modifier.size(width, height)) {
                InkyApp(holder, UiWorld.app.settings, UiWorld.app.storage, ui, prefs = prefs, transitions = ShellTransitions.None)
            }
        }
    }

    private fun resize(newWidth: Dp, newHeight: Dp) {
        compose.runOnUiThread {
            width = newWidth
            height = newHeight
        }
        compose.waitForIdle()
    }

    /** Device run s1-a: a large window turned upright keeps the file's page of Write, with a Back arrow on a top-level screen. */
    @Test
    fun aWindowNarrowedFromTheSidebarLayoutShowsWriteItself() {
        val holder = UiWorld.holder(UiWorld.project("Narrow", NewProject.MAIN_TEXT))
        launch(holder, DensityPreference.Pointer)
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)
        compose.waitFor { ui.route == Routes.file("Narrow.ink") }
        resize(800.dp, 1280.dp)
        compose.waitFor { ui.route == Routes.WRITE }
        // and back: the sidebar marks the file again
        resize(1920.dp, 1080.dp)
        compose.waitFor { ui.route == Routes.file("Narrow.ink") }
    }

    /** Device run: a phone on its side stacked the rail's bar, the editor's header and the tabs; the header now folds into the bar. */
    @Test
    fun aLowWindowWithARailFoldsTheEditorsHeaderIntoItsBar() {
        width = 891.dp
        height = 411.dp
        val holder = UiWorld.holder(UiWorld.project("Low", NewProject.MAIN_TEXT))
        launch(holder, DensityPreference.Touch)
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)
        compose.waitFor { ui.route == Routes.WRITE && ui.twoPanes }
        // the rail's bar is titled with the file: "Write" is only the rail's entry, and the file is named once
        compose.waitFor { count("Write") == 1 && count("Low.ink") == 1 }
        // a tall window has both bars: the rail's says "Write", the editor's header names the file
        resize(891.dp, 1200.dp)
        compose.waitFor { count("Write") == 2 && count("Low.ink") == 1 }
    }

    private fun count(text: String): Int = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

}
