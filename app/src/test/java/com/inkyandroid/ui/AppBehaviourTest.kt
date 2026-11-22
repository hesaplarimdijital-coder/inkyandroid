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
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkyandroid.core.live.CompileStatus
import com.inkyandroid.core.live.TranscriptItem
import com.inkyandroid.core.project.NewProject
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

/** The app's behaviour end to end: a real project, the real session and the real live story. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = AppBehaviourTest.PHONE)
class AppBehaviourTest {

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

    @Test
    fun theStoryPlaysAndAChoiceAdvancesIt() {
        val holder = UiWorld.holder(UiWorld.project("Tale", NewProject.MAIN_TEXT))
        launch(holder)
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)
        compose.onNode(hasText("Play") and isSelectable()).performClick()
        compose.onNodeWithText("Once upon a time...").assertExists()
        compose.onNodeWithText("There were two choices.").performClick()
        compose.waitFor(10_000) { session.live.session!!.state.value.transcript.isOver }
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
        compose.waitFor(5_000) { session.state.value.activePath == "other.ink" && session.state.value.showRequest == null }
        val editor = session.document("other.ink")!!.editorState!!
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
        val editor = session.document(session.state.value.activePath)!!.editorState!!
        compose.runOnUiThread { editor.select(editor.text.indexOf("the_end") + 2) }
        compose.waitForIdle()
        compose.runOnUiThread { assertTrue(ui.editor != null && ui.editor!!.linkAtCursor() != null) }
        compose.runOnUiThread { assertTrue(commands.perform(Commands.GOTO_DEFINITION)) }
        compose.waitFor(5_000) { session.state.value.activePath == "chapters.ink" && session.state.value.showRequest == null }
        val target = session.document("chapters.ink")!!.editorState!!
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
        compose.waitFor(5_000) { session.state.value.activePath == "intro.ink" }
        compose.onNode(hasText("intro.ink") and isSelectable()).assertIsSelected()
    }

    @Test
    fun saveWritesTheTextToStorage() {
        val ref = UiWorld.project("Saved", "First line.\n")
        val holder = UiWorld.holder(ref)
        launch(holder)
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)
        val editor = session.activeDocument.editorState!!
        compose.runOnUiThread { editor.replace(editor.length, editor.length, "Second line.\n") }
        compose.waitFor(5_000) { session.project.state.value.hasUnsavedChanges }
        compose.runOnUiThread { assertTrue(commands.perform(Commands.SAVE)) }
        compose.waitFor(10_000) { !session.project.state.value.hasUnsavedChanges }
        val stored = runBlocking { UiWorld.app.storage.internalProjects.store(ref).read(ref.mainPath) }
        assertEquals("First line.\nSecond line.\n", stored)
        compose.onNodeWithContentDescription("Saved.ink, unsaved changes", substring = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = DEX)
    fun findTakesTheSelectionAndCountsItsMatches() {
        val holder = UiWorld.holder(UiWorld.project("Trains", "The train was late.\nAnother train came.\nNo train after that.\n"))
        launch(holder, DensityPreference.Pointer)
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)
        val editor = session.activeDocument.editorState!!
        val first = editor.text.indexOf("train")
        compose.runOnUiThread { editor.select(first, first + "train".length) }
        compose.waitForIdle()
        compose.runOnUiThread { assertTrue(commands.perform(Commands.FIND)) }
        // the selection is what is looked for, and it is the first of three matches
        compose.waitFor(10_000) { compose.onAllNodesWithText("1 / 3").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("train") and androidx.compose.ui.test.hasSetTextAction()).assertExists()
    }

    @Test
    fun replaceAllChangesEveryMatchInOneStep() {
        val holder = UiWorld.holder(UiWorld.project("Rename", "VAR hour = 1\n~ hour = hour + 1\n{hour}\n"))
        launch(holder)
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)
        val editor = session.activeDocument.editorState!!
        compose.runOnUiThread { assertTrue(commands.perform(Commands.REPLACE)) }
        compose.waitForIdle()
        compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction())[0].performTextInput("hour")
        compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction())[1].performTextInput("saat")
        compose.waitFor(10_000) { compose.onAllNodesWithText(" / 4", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Replace all").performClick()
        compose.waitFor(10_000) { editor.text.toString() == "VAR saat = 1\n~ saat = saat + 1\n{saat}\n" }
        compose.runOnUiThread { editor.undo() }
        assertEquals("VAR hour = 1\n~ hour = hour + 1\n{hour}\n", editor.text.toString())
    }

    @Test
    fun suggestionsOfferTheProjectsKnotsInADivert() {
        val holder = UiWorld.holder(UiWorld.project("Knots", "-> station\n== station ==\nHere.\n-> END\n"))
        launch(holder)
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)
        val editor = session.activeDocument.editorState!!
        compose.waitFor(10_000) { session.project.state.value.main.symbols != null }
        compose.runOnUiThread {
            editor.replace(editor.length, editor.length, "-> sta")
            editor.select(editor.length)
            assertTrue(commands.perform(Commands.COMPLETE))
        }
        compose.waitFor(10_000) { compose.onAllNodesWithText("Divert target").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Divert target").assertExists()
    }

    companion object {
        const val PHONE = "en-w411dp-h891dp-xxhdpi"
        const val TABLET = "en-w924dp-h1480dp-xhdpi"
        const val DEX = "en-w1920dp-h1080dp-mdpi"
    }
}
