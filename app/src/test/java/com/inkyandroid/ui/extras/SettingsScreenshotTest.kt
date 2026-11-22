package com.inkyandroid.ui.extras

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inkyandroid.settings.AppSettings
import com.inkyandroid.storage.PickedFolder
import com.inkyandroid.testPrefs
import desktopkit.input.CommonShortcuts
import desktopkit.input.ShortcutRegistry
import desktopkit.theme.DensityPreference
import desktopkit.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings, about and the licences as pictures, in the three layouts. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenshotTest {

    @get:Rule(order = 0)
    val touchMode = object : ExternalResource() {
        override fun before() = InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun launch(theme: ThemeMode, density: DensityPreference = DensityPreference.Touch, folderChosen: Boolean = false) {
        val prefs = testPrefs(theme, density)
        val settings = AppSettings(context.getSharedPreferences("test-inky", Context.MODE_PRIVATE))
        val folder = MutableStateFlow(if (folderChosen) PickedFolder("content://tree", "primary:Documents/Inky", "Inky") else null)
        val control = ProjectsFolderControl(folder, { null }, { folder.value = null })
        val shortcuts = ShortcutRegistry(listOf(CommonShortcuts.ViewGroup, CommonShortcuts.HelpGroup), CommonShortcuts.all)
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent { ExtrasHost(prefs, TestRoutes.SETTINGS) { SettingsScreen(prefs, settings, shortcuts, control) } }
        compose.waitForIdle()
    }

    private fun scrollTo(text: String) {
        compose.onNodeWithTag(SettingsTags.LIST).performScrollToNode(hasText(text))
        compose.waitForIdle()
    }

    @Test @Config(qualifiers = Devices.PHONE)
    fun phoneAppSectionsDark() {
        launch(ThemeMode.Dark)
        scrollTo("Hakkında")
        compose.capture("settings-phone-app-dark")
    }

    @Test @Config(qualifiers = Devices.PHONE)
    fun phoneLicencesLight() {
        launch(ThemeMode.Light)
        scrollTo("Açık kaynak lisansları")
        compose.onNodeWithText("Açık kaynak lisansları").performSemanticsAction(SemanticsActions.OnClick)
        compose.capture("settings-phone-licences-light")
    }

    @Test @Config(qualifiers = Devices.TABLET)
    fun tabletLight() {
        launch(ThemeMode.Light, folderChosen = true)
        scrollTo("Düzenleyici")
        compose.capture("settings-tablet-light")
    }

    @Test @Config(qualifiers = Devices.DEX)
    fun dexDark() {
        launch(ThemeMode.Dark, DensityPreference.Pointer, folderChosen = true)
        scrollTo("Hakkında")
        compose.capture("settings-dex-dark")
    }

    /** At twice the font size nothing is cut off: rows wrap, buttons go under each other. */
    @Test @Config(qualifiers = Devices.PHONE, fontScale = 2.0f)
    fun phoneLargeFont() {
        launch(ThemeMode.Light)
        scrollTo("Hakkında")
        compose.capture("settings-phone-font200-light")
    }
}
