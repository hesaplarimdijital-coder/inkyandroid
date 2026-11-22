package com.inkyandroid.ui

import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import com.inkyandroid.R
import desktopkit.input.CommonShortcuts
import desktopkit.input.KeyChord
import desktopkit.input.Shortcut
import desktopkit.input.ShortcutGroup
import desktopkit.input.ShortcutRegistry
import desktopkit.input.ShortcutScope

/** The app's commands by name: what a shortcut, a menu entry, the "⋯" sheet or the palette asks for. */
object Commands {
    const val NEW_PROJECT = "project.new"
    const val NEW_INCLUDE = "file.new_include"
    const val OPEN_FOLDER = "project.open_folder"
    const val OPEN_FILE = "project.open_file"
    const val SAVE = "project.save"
    const val RELOAD = "project.reload"
    const val CLOSE_PROJECT = "project.close"
    const val EXPORT_JSON = "export.json"
    const val EXPORT_WEB = "export.web"
    const val EXPORT_JS = "export.js"

    const val UNDO = "edit.undo"
    const val REDO = "edit.redo"
    const val CUT = "edit.cut"
    const val COPY = "edit.copy"
    const val PASTE = "edit.paste"
    const val SELECT_ALL = "edit.select_all"
    const val FIND = "edit.find"
    const val REPLACE = "edit.replace"
    const val TOGGLE_COMMENT = "edit.toggle_comment"

    const val ZOOM_IN = "view.zoom_in"
    const val ZOOM_OUT = "view.zoom_out"
    const val ZOOM_RESET = "view.zoom_reset"
    const val TOGGLE_INSPECTOR = "view.toggle_inspector"
    const val SHOW_PLAYER = "view.player"
    const val SHOW_ISSUES = "view.issues"
    const val SHOW_OUTLINE = "view.outline"
    const val SHOW_WATCHES = "view.watches"

    const val GOTO_ANYTHING = "story.goto_anything"
    const val GOTO_LINE = "story.goto_line"
    const val GOTO_DEFINITION = "story.goto_definition"
    const val NEXT_ISSUE = "story.next_issue"
    const val BACK = "story.back"
    const val FORWARD = "story.forward"
    const val ADD_WATCH = "story.add_watch"
    const val STATISTICS = "story.statistics"
    const val STEP_BACK = "story.step_back"
    const val RESTART = "story.restart"

    const val DOCUMENTATION = "help.documentation"
}

val FileGroup = ShortcutGroup("file", R.string.menu_file)
val EditGroup = ShortcutGroup("edit", R.string.menu_edit)
val StoryGroup = ShortcutGroup("story", R.string.menu_story)

/** The Ink menu: the snippets. It has no shortcuts of its own; a project's custom snippets may bring some. */
val InkGroup = ShortcutGroup("ink", R.string.menu_ink)

/**
 * The keys the code editor answers itself while it has the focus (undo, the clipboard, find): they
 * are in the table so that the menus and the lists of shortcuts show them, and never routed from
 * the activity, where they would be taken from every other text field of the app.
 */
val EditorScope = ShortcutScope("editor")

/**
 * Every shortcut of the app in one table: the menu bar, the "⋯" sheet, the command palette, the
 * system's list of shortcuts (Meta+/) and the list in Settings are all drawn from it.
 *
 * The keys are desktop Inky's wherever they are free (docs/KISAYOLLAR.md lists every difference):
 *
 * - Ctrl+N, Ctrl+Alt+N, Ctrl+S, Ctrl+Shift+S, Ctrl+Alt+S, Ctrl+W, Ctrl+P, Ctrl+., Ctrl+Shift+C, F1
 *   and the zoom keys are upstream's.
 * - Ctrl+O opens a folder, which is what a project is here; Ctrl+Shift+O opens a single ink file.
 * - Ctrl+/ is the kit's list of shortcuts, so "toggle comment" is Alt+/.
 * - Ctrl+K is the kit's search: it opens the same palette as upstream's Ctrl+P, and Ctrl+G
 *   (upstream's "go to line") opens it too, since a number typed there is a line.
 * - Upstream has no keys for back and forward, step back, restart or "go to definition"; here
 *   they are Alt+← and Alt+→, Ctrl+R and Ctrl+Shift+R (upstream's Ctrl+R reloads the window, a
 *   developer's tool that has no meaning here), and F12.
 * - The kit's "New window" is Ctrl+Shift+N, as in the owner's other apps.
 */
val shortcuts = ShortcutRegistry(
    groups = listOf(FileGroup, EditGroup, CommonShortcuts.ViewGroup, StoryGroup, InkGroup, CommonShortcuts.HelpGroup),
    shortcuts = listOf(
        Shortcut(Commands.NEW_PROJECT, FileGroup, R.string.action_new_project, KeyChord(Key.N, ctrl = true)),
        Shortcut(Commands.NEW_INCLUDE, FileGroup, R.string.action_new_include, KeyChord(Key.N, ctrl = true, alt = true)),
        Shortcut(Commands.OPEN_FOLDER, FileGroup, R.string.action_open_folder, KeyChord(Key.O, ctrl = true)),
        Shortcut(Commands.OPEN_FILE, FileGroup, R.string.action_open_file, KeyChord(Key.O, ctrl = true, shift = true)),
        Shortcut(Commands.SAVE, FileGroup, R.string.action_save, KeyChord(Key.S, ctrl = true)),
        Shortcut(Commands.RELOAD, FileGroup, R.string.action_reload, KeyChord(Key.F5)),
        Shortcut(Commands.EXPORT_JSON, FileGroup, R.string.action_export_json, KeyChord(Key.S, ctrl = true, shift = true)),
        Shortcut(Commands.EXPORT_JS, FileGroup, R.string.action_export_js, KeyChord(Key.S, ctrl = true, alt = true)),
        Shortcut(CommonShortcuts.NEW_WINDOW, FileGroup, desktopkit.R.string.dk_new_window, KeyChord(Key.N, ctrl = true, shift = true)),
        Shortcut(Commands.CLOSE_PROJECT, FileGroup, R.string.action_close_project, KeyChord(Key.W, ctrl = true)),

        Shortcut(Commands.UNDO, EditGroup, R.string.action_undo, KeyChord(Key.Z, ctrl = true), EditorScope),
        Shortcut(Commands.REDO, EditGroup, R.string.action_redo, KeyChord(Key.Z, ctrl = true, shift = true), EditorScope),
        Shortcut(Commands.CUT, EditGroup, R.string.action_cut, KeyChord(Key.X, ctrl = true), EditorScope),
        Shortcut(Commands.COPY, EditGroup, R.string.action_copy, KeyChord(Key.C, ctrl = true), EditorScope),
        Shortcut(Commands.PASTE, EditGroup, R.string.action_paste, KeyChord(Key.V, ctrl = true), EditorScope),
        Shortcut(Commands.SELECT_ALL, EditGroup, R.string.action_select_all, KeyChord(Key.A, ctrl = true), EditorScope),
        Shortcut(Commands.FIND, EditGroup, R.string.action_find, KeyChord(Key.F, ctrl = true)),
        Shortcut(Commands.REPLACE, EditGroup, R.string.action_replace, KeyChord(Key.H, ctrl = true)),
        Shortcut(Commands.TOGGLE_COMMENT, EditGroup, R.string.action_toggle_comment, KeyChord(Key.Slash, alt = true)),

        Shortcut(Commands.ZOOM_IN, CommonShortcuts.ViewGroup, R.string.action_zoom_in, KeyChord(Key.Equals, ctrl = true)),
        Shortcut(Commands.ZOOM_OUT, CommonShortcuts.ViewGroup, R.string.action_zoom_out, KeyChord(Key.Minus, ctrl = true)),
        Shortcut(Commands.ZOOM_RESET, CommonShortcuts.ViewGroup, R.string.action_zoom_reset, KeyChord(Key.Zero, ctrl = true)),
        Shortcut(Commands.TOGGLE_INSPECTOR, CommonShortcuts.ViewGroup, R.string.action_toggle_inspector, KeyChord(Key.B, ctrl = true, alt = true)),
        Shortcut(Commands.SHOW_PLAYER, CommonShortcuts.ViewGroup, R.string.action_show_player, KeyChord(Key.One, alt = true)),
        Shortcut(Commands.SHOW_ISSUES, CommonShortcuts.ViewGroup, R.string.action_show_issues, KeyChord(Key.Two, alt = true)),
        Shortcut(Commands.SHOW_OUTLINE, CommonShortcuts.ViewGroup, R.string.action_show_outline, KeyChord(Key.Three, alt = true)),
        Shortcut(Commands.SHOW_WATCHES, CommonShortcuts.ViewGroup, R.string.action_show_watches, KeyChord(Key.Four, alt = true)),

        Shortcut(Commands.GOTO_ANYTHING, StoryGroup, R.string.action_goto_anything, KeyChord(Key.P, ctrl = true)),
        Shortcut(Commands.GOTO_LINE, StoryGroup, R.string.action_goto_line, KeyChord(Key.G, ctrl = true)),
        Shortcut(Commands.GOTO_DEFINITION, StoryGroup, R.string.action_goto_definition, KeyChord(Key.F12)),
        Shortcut(Commands.BACK, StoryGroup, R.string.action_back, KeyChord(Key.DirectionLeft, alt = true)),
        Shortcut(Commands.FORWARD, StoryGroup, R.string.action_forward, KeyChord(Key.DirectionRight, alt = true)),
        Shortcut(Commands.NEXT_ISSUE, StoryGroup, R.string.action_next_issue, KeyChord(Key.Period, ctrl = true)),
        Shortcut(Commands.STEP_BACK, StoryGroup, R.string.action_step_back, KeyChord(Key.R, ctrl = true)),
        Shortcut(Commands.RESTART, StoryGroup, R.string.action_restart, KeyChord(Key.R, ctrl = true, shift = true)),
        Shortcut(Commands.STATISTICS, StoryGroup, R.string.action_statistics, KeyChord(Key.C, ctrl = true, shift = true)),

        Shortcut(Commands.DOCUMENTATION, CommonShortcuts.HelpGroup, R.string.action_documentation, KeyChord(Key.F1)),
    ) + CommonShortcuts.all,
)

/** One window's commands, for whoever routes its keys; the composition says what performs them. */
class CommandHost {
    /** Runs the command [id]; false when there is nothing for it to do right now. */
    var perform: (String) -> Boolean = { false }
}

/**
 * What a key press does. The activity asks here first (dispatchKeyEvent), before the focused view
 * sees the key:
 *
 * - while the command palette is open, nothing: its field and its list take the keys;
 * - a chord of the table (Ctrl+S, Ctrl+P, Alt+←, F1, …) runs its command wherever the focus is;
 * - the editor's own keys ([EditorScope]: undo, the clipboard) are never taken here.
 *
 * A command with nothing to do (Ctrl+S with no project open) leaves the key to the focused view.
 */
class KeyRouter(
    private val registry: ShortcutRegistry,
    private val paletteOpen: () -> Boolean,
    private val perform: (String) -> Boolean,
) {
    /** True when the key was taken. */
    fun route(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || paletteOpen()) return false
        val shortcut = registry.matchNative(event) ?: return false
        // a key held down saves once and steps once
        if (event.repeatCount > 0 && shortcut.id !in REPEATING) return true
        return perform(shortcut.id)
    }

    private companion object {
        /** The commands a held key may repeat: walking through the issues and the history, and the zoom. */
        val REPEATING = setOf(Commands.NEXT_ISSUE, Commands.BACK, Commands.FORWARD, Commands.ZOOM_IN, Commands.ZOOM_OUT)
    }
}
