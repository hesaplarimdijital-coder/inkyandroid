package com.inkyandroid.screenshots

import com.inkyandroid.ui.TestTimeLimit
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inkyandroid.core.live.CompileStatus
import com.inkyandroid.core.snippets.InkSnippets
import com.inkyandroid.core.snippets.SnippetNode
import com.inkyandroid.session.ProjectSessionHolder
import com.inkyandroid.settings.InspectorTab
import com.inkyandroid.testPrefs
import com.inkyandroid.ui.CommandHost
import com.inkyandroid.ui.Commands
import com.inkyandroid.ui.InkyApp
import com.inkyandroid.ui.UiWorld
import com.inkyandroid.ui.WindowDialog
import com.inkyandroid.ui.WindowUi
import com.inkyandroid.ui.awaitSession
import com.inkyandroid.ui.awaitStory
import com.inkyandroid.ui.clickText
import com.inkyandroid.ui.waitFor
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
 * The app's screens as pictures, with real content: a small story in three files (one of them
 * spare) for the everyday screens, the same with errors, warnings and notes for the issues, and
 * upstream's *The Intercept* for a long project. At the sizes of the owner's devices: the phone
 * (Galaxy S24), the tablet (Galaxy Tab S11 Ultra) upright and on its side, and a DeX window on a
 * monitor with mouse and keyboard. Turkish, as he uses it; one English and one 200 % font pass.
 *
 * The PNGs land in build/outputs/screenshots; CI compares them with src/test/screenshots.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppScreenshotTest {

    // a test that hangs fails within its limit instead of holding the machine's shared build lock
    @get:Rule(order = Int.MIN_VALUE)
    val limit = TestTimeLimit()

    // a window nobody has touched hands its focus to the first control and draws a focus ring there
    @get:Rule(order = 0)
    val touchMode = object : ExternalResource() {
        override fun before() = InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ui = WindowUi()
    private val commands = CommandHost()

    private fun launch(holder: ProjectSessionHolder, theme: ThemeMode, density: DensityPreference = DensityPreference.Touch) {
        UiWorld.resetSettings()
        val prefs = testPrefs(theme, density)
        // the test activity's theme has an action bar; the app's has none
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            InkyApp(holder, UiWorld.app.settings, UiWorld.app.storage, ui, prefs = prefs, transitions = ShellTransitions.None, commands = commands)
        }
    }

    /** The small story, opened and played as far as it goes; what it compiled to. */
    private fun story(theme: ThemeMode, density: DensityPreference = DensityPreference.Touch, broken: Boolean = false): ProjectSessionHolder {
        val holder = UiWorld.holder(UiWorld.project(NAME, MAIN, if (broken) BROKEN_FILES else FILES))
        launch(holder, theme, density)
        compose.awaitStory(compose.awaitSession(holder), if (broken) CompileStatus.Errors else CompileStatus.Ok)
        return holder
    }

    private fun intercept(theme: ThemeMode, density: DensityPreference): ProjectSessionHolder {
        val holder = UiWorld.holder(UiWorld.project("The Intercept", interceptText()))
        launch(holder, theme, density)
        compose.awaitStory(compose.awaitSession(holder))
        return holder
    }

    private fun interceptText(): String {
        val full = InkSnippets.builtIn.filterIsInstance<SnippetNode.Category>().first { it.id == "full_stories" }
        return full.children.filterIsInstance<SnippetNode.Snippet>().first { it.id == "story_intercept" }.ink
    }

    private fun tab(name: String) = compose.onNode(hasText(name) and isSelectable()).performClick()

    /**
     * Draws the screen into `<screenshots>/<name>.png`: the activity's window, and over it every
     * window it has up (a dialog, with the dim the system lays under one), where each one is.
     */
    private fun capture(name: String) {
        compose.waitForIdle()
        val window = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
        compose.runOnIdle {
            val canvas = Canvas(bitmap)
            window.draw(canvas)
            windowsAbove(window).forEach { view ->
                val params = view.layoutParams as? android.view.WindowManager.LayoutParams
                if (params != null && params.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                    canvas.drawColor(android.graphics.Color.argb((params.dimAmount * 255).toInt(), 0, 0, 0))
                }
                val at = IntArray(2).also(view::getLocationOnScreen)
                val origin = IntArray(2).also(window::getLocationOnScreen)
                canvas.save()
                canvas.translate((at[0] - origin[0]).toFloat(), (at[1] - origin[1]).toFloat())
                view.draw(canvas)
                canvas.restore()
            }
        }
        directory.mkdirs()
        FileOutputStream(File(directory, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The windows over the activity's own ([main]), bottom to top. */
    @Suppress("UNCHECKED_CAST")
    private fun windowsAbove(main: android.view.View): List<android.view.View> {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val views = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<android.view.View>
        return views.dropWhile { it !== main }.drop(1).filter { it.isAttachedToWindow && it.visibility == android.view.View.VISIBLE }
    }

    // ------------------------------------------------------------------ phone

    @Test @Config(qualifiers = PHONE)
    fun phoneWriteDark() {
        story(ThemeMode.Dark)
        capture("app-phone-write-dark")
    }

    @Test @Config(qualifiers = PHONE)
    fun phoneWriteLight() {
        story(ThemeMode.Light)
        capture("app-phone-write-light")
    }

    @Test @Config(qualifiers = PHONE)
    fun phonePlayLight() {
        story(ThemeMode.Light)
        tab("Oyna")
        capture("app-phone-play-light")
    }

    @Test @Config(qualifiers = PHONE)
    fun phonePlayEndedDark() {
        val holder = story(ThemeMode.Dark)
        tab("Oyna")
        compose.clickText("Bekleme salonuna gir")
        compose.clickText("Sessizce otur")
        compose.clickText("Bekle")
        compose.waitFor(10_000) { holder.session!!.live.session!!.state.value.transcript.isOver }
        capture("app-phone-play-ended-dark")
    }

    @Test @Config(qualifiers = PHONE)
    fun phonePlayStaleWhileTheInkHasErrorsDark() {
        val holder = story(ThemeMode.Dark)
        val session = holder.session!!
        val editor = session.activeDocument.editorState!!
        compose.runOnUiThread { editor.replace(editor.length, editor.length, "{ unclosed\n") }
        compose.awaitStory(session, CompileStatus.Errors)
        tab("Oyna")
        capture("app-phone-play-errors-dark")
    }

    @Test @Config(qualifiers = PHONE)
    fun phoneFilesDark() {
        story(ThemeMode.Dark)
        tab("Dosyalar")
        capture("app-phone-files-dark")
    }

    /** A spare file that is not UTF-8 text: it is no project file, so the files view lists it apart, saying why. */
    @Test @Config(qualifiers = PHONE)
    fun phoneFilesUnreadableLight() {
        val ref = UiWorld.project(NAME, MAIN, FILES)
        val folder = UiWorld.app.storage.internalProjects.folderOf(ref)
        File(folder, "eski.ink").writeBytes(byteArrayOf(0x45, 0x73, 0x6B, 0x69, 0xC3.toByte(), 0x28, 0x0A))
        val holder = UiWorld.holder(ref)
        launch(holder, ThemeMode.Light)
        compose.awaitStory(compose.awaitSession(holder))
        tab("Dosyalar")
        compose.waitFor { compose.onAllNodesWithText("Okunamayan dosyalar").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("eski.ink").assertExists()
        capture("app-phone-files-unreadable-light")
    }

    /** Each project is open in one window at a time: a second window that asks for it is told so, and offers the others. */
    @Test @Config(qualifiers = PHONE)
    fun phoneProjectOpenElsewhereDark() {
        val ref = UiWorld.project(NAME, MAIN, FILES)
        val other = UiWorld.holder(ref)
        compose.waitFor { other.session != null }
        val holder = UiWorld.holder(ref)
        launch(holder, ThemeMode.Dark)
        compose.waitFor { holder.project.value is com.inkyandroid.session.WindowProject.Failed }
        compose.onNodeWithText("Bu proje başka bir pencerede açık").assertExists()
        capture("app-phone-open-elsewhere-dark")
        compose.clickText("Proje seç")
        compose.waitFor { compose.onAllNodesWithText("Projeler").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test @Config(qualifiers = PHONE)
    fun phoneOutlineLight() {
        val holder = story(ThemeMode.Light)
        compose.runOnUiThread { holder.session!!.show("istasyon.ink") }
        tab("Dosyalar")
        compose.onNode(hasText("Anahat") and isSelectable()).performClick()
        capture("app-phone-outline-light")
    }

    @Test @Config(qualifiers = PHONE)
    fun phoneIssuesLight() {
        story(ThemeMode.Light, broken = true)
        compose.runOnUiThread { commands.perform(Commands.SHOW_ISSUES) }
        capture("app-phone-issues-light")
    }

    @Test @Config(qualifiers = PHONE)
    fun phoneNoProjectDark() {
        launch(UiWorld.holder(), ThemeMode.Dark)
        capture("app-phone-no-project-dark")
    }

    @Test @Config(qualifiers = PHONE, fontScale = 2.0f)
    fun phonePlayFont200Light() {
        story(ThemeMode.Light)
        tab("Oyna")
        capture("app-phone-play-font200-light")
    }

    @Test @Config(qualifiers = PHONE_EN)
    fun phoneWriteEnglishLight() {
        story(ThemeMode.Light)
        capture("app-phone-write-en-light")
    }

    /** The strip of ink's symbols as it sits right above the phone's keyboard (the test machine has no keyboard to open). */
    @Test @Config(qualifiers = PHONE)
    fun phoneInkKeysLight() {
        val prefs = testPrefs(ThemeMode.Light, DensityPreference.Touch)
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            desktopkit.DesktopKitRoot(prefs) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    com.inkyandroid.ui.workspace.InkKeysStrip(
                        desktopkit.editor.CodeEditorState("== start =="),
                        desktopkit.editor.CodeIndent.spaces(4),
                        androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                    )
                }
            }
        }
        capture("app-phone-ink-keys-light")
    }

    /** The find bar under the phone's top bar, with what was found counted. */
    @Test @Config(qualifiers = PHONE)
    fun phoneFindLight() {
        story(ThemeMode.Light)
        compose.runOnUiThread { commands.perform(Commands.FIND) }
        compose.waitForIdle()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("tren")
        compose.waitFor { compose.onAllNodesWithText(" / ", substring = true).fetchSemanticsNodes().isNotEmpty() }
        capture("app-phone-find-light")
    }

    /** Suggestions in a divert: the project's knots and stitches, each with what it is. */
    @Test @Config(qualifiers = PHONE)
    fun phoneSuggestionsDark() {
        val holder = story(ThemeMode.Dark)
        val session = holder.session!!
        val editor = session.activeDocument.editorState!!
        compose.waitFor { session.project.state.value.files.all { it.symbols != null } }
        compose.runOnUiThread {
            val at = editor.text.indexOf("* [Bekleme salonuna gir]")
            editor.select(at)
            editor.insert("-> s\n")
            editor.select(at + "-> s".length)
            commands.perform(Commands.COMPLETE)
        }
        compose.waitFor { compose.onAllNodesWithText("Yönlendirme hedefi").fetchSemanticsNodes().isNotEmpty() }
        capture("app-phone-suggestions-dark")
    }

    /** A phone on its side: the rail, the editor and the player side by side, the editor's header folded into the rail's bar. */
    @Test @Config(qualifiers = PHONE_LANDSCAPE)
    fun phoneLandscapeDark() {
        story(ThemeMode.Dark)
        capture("app-phone-landscape-dark")
    }

    // ------------------------------------------------------------------ tablet

    @Test @Config(qualifiers = TABLET)
    fun tabletWorkspaceLight() {
        intercept(ThemeMode.Light, DensityPreference.Touch)
        capture("app-tablet-workspace-light")
    }

    @Test @Config(qualifiers = TABLET)
    fun tabletIssuesDark() {
        story(ThemeMode.Dark, broken = true)
        compose.runOnUiThread { UiWorld.app.settings.updateInspectorTab(InspectorTab.Issues) }
        capture("app-tablet-issues-dark")
    }

    @Test @Config(qualifiers = TABLET_LANDSCAPE)
    fun tabletLandscapeDark() {
        story(ThemeMode.Dark)
        capture("app-tablet-landscape-dark")
    }

    // ------------------------------------------------------------------ DeX

    @Test @Config(qualifiers = DEX)
    fun dexWorkspaceDark() {
        intercept(ThemeMode.Dark, DensityPreference.Pointer)
        capture("app-dex-workspace-dark")
    }

    @Test @Config(qualifiers = DEX)
    fun dexOutlineLight() {
        val holder = story(ThemeMode.Light, DensityPreference.Pointer)
        compose.runOnUiThread {
            holder.session!!.show("istasyon.ink")
            UiWorld.app.settings.updateInspectorTab(InspectorTab.Outline)
        }
        capture("app-dex-outline-light")
    }

    @Test @Config(qualifiers = DEX)
    fun dexWatchesLight() {
        val holder = story(ThemeMode.Light, DensityPreference.Pointer)
        compose.runOnUiThread {
            holder.session!!.setWatches(listOf("Saat {saat}", "bilet {bilet: var|yok}"))
            UiWorld.app.settings.updateInspectorTab(InspectorTab.Watches)
        }
        compose.awaitStory(holder.session!!)
        capture("app-dex-watches-light")
    }

    @Test @Config(qualifiers = DEX)
    fun dexNewIncludeDark() {
        story(ThemeMode.Dark, DensityPreference.Pointer)
        compose.runOnUiThread { ui.dialog = WindowDialog.NewInclude() }
        capture("app-dex-new-include-dark")
    }

    @Test @Config(qualifiers = DEX)
    fun dexGotoAnythingLight() {
        story(ThemeMode.Light, DensityPreference.Pointer)
        compose.runOnUiThread { commands.perform(Commands.GOTO_ANYTHING) }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("salon")
        capture("app-dex-goto-light")
    }

    @Test @Config(qualifiers = DEX)
    fun dexFindReplaceDark() {
        intercept(ThemeMode.Dark, DensityPreference.Pointer)
        compose.runOnUiThread { commands.perform(Commands.REPLACE) }
        compose.waitForIdle()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Harris")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("Hooper")
        compose.waitFor { compose.onAllNodesWithText(" / ", substring = true).fetchSemanticsNodes().isNotEmpty() }
        capture("app-dex-find-replace-dark")
    }

    /** A conflict answered with "Load from storage", then File › Kept versions: the writer's text is there to bring back. */
    @Test @Config(qualifiers = DEX)
    fun dexKeptVersionsLight() {
        val ref = UiWorld.project(NAME, MAIN, FILES)
        val holder = UiWorld.holder(ref)
        launch(holder, ThemeMode.Light, DensityPreference.Pointer)
        compose.runOnUiThread { UiWorld.app.settings.updateAutosave(false) }
        val session = compose.awaitSession(holder)
        compose.awaitStory(session)
        val editor = session.activeDocument.editorState!!
        compose.runOnUiThread { editor.replace(editor.length, editor.length, "Peronda kimse yoktu.\n") }
        compose.waitFor { session.project.state.value.hasUnsavedChanges }
        kotlinx.coroutines.runBlocking { UiWorld.app.storage.internalProjects.store(ref).write(ref.mainPath, MAIN.replace("son tren gecikmişti", "son tren hiç gelmedi")) }
        compose.runOnUiThread { commands.perform(Commands.RELOAD) }
        compose.waitFor { session.project.state.value.conflicts.isNotEmpty() }
        compose.clickText("Depolamadakini yükle")
        compose.waitFor { session.kept.value.isNotEmpty() }
        compose.runOnUiThread { commands.perform(Commands.KEPT_VERSIONS) }
        compose.waitForIdle()
        capture("app-dex-kept-versions-light")
    }

    /** The Ink menu with a category open beside it (upstream's submenus). */
    @Test @Config(qualifiers = DEX)
    fun dexInkSubmenuDark() {
        story(ThemeMode.Dark, DensityPreference.Pointer)
        compose.onNodeWithText("Ink").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Seçenekler").performClick()
        compose.waitForIdle()
        capture("app-dex-ink-submenu-dark")
    }

    /** The phone's "⋯" sheet: the snippets as a page of the sheet, their categories as further pages. */
    @Test @Config(qualifiers = PHONE)
    fun phoneSnippetPagesLight() {
        story(ThemeMode.Light)
        compose.clickText("Daha fazla")
        compose.waitForIdle()
        // the Ink group is the sheet's last: scrolled to, its line opens the snippets' page
        compose.onNodeWithText("Parçacık ekle").performScrollTo().performClick()
        compose.waitFor { compose.onAllNodesWithText("Temel yapı").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        capture("app-phone-snippet-pages-light")
    }

    private companion object {
        private const val PHONE = "tr-w411dp-h891dp-xhdpi"
        private const val PHONE_EN = "en-w411dp-h891dp-xhdpi"
        private const val PHONE_LANDSCAPE = "tr-w891dp-h411dp-xhdpi"
        private const val TABLET = "tr-w924dp-h1480dp-mdpi"
        private const val TABLET_LANDSCAPE = "tr-w1480dp-h924dp-mdpi"
        private const val DEX = "tr-w1920dp-h1080dp-mdpi"

        val directory = File(System.getProperty("inky.screenshotDir") ?: "build/outputs/screenshots")

        const val NAME = "Gece treni"

        val MAIN = """
            INCLUDE istasyon.ink
            INCLUDE bolumler/vagon.ink
            # title: Gece treni
            // Kısa bir deneme: son tren gecikmiş.
            VAR bilet = false
            VAR saat = 23

            Peron boştu. Saat {saat}'ü geçiyordu ve son tren gecikmişti. # yer: peron

            * [Bekleme salonuna gir] -> salon
            * [Raylara doğru yürü] -> raylar
            * [Cebindeki bileti yokla]
                Cebini yokladın. {bilet: Bilet oradaydı.|Bilet yoktu.}
                -> salon

        """.trimIndent()

        private val STATION = """
            == salon ==
            Salonda yaşlı bir adam gazete okuyordu. # kişi: adam
            * "Tren ne zaman gelir?"
                Adam başını kaldırmadan omuz silkti.
                -> vagon.ilk
            * [Sessizce otur]
                -> bekle
            = bekle
            Saatler geçti.
            ~ saat = saat + 1
            -> vagon.ilk

            == raylar ==
            Raylar soğuktu ve uzakta bir ışık yaklaşıyordu.
            -> vagon.ilk

        """.trimIndent()

        private val CARRIAGE = """
            == vagon ==
            = ilk
            Tren sonunda geldi. Kapılar tısladı.
            * [Bin] Vagona bindin.
                -> son
            * [Bekle] Kapılar kapandı ve tren gitti.
                -> END

            == son ==
            Pencereden kasabanın ışıkları kayıp gitti.
            -> END

        """.trimIndent()

        val FILES = mapOf(
            "istasyon.ink" to STATION,
            "bolumler/vagon.ink" to CARRIAGE,
            // nothing includes it: a spare file
            "notlar.ink" to "TODO: Adamın kim olduğunu bul.\n",
        )

        /** The same story with an error of meaning, a loose end and notes, so the compiler reports all three kinds. */
        val BROKEN_FILES = FILES + mapOf(
            "istasyon.ink" to STATION.replace("-> vagon.ilk\n\n== raylar", "-> vagon.kayip\n\n== raylar").replace("Salonda yaşlı", "TODO: Adamın adını sonra yaz.\nSalonda yaşlı"),
            "bolumler/vagon.ink" to CARRIAGE + "== yan_yol ==\nBu yol bir yere çıkmıyor.\n",
        )
    }
}
