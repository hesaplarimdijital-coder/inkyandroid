package com.inkyandroid.ui.extras.docs

import android.content.Context
import android.content.SharedPreferences

/**
 * What the documentation screen remembers between visits and restarts: the reading position (the
 * first block on screen and how far it is scrolled). The text size is the kit's editor zoom, kept
 * in the kit's preferences.
 */
class DocsMemory(private val prefs: SharedPreferences) {

    /** The block at the top of the screen and its scroll offset in pixels. */
    data class Position(val block: Int, val offset: Int)

    val position: Position get() = Position(prefs.getInt(KEY_BLOCK, 0), prefs.getInt(KEY_OFFSET, 0))

    fun updatePosition(position: Position) {
        prefs.edit().putInt(KEY_BLOCK, position.block).putInt(KEY_OFFSET, position.offset).apply()
    }

    companion object {
        const val FILE = "inky-docs"
        private const val KEY_BLOCK = "block"
        private const val KEY_OFFSET = "offset"

        fun of(context: Context) = DocsMemory(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
