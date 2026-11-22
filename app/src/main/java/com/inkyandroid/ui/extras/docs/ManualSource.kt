package com.inkyandroid.ui.extras.docs

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The bundled manual, parsed once per process: every window and every visit of the screen after the
 * first gets the same [Manual] at once.
 */
object ManualSource {
    /** Where the manual is among the app's assets (inkle's `WritingWithInk.md`, unchanged). */
    const val ASSET = "docs/WritingWithInk.md"

    private val lock = Mutex()

    @Volatile
    private var loaded: Manual? = null

    /** The manual if it has been parsed already, else null. */
    fun cached(): Manual? = loaded

    /** The manual, read and parsed off the main thread the first time. */
    suspend fun load(context: Context): Manual = loaded ?: lock.withLock {
        loaded ?: withContext(Dispatchers.Default) {
            val text = context.applicationContext.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            ManualParser.parse(text)
        }.also { loaded = it }
    }
}
