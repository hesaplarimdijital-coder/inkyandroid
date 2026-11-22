package com.inkyandroid.ui.extras

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inkyandroid.testPrefs
import com.inkyandroid.ui.extras.docs.DocsMemory
import com.inkyandroid.ui.extras.docs.DocsTags
import com.inkyandroid.ui.extras.docs.ManualParser
import com.inkyandroid.ui.extras.docs.ManualSource
import desktopkit.theme.DensityPreference
import desktopkit.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The manual's screen as pictures: phone, tablet and DeX, light and dark, at its start and in its examples. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DocsScreenshotTest {

    @get:Rule(order = 0)
    val touchMode = object : ExternalResource() {
        override fun before() = InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Opens the manual at the section with [anchor] (its first block when null). */
    private fun launch(theme: ThemeMode, density: DensityPreference = DensityPreference.Touch, anchor: String? = null, insert: Boolean = false) {
        val prefs = testPrefs(theme, density)
        val manual = ManualParser.parse(context.assets.open(ManualSource.ASSET).bufferedReader().readText())
        val block = anchor?.let { manual.resolve(it) } ?: 0
        DocsMemory.of(context).updatePosition(DocsMemory.Position(block, 0))
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            ExtrasHost(prefs, TestRoutes.DOCS) { DocsScreen(prefs, insertIntoEditor = if (insert) ({ }) else null) }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag(DocsTags.TEXT)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test @Config(qualifiers = Devices.PHONE)
    fun phoneStartDark() {
        launch(ThemeMode.Dark)
        compose.capture("docs-phone-start-dark")
    }

    @Test @Config(qualifiers = Devices.PHONE)
    fun phoneExamplesLight() {
        launch(ThemeMode.Light, anchor = "comments")
        compose.capture("docs-phone-examples-light")
    }

    @Test @Config(qualifiers = Devices.TABLET)
    fun tabletExamplesDark() {
        launch(ThemeMode.Dark, anchor = "tags")
        compose.capture("docs-tablet-examples-dark")
    }

    @Test @Config(qualifiers = Devices.DEX)
    fun dexFindLight() {
        launch(ThemeMode.Light, DensityPreference.Pointer, anchor = "2-choices", insert = true)
        compose.onNodeWithTag(DocsTags.FIND).performTextInput("choice")
        compose.capture("docs-dex-find-light")
    }

    @Test @Config(qualifiers = Devices.DEX)
    fun dexListsDark() {
        launch(ThemeMode.Dark, DensityPreference.Pointer, anchor = "5-functions", insert = true)
        compose.capture("docs-dex-lists-dark")
    }

    /** At twice the font size the manual still reads: examples wrap, the bars keep their place. */
    @Test @Config(qualifiers = Devices.PHONE, fontScale = 2.0f)
    fun phoneLargeFontDark() {
        launch(ThemeMode.Dark, anchor = "comments")
        compose.capture("docs-phone-font200-dark")
    }

    @Test @Config(qualifiers = Devices.PHONE_EN)
    fun phoneContentsSheetEnglishLight() {
        launch(ThemeMode.Light, anchor = "tunnels-run-sub-stories")
        compose.onNodeWithContentDescription("Contents").performClick()
        compose.waitForIdle()
        compose.capture("docs-phone-contents-en-light")
    }
}
