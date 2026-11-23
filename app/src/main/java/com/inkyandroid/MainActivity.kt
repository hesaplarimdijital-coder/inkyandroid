package com.inkyandroid

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import com.inkyandroid.session.ProjectSessionHolder
import com.inkyandroid.storage.ProjectIntents
import com.inkyandroid.ui.CommandHost
import com.inkyandroid.ui.InkyApp
import com.inkyandroid.ui.KeyRouter
import com.inkyandroid.ui.WindowUi
import com.inkyandroid.ui.shortcuts
import desktopkit.components.CommandPaletteState
import desktopkit.shell.DeskWindows

/**
 * One window of the app. There can be several (DeX, a tablet's split screen): each is an activity
 * of its own with its own project session, opened at the route the other window asked for.
 */
class MainActivity : ComponentActivity() {

    private val app: InkyApplication get() = application as InkyApplication

    /** The window's project: kept when the activity is recreated, ended when the window goes. */
    private val holder: ProjectSessionHolder by lazy {
        ViewModelProvider(this, ProjectSessionHolder.Factory(app.sessionEnvironment))[ProjectSessionHolder::class.java]
    }

    private val window: WindowState by lazy { ViewModelProvider(this)[WindowState::class.java] }

    /** Ctrl+K and Ctrl+P: the command palette. Kept here, so the keys can be left alone while it is open. */
    private val palette = CommandPaletteState()

    /** The composition says what performs the window's commands (the keys, and the tests, ask here). */
    internal val commands = CommandHost()

    private val keys = KeyRouter(
        registry = shortcuts,
        paletteOpen = { palette.isOpen },
        perform = { id -> commands.perform(id) },
        otherKey = { event -> commands.otherKey(event) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // the project of the window before the system recreated it, else the last session's
        holder.restore(savedInstanceState?.getString(KEY_PROJECT))
        if (savedInstanceState == null) {
            // an .ink file handed over by another app (a file manager's "open with") opens as a project
            window.ui.pendingFile = ProjectIntents.fileToOpen(intent)
            window.ui.freshStart = true
        }
        setContent {
            InkyApp(
                holder = holder,
                settings = app.settings,
                storage = app.storage,
                ui = window.ui,
                palette = palette,
                commands = commands,
                startRoute = DeskWindows.startRoute(intent),
            )
        }
    }

    /** Another app handed over an .ink file while this window was there. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ProjectIntents.fileToOpen(intent)?.let { window.ui.pendingFile = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PROJECT, holder.reference)
    }

    /** The window is in front again: files may have changed outside the app meanwhile. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) holder.onWindowFocusGained()
    }

    /** Out of sight: the system may end the process from here on, so the unsaved text goes to the drafts (and with autosave to storage) now. */
    override fun onStop() {
        holder.onBackground()
        super.onStop()
    }

    /** Every key passes here first, whatever has the focus: the shortcut table decides. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = keys.route(event) || super.dispatchKeyEvent(event)

    /** Meta+/ (the system's list of keyboard shortcuts) shows the same table. */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        data.addAll(shortcuts.keyboardShortcutGroups(resources))
    }

    private companion object {
        const val KEY_PROJECT = "inky.project"
    }
}

/** What this window shows beyond its project, kept when something recreates its activity. */
class WindowState(application: Application) : AndroidViewModel(application) {
    val ui = WindowUi()
}
