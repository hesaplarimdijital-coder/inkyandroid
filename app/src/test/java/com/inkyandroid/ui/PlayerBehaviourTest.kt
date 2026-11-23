package com.inkyandroid.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The player after a choice (F-119, F-120): the new turn is scrolled to, its rule at the top, as
 * desktop Inky scrolls to each new turn; with "Play view animation" on it fades in first. Taken
 * over from review-ui's finding 2: a choice keeps the transcript's generation, and the player waited
 * for a new one.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
class PlayerBehaviourTest {

    // a test that hangs fails within its limit instead of holding the machine's shared build lock
    @get:Rule(order = Int.MIN_VALUE)
    val limit = TestTimeLimit()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ui = WindowUi()

    private fun launch(holder: ProjectSessionHolder) {
        UiWorld.resetSettings()
        val prefs = testPrefs(ThemeMode.Light, DensityPreference.Touch)
        compose.setContent {
            InkyApp(holder, UiWorld.app.settings, UiWorld.app.storage, ui, prefs = prefs, transitions = ShellTransitions.None)
        }
    }

    /** A turn of forty lines, a choice, another turn of forty lines: the reader scrolls to the choice and takes it. */
    private fun chooseAtTheEndOfALongTurn(animation: Boolean) {
        val first = (1..40).joinToString("\n") { "Turn one, line $it." }
        val second = (1..40).joinToString("\n") { "    Turn two, line $it." }
        val holder = UiWorld.holder(UiWorld.project("Long", "$first\n* [Go on]\n$second\n    -> END\n"))
        launch(holder)
        UiWorld.app.settings.updatePlayAnimation(animation)
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)
        compose.onNode(hasText("Play") and isSelectable()).performClick()
        compose.waitForIdle()
        val live = session.live.session!!
        compose.runOnUiThread { runBlocking { ui.playerList.scrollToItem(ui.playerList.layoutInfo.totalItemsCount - 1) } }
        compose.waitForIdle()
        val before = ui.playerList.firstVisibleItemIndex
        compose.onNodeWithText("Go on").performClick()
        compose.waitFor { live.state.value.transcript.liveTurn == 1 }
        val transcript = live.state.value.transcript
        val divider = transcript.items[transcript.appendedFrom!!]
        // the rule that closes the first turn comes to the top of the list (after the fade, with animation)
        compose.waitFor(10_000) { ui.playerList.layoutInfo.visibleItemsInfo.firstOrNull { it.offset >= 0 }?.key == divider.key }
        assertTrue("the player did not move to the new turn", ui.playerList.firstVisibleItemIndex > before)
        compose.onNodeWithText("Turn two, line 1.").assertExists()
        assertEquals(1, live.state.value.transcript.liveTurn)
    }

    @Test
    fun aChoiceScrollsToTheNewTurn() = chooseAtTheEndOfALongTurn(animation = false)

    @Test
    fun aChoiceFadesTheNewTurnInAndScrollsToIt() = chooseAtTheEndOfALongTurn(animation = true)
}
