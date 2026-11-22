package com.inkyandroid.storage

import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** One entry of File › Open Recent. */
data class RecentProject(
    val ref: ProjectRef,
    /** When the project was last opened (or its main file last saved), milliseconds since the epoch. */
    val lastOpened: Long,
)

/**
 * Upstream's recent files (File › Open Recent, with Clear), kept in a small JSON file in the
 * app's own storage. Differences from upstream, deliberate: the list is ordered by last use (an
 * entry opened again moves to the front, where upstream leaves it in place), it holds projects
 * (a folder and its main file) instead of paths, and entries whose storage is gone are removed by
 * [removeUnavailable].
 *
 * The list holds at most [LIMIT] entries, upstream's five. Whenever an entry leaves it,
 * [onRemoved] runs, so that permissions nothing refers to any more are given back.
 */
class RecentProjects internal constructor(
    private val file: File,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    private val isAvailable: suspend (ProjectRef) -> StorageProblem?,
    private val onRemoved: () -> Unit,
) {
    private val lock = Mutex()
    private val state = MutableStateFlow(load())

    /** The entries, most recently opened first. */
    val entries: StateFlow<List<RecentProject>> = state.asStateFlow()

    /**
     * Puts [ref] at the front with the current time (upstream: on open and on every save of the
     * main file). An entry for the same project ([ProjectRef.key]) is replaced, so a changed display
     * name is taken over.
     */
    suspend fun add(ref: ProjectRef) = update { list ->
        listOf(RecentProject(ref, clock())) + list.filter { it.ref.key != ref.key }
    }

    /** Removes the entry of [ref], if any. */
    suspend fun remove(ref: ProjectRef) = update { list -> list.filter { it.ref.key != ref.key } }

    /** Upstream's Clear. */
    suspend fun clear() = update { emptyList() }

    /**
     * Removes the entries whose storage is definitely gone: the permission was revoked, or the
     * folder or main file no longer exists. Entries that fail for another reason (a provider that
     * does not answer right now, an SD card that is out) stay. Returns what was removed.
     */
    suspend fun removeUnavailable(): List<RecentProject> {
        val gone = state.value.filter {
            when (isAvailable(it.ref)) {
                StorageProblem.PermissionLost, StorageProblem.NotFound -> true
                else -> false
            }
        }
        if (gone.isNotEmpty()) {
            val keys = gone.map { it.ref.key }.toSet()
            update { list -> list.filter { it.ref.key !in keys } }
        }
        return gone
    }

    private suspend fun update(change: (List<RecentProject>) -> List<RecentProject>) {
        val removedSome = lock.withLock {
            val before = state.value
            val after = change(before).take(LIMIT)
            if (after == before) return@withLock false
            state.value = after
            withContext(io) { save(after) }
            before.any { old -> after.none { it.ref.key == old.ref.key } }
        }
        if (removedSome) onRemoved()
    }

    private fun load(): List<RecentProject> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(String(AtomicFile(file).readFully(), Charsets.UTF_8))
            (0 until array.length()).mapNotNull { i ->
                val entry = array.optJSONObject(i) ?: return@mapNotNull null
                val ref = ProjectRef.decode(entry.optString(FIELD_REF)) ?: return@mapNotNull null
                RecentProject(ref, entry.optLong(FIELD_OPENED))
            }.distinctBy { it.ref.key }.take(LIMIT)
        } catch (e: IOException) {
            Log.w(LOG_TAG, "recent projects could not be read: ${e.message}")
            emptyList()
        } catch (e: JSONException) {
            Log.w(LOG_TAG, "recent projects are damaged and start empty: ${e.message}")
            emptyList()
        }
    }

    private fun save(list: List<RecentProject>) {
        val array = JSONArray()
        for (entry in list) array.put(JSONObject().put(FIELD_REF, entry.ref.encode()).put(FIELD_OPENED, entry.lastOpened))
        val atomic = AtomicFile(file)
        val out = try {
            file.parentFile?.mkdirs()
            atomic.startWrite()
        } catch (e: IOException) {
            Log.w(LOG_TAG, "recent projects could not be saved: ${e.message}")
            return
        }
        try {
            out.write(array.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: IOException) {
            atomic.failWrite(out)
            Log.w(LOG_TAG, "recent projects could not be saved: ${e.message}")
        }
    }

    companion object {
        /** Upstream keeps five recent files. */
        const val LIMIT = 5

        private const val FIELD_REF = "ref"
        private const val FIELD_OPENED = "opened"
    }
}
