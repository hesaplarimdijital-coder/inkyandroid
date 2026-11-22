package com.inkyandroid.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inkyandroid.testPrefs
import com.inkyandroid.ui.InkyApp
import desktopkit.shell.ShellTransitions
import desktopkit.theme.DensityPreference
import desktopkit.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * The app's screens as pictures, in Turkish, at the sizes of the devices it is made for: the phone
 * (Galaxy S24, 411 x 891 dp) and a DeX window on a monitor (1920 x 1080 dp, mouse and keyboard).
 * The tablet (Galaxy Tab S11 Ultra, 924 x 1480 and 1480 x 924 dp) joins them with the first real
 * screens.
 *
 * The PNGs land in build/outputs/screenshots. CI compares them with src/test/screenshots, reports
 * what changed (tools/screenshot_report.py) and pushes them to ci/screenshots-pr-<number>.
 * Glass is drawn in its translucent form: the test renderer cannot blur.
 *
 * The window is drawn into a bitmap directly, as in the kit's screenshot tests.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppScreenshotTest {

    // a window nobody has touched hands its focus to the first control and draws a focus ring there
    @get:Rule(order = 0)
    val touchMode = object : ExternalResource() {
        override fun before() = InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun launch(theme: ThemeMode, density: DensityPreference = DensityPreference.Touch) {
        val prefs = testPrefs(theme, density)
        // the test activity's theme has an action bar; the app's has none
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent { InkyApp(prefs = prefs, transitions = ShellTransitions.None) }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val window = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
        compose.runOnIdle { window.draw(Canvas(bitmap)) }
        directory.mkdirs()
        FileOutputStream(File(directory, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @Config(qualifiers = PHONE)
    fun phoneSetupDark() {
        launch(ThemeMode.Dark)
        capture("phone-setup-dark")
    }

    @Test @Config(qualifiers = DEX)
    fun dexSetupLight() {
        launch(ThemeMode.Light, DensityPreference.Pointer)
        capture("dex-setup-light")
    }

    private companion object {
        private const val PHONE = "tr-w411dp-h891dp-xhdpi"
        private const val DEX = "tr-w1920dp-h1080dp-mdpi"

        private val directory = File(System.getProperty("inky.screenshotDir") ?: "build/outputs/screenshots")
    }
}
