package com.inkyandroid.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkyandroid.core.live.CompileStatus
import com.inkyandroid.core.live.TranscriptItem
import com.inkyandroid.core.project.NewProject
import com.inkyandroid.session.ProjectSessionHolder
import com.inkyandroid.testPrefs
import com.inkyandroid.ui.workspace.editorStateOf
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

/** The app's behaviour end to end: a real project, the real session and the real live story. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = AppBehaviourTest.PHONE)
class AppBehaviourTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ui = WindowUi()
    private val commands = CommandHost()

    private fun launch(holder: ProjectSessionHolder, density: DensityPreference = DensityPreference.Touch) {
        val prefs = testPrefs(ThemeMode.Light, density)
        compose.setContent {
            InkyApp(holder, UiWorld.app.settings, UiWorld.app.storage, ui, prefs = prefs, transitions = ShellTransitions.None, commands = commands)
        }
    }

    @Test
    fun theStoryPlaysAndAChoiceAdvancesIt() {
        val holder = UiWorld.holder(UiWorld.project("Tale", NewProject.MAIN_TEXT))
        launch(holder)
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)
        compose.onNode(hasText("Play") and isSelectable()).performClick()
        compose.onNodeWithText("Once upon a time...").assertExists()
        compose.onNodeWithText("There were two choices.").performClick()
        compose.waitUntil(10_000) { session.live.session!!.state.value.transcript.isOver }
        compose.onNodeWithText("They lived happily ever after.").assertExists()
        compose.onNodeWithText("End of story").assertExists()
        // the choice taken is part of the story now, its row is gone
        assertEquals(0, session.live.session!!.state.value.transcript.choices.size)
    }

    @Test
    fun anIssueOpensItsFileAtItsLine() {
        val main = "INCLUDE other.ink\nHello.\n-> start\n"
        val other = "== start ==\nFine.\n{ broken\n-> END\n"
        val holder = UiWorld.holder(UiWorld.project("Broken", main, mapOf("other.ink" to other)))
        launch(holder)
        val session = compose.awaitSession(holder)
        val state = compose.awaitStory(session, CompileStatus.Errors)
        val issue = state.issues.first { it.file == "other.ink" }
        compose.runOnUiThread { session.showIssue(issue) }
        compose.waitUntil(5_000) { session.state.value.activePath == "other.ink" && session.state.value.showRequest == null }
        val editor = editorStateOf(session.document("other.ink")!!.buffer)
        assertEquals(issue.line!! - 1, editor.cursorPosition.line)
    }

    @Test
    fun goToDefinitionFindsAKnotInAnotherFile() {
        val main = "INCLUDE chapters.ink\nOff we go.\n-> the_end\n"
        val chapters = "== the_end ==\nThat was all.\n-> END\n"
        val holder = UiWorld.holder(UiWorld.project("Links", main, mapOf("chapters.ink" to chapters)))
        launch(holder)
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)
        val editor = editorStateOf(session.document(session.state.value.activePath)!!.buffer)
        compose.runOnUiThread { editor.select(editor.text.indexOf("the_end") + 2) }
        compose.waitForIdle()
        compose.runOnUiThread { assertTrue(ui.editor != null && ui.editor!!.linkAtCursor() != null) }
        compose.runOnUiThread { assertTrue(commands.perform(Commands.GOTO_DEFINITION)) }
        compose.waitUntil(5_000) { session.state.value.activePath == "chapters.ink" && session.state.value.showRequest == null }
        val target = editorStateOf(session.document("chapters.ink")!!.buffer)
        assertEquals(0, target.cursorPosition.line)
        // the jump is a step of the history: Back returns to where it started
        assertTrue(session.state.value.canGoBack)
    }

    @Test
    @Config(qualifiers = TABLET)
    fun aTabletPutsThePlayerBesideTheEditor() {
        val holder = UiWorld.holder(UiWorld.project("Wide", NewProject.MAIN_TEXT))
        launch(holder)
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)
        // the inspector's Player tab is there and the story with it; no Play entry on the rail
        compose.onNodeWithText("Player").assertExists()
        compose.onNodeWithText("Once upon a time...").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Play").fetchSemanticsNodes().count { node -> node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Selected) })
        assertTrue(ui.twoPanes)
    }

    @Test
    @Config(qualifiers = DEX)
    fun aLargeWindowListsTheProjectsFilesAndMarksTheOneInTheEditor() {
        val holder = UiWorld.holder(UiWorld.project("Files", "INCLUDE intro.ink\n-> intro\n", mapOf("intro.ink" to "== intro ==\nHi.\n-> END\n")))
        launch(holder, DensityPreference.Pointer)
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)
        compose.onNode(hasText("Files.ink") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("intro.ink") and isSelectable()).performClick()
        compose.waitUntil(5_000) { session.state.value.activePath == "intro.ink" }
        compose.onNode(hasText("intro.ink") and isSelectable()).assertIsSelected()
    }

    @Test
    fun saveWritesTheTextToStorage() {
        val ref = UiWorld.project("Saved", "First line.\n")
        val holder = UiWorld.holder(ref)
        launch(holder)
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)
        val editor = editorStateOf(session.activeDocument.buffer)
        compose.runOnUiThread { editor.replace(editor.length, editor.length, "Second line.\n") }
        compose.waitUntil(5_000) { session.project.state.value.hasUnsavedChanges }
        compose.runOnUiThread { assertTrue(commands.perform(Commands.SAVE)) }
        compose.waitUntil(10_000) { !session.project.state.value.hasUnsavedChanges }
        val stored = runBlocking { UiWorld.app.storage.internalProjects.store(ref).read(ref.mainPath) }
        assertEquals("First line.\nSecond line.\n", stored)
        compose.onNodeWithContentDescription("Saved.ink, unsaved changes", substring = true).assertDoesNotExist()
    }

    companion object {
        const val PHONE = "en-w411dp-h891dp-xxhdpi"
        const val TABLET = "en-w924dp-h1480dp-xhdpi"
        const val DEX = "en-w1920dp-h1080dp-mdpi"
    }
}
