package com.inkyandroid.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The tabs of the inspector beside the editor in a wide window. */
enum class InspectorTab { Player, Issues, Outline, Watches }

/**
 * The app's own settings, stored in shared preferences and observable from Compose: a composable
 * that reads a property is recomposed when it changes. One instance serves every window
 * ([AppSettings.of]), so a switch flipped in one window changes the others at once. (Theme, accent,
 * density and the editor's zoom are the kit's and live in its own preferences.)
 */
@Stable
class AppSettings(private val prefs: SharedPreferences) {

    // ------------------------------------------------------------------ files

    /**
     * Files with unsaved changes are saved by themselves two seconds after the last edit and when
     * the app goes to the background. Off is upstream's way: nothing is written until "Save", and
     * closing a project with unsaved changes asks. (Drafts are kept either way.)
     */
    var autosave by mutableStateOf(prefs.getBoolean(KEY_AUTOSAVE, true))
        private set

    fun updateAutosave(value: Boolean) {
        autosave = value
        prefs.edit().putBoolean(KEY_AUTOSAVE, value).apply()
    }

    // ------------------------------------------------------------------ the editor

    /** Long lines wrap at the editor's edge (upstream always wraps); off scrolls sideways. */
    var softWrap by mutableStateOf(prefs.getBoolean(KEY_SOFT_WRAP, true))
        private set

    /** Line numbers in the editor's gutter. */
    var lineNumbers by mutableStateOf(prefs.getBoolean(KEY_LINE_NUMBERS, true))
        private set

    /** Upstream's View > Auto-complete: knot names, variables and words are offered while typing. */
    var autoComplete by mutableStateOf(prefs.getBoolean(KEY_AUTO_COMPLETE, true))
        private set

    /** The on-screen keyboard's own suggestions and autocorrect in the editor: ink is mostly prose. */
    var keyboardSuggestions by mutableStateOf(prefs.getBoolean(KEY_KEYBOARD_SUGGESTIONS, true))
        private set

    fun updateSoftWrap(value: Boolean) {
        softWrap = value
        prefs.edit().putBoolean(KEY_SOFT_WRAP, value).apply()
    }

    fun updateLineNumbers(value: Boolean) {
        lineNumbers = value
        prefs.edit().putBoolean(KEY_LINE_NUMBERS, value).apply()
    }

    fun updateAutoComplete(value: Boolean) {
        autoComplete = value
        prefs.edit().putBoolean(KEY_AUTO_COMPLETE, value).apply()
    }

    fun updateKeyboardSuggestions(value: Boolean) {
        keyboardSuggestions = value
        prefs.edit().putBoolean(KEY_KEYBOARD_SUGGESTIONS, value).apply()
    }

    // ------------------------------------------------------------------ the player

    /** Upstream's View > Play view animation: new story text fades in. */
    var playAnimation by mutableStateOf(prefs.getBoolean(KEY_PLAY_ANIMATION, true))
        private set

    /** Upstream's Story > Tags visible: the player shows the tags of lines and choices. */
    var tagsVisible by mutableStateOf(prefs.getBoolean(KEY_TAGS_VISIBLE, true))
        private set

    fun updatePlayAnimation(value: Boolean) {
        playAnimation = value
        prefs.edit().putBoolean(KEY_PLAY_ANIMATION, value).apply()
    }

    fun updateTagsVisible(value: Boolean) {
        tagsVisible = value
        prefs.edit().putBoolean(KEY_TAGS_VISIBLE, value).apply()
    }

    // ------------------------------------------------------------------ the panes of a wide window

    /** The inspector's open tab. */
    var inspectorTab by mutableStateOf(
        InspectorTab.entries.firstOrNull { it.name == prefs.getString(KEY_INSPECTOR_TAB, null) } ?: InspectorTab.Player,
    )
        private set

    /** The inspector is folded away: the editor alone, upstream's "focus" idea. */
    var inspectorCollapsed by mutableStateOf(prefs.getBoolean(KEY_INSPECTOR_COLLAPSED, false))
        private set

    /**
     * The inspector's share of the room it has with the editor, 0 to 1: what the divider was dragged
     * to. A share and not a width, so a window of another size divides its room the same way.
     * Upstream starts with the two panes equal.
     */
    var inspectorShare by mutableFloatStateOf(
        prefs.getFloat(KEY_INSPECTOR_SHARE, DEFAULT_INSPECTOR_SHARE).coerceIn(MIN_INSPECTOR_SHARE, MAX_INSPECTOR_SHARE),
    )
        private set

    fun updateInspectorTab(value: InspectorTab) {
        inspectorTab = value
        prefs.edit().putString(KEY_INSPECTOR_TAB, value.name).apply()
    }

    fun updateInspectorCollapsed(value: Boolean) {
        inspectorCollapsed = value
        prefs.edit().putBoolean(KEY_INSPECTOR_COLLAPSED, value).apply()
    }

    fun updateInspectorShare(value: Float) {
        inspectorShare = value.coerceIn(MIN_INSPECTOR_SHARE, MAX_INSPECTOR_SHARE)
        prefs.edit().putFloat(KEY_INSPECTOR_SHARE, inspectorShare).apply()
    }

    // ------------------------------------------------------------------ where the user was

    /**
     * The project that was open last, the file shown and the place in each file, so the app comes
     * back where it was left; null before any project was opened and after the last one was closed.
     */
    var lastSession by mutableStateOf(LastSession.decode(prefs.getString(KEY_LAST_SESSION, null)))
        private set

    fun updateLastSession(value: LastSession?) {
        if (value == lastSession) return
        lastSession = value
        prefs.edit().apply { if (value == null) remove(KEY_LAST_SESSION) else putString(KEY_LAST_SESSION, value.encode()) }.apply()
    }

    companion object {
        /** The file the app's settings are kept in. */
        const val FILE = "inky"

        const val DEFAULT_INSPECTOR_SHARE = 0.5f

        /** No stored share leaves the editor or the inspector without room. */
        const val MIN_INSPECTOR_SHARE = 0.2f
        const val MAX_INSPECTOR_SHARE = 0.8f

        private const val KEY_AUTOSAVE = "autosave"
        private const val KEY_SOFT_WRAP = "editor_soft_wrap"
        private const val KEY_LINE_NUMBERS = "editor_line_numbers"
        private const val KEY_AUTO_COMPLETE = "editor_auto_complete"
        private const val KEY_KEYBOARD_SUGGESTIONS = "editor_keyboard_suggestions"
        private const val KEY_PLAY_ANIMATION = "player_animation"
        private const val KEY_TAGS_VISIBLE = "player_tags_visible"
        private const val KEY_INSPECTOR_TAB = "inspector_tab"
        private const val KEY_INSPECTOR_COLLAPSED = "inspector_collapsed"
        private const val KEY_INSPECTOR_SHARE = "inspector_share"
        private const val KEY_LAST_SESSION = "last_session"

        @Volatile
        private var shared: AppSettings? = null

        /** The one instance of the process. */
        fun of(context: Context): AppSettings = shared ?: synchronized(this) {
            shared ?: AppSettings(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)).also { shared = it }
        }
    }
}
