package com.inkyandroid.settings

import org.json.JSONException
import org.json.JSONObject

/** Where the cursor and the view were in one file. Rows and columns are zero-based. */
data class FilePlace(val row: Int, val column: Int, val firstVisibleRow: Int)

/**
 * What a window had open, kept so that the app returns to it after it was closed or killed.
 *
 * @property project the project's reference in its string form (`ProjectRef.encode()` of the
 *   storage package; the settings neither read nor change it).
 * @property activeFile the root-relative path of the file that was shown; null means the main file.
 * @property places the cursor and scroll position of every file that had been opened, by path.
 */
data class LastSession(
    val project: String,
    val activeFile: String? = null,
    val places: Map<String, FilePlace> = emptyMap(),
) {
    fun encode(): String {
        val files = JSONObject()
        for ((path, place) in places) {
            files.put(path, JSONObject().put(KEY_ROW, place.row).put(KEY_COLUMN, place.column).put(KEY_TOP, place.firstVisibleRow))
        }
        return JSONObject().put(KEY_PROJECT, project).put(KEY_ACTIVE, activeFile ?: JSONObject.NULL).put(KEY_PLACES, files).toString()
    }

    companion object {
        /** The session in [text]; null for nothing, or for something that is not one. */
        fun decode(text: String?): LastSession? {
            if (text.isNullOrEmpty()) return null
            return try {
                val root = JSONObject(text)
                val project = root.optString(KEY_PROJECT).ifEmpty { return null }
                val active = if (root.isNull(KEY_ACTIVE)) null else root.optString(KEY_ACTIVE).ifEmpty { null }
                val places = LinkedHashMap<String, FilePlace>()
                root.optJSONObject(KEY_PLACES)?.let { files ->
                    for (path in files.keys()) {
                        val place = files.optJSONObject(path) ?: continue
                        places[path] = FilePlace(
                            row = place.optInt(KEY_ROW, 0).coerceAtLeast(0),
                            column = place.optInt(KEY_COLUMN, 0).coerceAtLeast(0),
                            firstVisibleRow = place.optInt(KEY_TOP, 0).coerceAtLeast(0),
                        )
                    }
                }
                LastSession(project, active, places)
            } catch (malformed: JSONException) {
                null
            }
        }

        private const val KEY_PROJECT = "project"
        private const val KEY_ACTIVE = "active"
        private const val KEY_PLACES = "places"
        private const val KEY_ROW = "row"
        private const val KEY_COLUMN = "column"
        private const val KEY_TOP = "top"
    }
}
