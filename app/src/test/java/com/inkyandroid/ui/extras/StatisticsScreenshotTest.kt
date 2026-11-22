package com.inkyandroid.ui.extras

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inkyandroid.R
import com.inkyandroid.testPrefs
import desktopkit.DesktopKitRoot
import desktopkit.components.DeskButton
import desktopkit.components.DialogFrame
import desktopkit.theme.DensityPreference
import desktopkit.theme.DeskTheme
import desktopkit.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Word count and more" as pictures. A dialog has a window of its own, which the activity's picture
 * does not hold: the dialog's card is drawn inline, as the kit's own dialog screenshots do.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatisticsScreenshotTest {

    @get:Rule(order = 0)
    val touchMode = object : ExternalResource() {
        override fun before() = InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val numbers = StoryStatistics(words = 41_327, knots = 112, stitches = 64, functions = 9, choices = 388, gathers = 151, diverts = 702)

    private fun show(theme: ThemeMode, density: DensityPreference, state: StatisticsState) {
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            DesktopKitRoot(testPrefs(theme, density)) {
                Box(Modifier.fillMaxSize().background(DeskTheme.colors.scrim), contentAlignment = Alignment.Center) {
                    DialogFrame(
                        onDismiss = {},
                        title = stringResource(R.string.statistics_title),
                        modifier = Modifier.padding(24.dp),
                        maxWidth = DIALOG_WIDTH,
                        actions = { DeskButton(stringResource(R.string.statistics_close), {}, primary = true) },
                    ) { StatisticsBody(state) }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test @Config(qualifiers = Devices.PHONE)
    fun phoneLight() {
        show(ThemeMode.Light, DensityPreference.Touch, StatisticsState.Counted(numbers))
        compose.capture("statistics-phone-light")
    }

    @Test @Config(qualifiers = Devices.DEX)
    fun dexDark() {
        show(ThemeMode.Dark, DensityPreference.Pointer, StatisticsState.Counted(numbers))
        compose.capture("statistics-dex-dark")
    }

    @Test @Config(qualifiers = Devices.TABLET)
    fun tabletCountingDark() {
        show(ThemeMode.Dark, DensityPreference.Touch, StatisticsState.Counting)
        compose.capture("statistics-tablet-counting-dark")
    }
}
