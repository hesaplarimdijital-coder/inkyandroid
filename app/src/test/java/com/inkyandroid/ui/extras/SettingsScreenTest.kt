package com.inkyandroid.ui.extras

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkyandroid.settings.AppSettings
import com.inkyandroid.settings.EditorFace
import com.inkyandroid.storage.PickedFolder
import com.inkyandroid.storage.StorageProblem
import com.inkyandroid.testPrefs
import com.inkyandroid.ui.extras.settings.LicenceTags
import desktopkit.input.CommonShortcuts
import desktopkit.input.ShortcutRegistry
import desktopkit.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The settings: the switches change the app's settings, the projects folder is chosen, the licences are read. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = Devices.PHONE)
class SettingsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = AppSettings(context.getSharedPreferences("test-inky", Context.MODE_PRIVATE))
    private val folder = MutableStateFlow<PickedFolder?>(null)
    private var answer: StorageProblem? = null
    private val picked = ArrayList<Uri>()
    private val control = ProjectsFolderControl(
        folder = folder,
        set = { tree ->
            picked += tree
            answer ?: null.also { folder.value = PickedFolder(tree.toString(), "primary:Documents/Inky", "Inky") }
        },
        clear = { folder.value = null },
    )
    private val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FInky")

    private fun launch() {
        val pickers = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, tree)
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = pickers }
        val prefs = testPrefs(ThemeMode.Light)
        val shortcuts = ShortcutRegistry(listOf(CommonShortcuts.ViewGroup, CommonShortcuts.HelpGroup), CommonShortcuts.all)
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ExtrasHost(prefs, TestRoutes.SETTINGS) { SettingsScreen(prefs, settings, shortcuts, control) }
            }
        }
        compose.waitForIdle()
    }

    private fun tap(text: String) {
        compose.onNodeWithTag(SettingsTags.LIST).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    @Test
    fun theSwitchesChangeTheAppsSettings() {
        launch()
        tap("Orantılı")
        assertEquals(EditorFace.Proportional, settings.editorFace)
        tap("Uzun satırları kaydır")
        assertFalse(settings.softWrap)
        tap("Etiketleri göster")
        assertFalse(settings.tagsVisible)
        tap("Otomatik kaydet")
        assertFalse(settings.autosave)
        // with autosave off the note says how saving works then
        compose.onNodeWithTag(SettingsTags.LIST).performScrollToNode(hasText("Ctrl+S ya da Kaydet", substring = true))
    }

    @Test
    fun theProjectsFolderIsChosenAndCleared() {
        launch()
        compose.onNodeWithTag(SettingsTags.LIST).performScrollToNode(hasText("Seçilmedi", substring = true))
        tap("Seç…")
        assertEquals(listOf(tree), picked)
        compose.onNodeWithText("Inky").assertExists()
        tap("Uygulamanın içinde tut")
        assertNull(folder.value)
    }

    @Test
    fun aFolderThatCannotBeUsedSaysWhy() {
        answer = StorageProblem.PermissionLost
        launch()
        tap("Seç…")
        compose.onNodeWithTag(SettingsTags.FOLDER_PROBLEM).assertExists()
        assertNull(folder.value)
    }

    @Test
    fun theLicencesAreReadFromAbout() {
        launch()
        tap("Açık kaynak lisansları")
        compose.onNodeWithTag(LicenceTags.LIST).performScrollToNode(hasText("commonmark-java"))
        compose.onNodeWithText("commonmark-java").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag(LicenceTags.TEXT).assertExists()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Atlassian Pty Ltd", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
