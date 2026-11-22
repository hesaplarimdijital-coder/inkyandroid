package com.inkyandroid.ui.extras.docs

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * What the documentation screen remembers between visits and restarts: the reading position (the
 * first block on screen and how far it is scrolled) and the text size.
 */
class DocsMemory(private val prefs: SharedPreferences) {

    /** The block at the top of the screen and its scroll offset in pixels. */
    data class Position(val block: Int, val offset: Int)

    val position: Position get() = Position(prefs.getInt(KEY_BLOCK, 0), prefs.getInt(KEY_OFFSET, 0))

    fun updatePosition(position: Position) {
        prefs.edit().putInt(KEY_BLOCK, position.block).putInt(KEY_OFFSET, position.offset).apply()
    }

    /** The text size as a multiple of the theme's. */
    val scale: Float get() = prefs.getFloat(KEY_SCALE, 1f)

    fun updateScale(scale: Float) {
        prefs.edit().putFloat(KEY_SCALE, scale).apply()
    }

    companion object {
        const val FILE = "inky-docs"
        private const val KEY_BLOCK = "block"
        private const val KEY_OFFSET = "offset"
        private const val KEY_SCALE = "scale"

        fun of(context: Context) = DocsMemory(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}

/**
 * The manual's text size, stepped like the code editor's (tenths, 0.7 to 2.5 times the theme's
 * size; Ctrl with + and −, Ctrl+0 back to the theme's), and kept in [DocsMemory].
 */
@Stable
class DocsZoom(initial: Float, private val onCommit: (Float) -> Unit = {}) {
    /** Compose state: text drawn with it follows a change at once. */
    var scale: Float by mutableFloatStateOf(snap(initial))
        private set

    fun zoomIn(): Float = set(scale + STEP)

    fun zoomOut(): Float = set(scale - STEP)

    fun reset(): Float = set(1f)

    val canZoomIn: Boolean get() = scale < MAX
    val canZoomOut: Boolean get() = scale > MIN

    private fun set(value: Float): Float {
        val next = snap(value)
        if (next != scale) {
            scale = next
            onCommit(next)
        }
        return scale
    }

    companion object {
        const val MIN = 0.7f
        const val MAX = 2.5f
        private const val STEP = 0.1f

        fun snap(value: Float): Float = (Math.round(value * 10f) / 10f).coerceIn(MIN, MAX)
    }
}
