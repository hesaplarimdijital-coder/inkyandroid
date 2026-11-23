package com.inkyandroid.ui.extras

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.inkyandroid.R
import com.inkyandroid.ui.NavFiles
import com.inkyandroid.ui.Routes
import com.inkyandroid.ui.buildShellSpec
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import desktopkit.DesktopKitRoot
import desktopkit.components.ToastState
import desktopkit.settings.DeskPreferences
import desktopkit.shell.DesktopShell
import desktopkit.shell.ShellTransitions
import java.io.File
import java.io.FileOutputStream

/** The shell's routes the extras' screens are shown at in the tests: the app's own. */
object TestRoutes {
    const val WRITE = Routes.WRITE
    const val DOCS = Routes.DOCS
    const val SETTINGS = Routes.SETTINGS
}

/**
 * One of the extras' screens inside the kit's root and the app's own shell (its navigation as
 * [buildShellSpec] makes it, with a one-file project open), at [route].
 */
@Composable
fun ExtrasHost(prefs: DeskPreferences, route: String, toasts: ToastState? = null, screen: @Composable () -> Unit) {
    val resources = LocalContext.current.resources
    val spec = remember(resources, route) {
        val main = resources.getString(R.string.sample_project_name) + ".ink"
        buildShellSpec(
            resources = resources,
            files = NavFiles(resources.getString(R.string.sample_project_name), main, emptyList(), emptyList()),
            playOnRail = false,
            docs = true,
            settings = true,
            startRoute = route,
        )
    }
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
