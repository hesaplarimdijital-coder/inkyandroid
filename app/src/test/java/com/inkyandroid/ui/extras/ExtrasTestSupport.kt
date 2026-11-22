package com.inkyandroid.ui.extras

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import desktopkit.DesktopKitRoot
import desktopkit.components.DeskIcons
import desktopkit.components.ToastState
import desktopkit.settings.DeskPreferences
import desktopkit.shell.DesktopShell
import desktopkit.shell.NavItem
import desktopkit.shell.NavSection
import desktopkit.shell.ShellSpec
import desktopkit.shell.ShellTransitions
import java.io.File
import java.io.FileOutputStream

/** The shell's routes the extras' screens are shown at in the tests, as the app has them. */
object TestRoutes {
    const val WRITE = "write"
    const val DOCS = "docs"
    const val SETTINGS = "settings"
}

/**
 * One of the extras' screens inside the kit's root and shell, as the app shows it: a stand-in
 * "write" route first (the phone's bottom bar needs company), the screen at [route].
 */
@Composable
fun ExtrasHost(prefs: DeskPreferences, route: String, toasts: ToastState? = null, screen: @Composable () -> Unit) {
    val spec = ShellSpec(
        appTitle = "Inky",
        sections = listOf(
            NavSection(
                null,
                listOf(
                    NavItem(TestRoutes.WRITE, "Yaz", DeskIcons.Lyrics),
                    NavItem(TestRoutes.DOCS, "Belgeler", DeskIcons.Info),
                ),
            ),
        ),
        footer = NavItem(TestRoutes.SETTINGS, "Ayarlar", DeskIcons.Settings),
        startRoute = route,
    )
    DesktopKitRoot(prefs) { widthClass ->
        DesktopShell(
            spec = spec,
            widthClass = widthClass,
            sidebarCollapsed = prefs.sidebarCollapsed,
            onSidebarCollapsedChange = prefs::updateSidebarCollapsed,
            transitions = ShellTransitions.None,
            toasts = toasts,
        ) { shown, _ -> if (shown == route) screen() }
    }
}

/** Device sizes of the owner's devices, as Robolectric qualifiers (Turkish, as he uses them). */
object Devices {
    const val PHONE = "tr-w411dp-h891dp-xhdpi"
    const val PHONE_EN = "en-w411dp-h891dp-xhdpi"
    const val TABLET = "tr-w924dp-h1480dp-mdpi"
    const val TABLET_LANDSCAPE = "tr-w1480dp-h924dp-mdpi"
    const val DEX = "tr-w1920dp-h1080dp-mdpi"
}

private val screenshotDirectory = File(System.getProperty("inky.screenshotDir") ?: "build/outputs/screenshots")

/** Draws the test activity's window into `<screenshots>/<name>.png`, as the app's screenshot tests do. */
fun AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>.capture(name: String) {
    waitForIdle()
    val window = activity.window.decorView
    val bitmap = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
    runOnIdle { window.draw(Canvas(bitmap)) }
    screenshotDirectory.mkdirs()
    FileOutputStream(File(screenshotDirectory, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
