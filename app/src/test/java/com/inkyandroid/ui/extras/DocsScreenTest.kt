package com.inkyandroid.ui.extras

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.lazy.LazyListState
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkyandroid.testPrefs
import com.inkyandroid.ui.extras.docs.DOCS_ZOOM_KEY
import com.inkyandroid.ui.extras.docs.DocsMemory
import com.inkyandroid.ui.extras.docs.DocsTags
import com.inkyandroid.ui.extras.docs.Manual
import com.inkyandroid.ui.extras.docs.ManualParser
import com.inkyandroid.ui.extras.docs.ManualSource
import desktopkit.theme.DensityPreference
import desktopkit.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What the manual's screen does: contents, find, links, zoom, copy, insert and the remembered position. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = Devices.DEX)
class DocsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manual: Manual = ManualParser.parse(context.assets.open(ManualSource.ASSET).bufferedReader().readText())
    private val memory = DocsMemory.of(context)
    private val inserted = ArrayList<String>()

    private val prefs = testPrefs(ThemeMode.Light, DensityPreference.Pointer)

    private fun launch() {
        compose.setContent { ExtrasHost(prefs, TestRoutes.DOCS) { DocsScreen(prefs, insertIntoEditor = { inserted += it }) } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(DocsTags.TEXT)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** The block at the top of the text, as the screen remembers it. */
    private fun topBlock(): Int {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        return memory.position.block
    }

    @Test
    fun anEntryOfTheContentsOpensItsSection() {
        launch()
        val entry = manual.contents.first { it.anchor == "3-knots" }
        compose.onNodeWithTag(DocsTags.CONTENTS).performScrollToNode(hasText(entry.title))
        compose.onNode(hasText(entry.title) and hasClickAction()).performClick()
        assertEquals(entry.block, topBlock())
    }

    @Test
    fun findCountsTheMatchesAndStepsThroughThem() {
        launch()
        compose.onNodeWithTag(DocsTags.FIND).performTextInput("tunnel")
        compose.waitForIdle()
        val matches = manual.find("tunnel")
        compose.onNodeWithTag(DocsTags.FIND_COUNT).assert(hasText("1 / ${matches.size}"))
        assertEquals(matches[0].block, topBlock())
        compose.onAllNodesWithContentDescription("Sonraki sonuç").onFirst().performClick()
        compose.onNodeWithTag(DocsTags.FIND_COUNT).assert(hasText("2 / ${matches.size}"))
        // backwards from the first wraps round to the last
        compose.onAllNodesWithContentDescription("Önceki sonuç").onFirst().performClick()
        compose.onNodeWithTag(DocsTags.FIND_COUNT).assert(hasText("1 / ${matches.size}"))
        assertEquals(matches[0].block, topBlock())
        compose.onAllNodesWithContentDescription("Önceki sonuç").onFirst().performClick()
        compose.onNodeWithTag(DocsTags.FIND_COUNT).assert(hasText("${matches.size} / ${matches.size}"))
        // the last match is near the end, where the text cannot scroll it to the top: it ends on screen
        val top = topBlock()
        assertTrue("top $top, matches ${matches.map { it.block }}, blocks ${manual.blocks.size}", top > matches[1].block && top <= matches.last().block)
    }

    @Test
    fun aQueryWithoutMatchesSaysSo() {
        launch()
        compose.onNodeWithTag(DocsTags.FIND).performTextInput("zzzz")
        compose.onNodeWithTag(DocsTags.FIND_COUNT).assert(hasText("Sonuç yok"))
    }

    @Test
    fun aLinkInsideTheManualJumpsToItsSection() {
        val target = manual.resolve("conditional-choices")!!
        val source = manual.blocks.indexOfFirst { "Conditional Choices" in it.plainText && it !is com.inkyandroid.ui.extras.docs.ManualBlock.Heading }
        memory.updatePosition(DocsMemory.Position(source, 0))
        launch()
        val link = SemanticsMatcher("link") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Conditional Choices" } == true &&
                node.config.getOrNull(SemanticsActions.OnClick) != null
        }
        compose.onAllNodes(link).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(target, topBlock())
    }

    @Test
    fun theReadingPositionIsKept() {
        val section = manual.contents.first { it.anchor == "2-nested-flow" }
        memory.updatePosition(DocsMemory.Position(section.block, 0))
        launch()
        assertEquals(section.block, topBlock())
    }

    @Test
    fun ctrlPlusAndMinusChangeTheTextSize() {
        launch()
        compose.onNodeWithTag(DocsTags.FIND).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag(DocsTags.FIND).performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.Equals); pressKey(Key.Equals) }
        }
        compose.waitForIdle()
        assertEquals(1.2f, prefs.editorTextScale(DOCS_ZOOM_KEY), 0.001f)
        compose.onNodeWithTag(DocsTags.FIND).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Zero) } }
        compose.waitForIdle()
        assertEquals(1f, prefs.editorTextScale(DOCS_ZOOM_KEY), 0.001f)
    }

    @Test
    fun anExampleIsCopiedAndInsertedAsWritten() {
        val comments = manual.resolve("comments")!!
        memory.updatePosition(DocsMemory.Position(comments, 0))
        launch()
        val example = manual.blocks.drop(comments).filterIsInstance<com.inkyandroid.ui.extras.docs.ManualBlock.Code>().first()
        compose.onAllNodesWithContentDescription("Örneği kopyala").onFirst().performClick()
        compose.waitForIdle()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        assertEquals(example.code, clipboard.primaryClip?.getItemAt(0)?.text.toString())
        compose.onAllNodesWithContentDescription("Düzenleyiciye ekle").onFirst().performClick()
        assertEquals(listOf(example.code), inserted)
        assertTrue(example.code.contains('\t'))
    }
}
