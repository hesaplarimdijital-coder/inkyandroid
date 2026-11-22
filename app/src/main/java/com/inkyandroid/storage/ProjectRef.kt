package com.inkyandroid.storage

import org.json.JSONException
import org.json.JSONObject

/**
 * Where a project lives, in a form that survives the process: recent projects, the last session
 * and the "folder → main file" memory store it as the string of [encode].
 *
 * A project is a root folder plus the main ink file in it ([mainPath], root-relative, `/`-separated,
 * as `ProjectStore` paths are). [ProjectStorage.open] turns a reference into the store.
 */
sealed interface ProjectRef {

    /** The main ink file, relative to the project's root (`"story.ink"`). */
    val mainPath: String

    /** What the UI calls the project: the root folder's name, or the file's name for a single file. */
    val displayName: String

    /**
     * Identity of the project, independent of [displayName]: two references with the same key
     * are the same project (recent projects are deduplicated by it).
     */
    val key: String

    /** The stable string form; [decode] reads it back. */
    fun encode(): String

    /**
     * A folder inside a tree the user granted through the Storage Access Framework.
     *
     * @property treeUri the granted tree (`content://…/tree/…`), as the folder picker returned it;
     *   the persisted permission belongs to it. Several projects can share one tree (the projects
     *   folder holds many).
     * @property rootDocumentId the document id of the project's root folder inside that tree (the
     *   tree's own document id when the project is the picked folder itself).
     */
    data class Saf(
        val treeUri: String,
        val rootDocumentId: String,
        override val mainPath: String,
        override val displayName: String,
    ) : ProjectRef {
        override val key: String get() = "saf\u0000$treeUri\u0000$rootDocumentId\u0000$mainPath"
        override fun encode(): String = JSONObject()
            .put(FIELD_VERSION, VERSION)
            .put(FIELD_KIND, KIND_SAF)
            .put(FIELD_TREE, treeUri)
            .put(FIELD_ROOT, rootDocumentId)
            .put(FIELD_MAIN, mainPath)
            .put(FIELD_NAME, displayName)
            .toString()
    }

    /**
     * A project in the app's own storage, `filesDir/projects/<name>/`: no permission involved.
     *
     * @property name the folder's name under `filesDir/projects/` (already sanitised; see
     *   [InternalProjects.create]); it is also the [displayName].
     */
    data class Internal(
        val name: String,
        override val mainPath: String,
    ) : ProjectRef {
        override val displayName: String get() = name
        override val key: String get() = "internal\u0000$name\u0000$mainPath"
        override fun encode(): String = JSONObject()
            .put(FIELD_VERSION, VERSION)
            .put(FIELD_KIND, KIND_INTERNAL)
            .put(FIELD_NAME, name)
            .put(FIELD_MAIN, mainPath)
            .toString()
    }

    /**
     * One ink file without its folder: opened from the file picker or received from another app
     * while its folder is not granted. Only that file can be read and written; `INCLUDE`s cannot
     * be resolved until the project is upgraded to a [Saf] folder project
     * ([ProjectStorage.upgradeToFolder]). [mainPath] is the file's name.
     *
     * @property documentUri the file's URI as it was received (a document URI from the picker, or
     *   any `content:` URI another app sent).
     */
    data class SingleDocument(
        val documentUri: String,
        override val mainPath: String,
    ) : ProjectRef {
        override val displayName: String get() = mainPath
        override val key: String get() = "document\u0000$documentUri"
        override fun encode(): String = JSONObject()
            .put(FIELD_VERSION, VERSION)
            .put(FIELD_KIND, KIND_DOCUMENT)
            .put(FIELD_URI, documentUri)
            .put(FIELD_MAIN, mainPath)
            .toString()
    }

    companion object {
        /**
         * Reads a string written by [encode]; `null` for anything else (an empty string, a string
         * from a future version, a damaged file). Never throws.
         */
        fun decode(encoded: String?): ProjectRef? {
            if (encoded.isNullOrBlank()) return null
            return try {
                val json = JSONObject(encoded)
                if (json.optInt(FIELD_VERSION, -1) != VERSION) return null
                val main = json.optString(FIELD_MAIN).takeIf { it.isNotEmpty() } ?: return null
                when (json.optString(FIELD_KIND)) {
                    KIND_SAF -> Saf(
                        treeUri = json.optString(FIELD_TREE).takeIf { it.isNotEmpty() } ?: return null,
                        rootDocumentId = json.optString(FIELD_ROOT).takeIf { it.isNotEmpty() } ?: return null,
                        mainPath = main,
                        displayName = json.optString(FIELD_NAME),
                    )
                    KIND_INTERNAL -> Internal(
                        name = json.optString(FIELD_NAME).takeIf { it.isNotEmpty() } ?: return null,
                        mainPath = main,
                    )
                    KIND_DOCUMENT -> SingleDocument(
                        documentUri = json.optString(FIELD_URI).takeIf { it.isNotEmpty() } ?: return null,
                        mainPath = main,
                    )
                    else -> null
                }
            } catch (e: JSONException) {
                null
            }
        }

        // The string form: a JSON object with a version, so that a later version of the app can
        // read what this one wrote (and this one refuses what it does not understand).
        private const val VERSION = 1
        private const val FIELD_VERSION = "v"
        private const val FIELD_KIND = "kind"
        private const val FIELD_TREE = "tree"
        private const val FIELD_ROOT = "root"
        private const val FIELD_MAIN = "main"
        private const val FIELD_NAME = "name"
        private const val FIELD_URI = "uri"
        private const val KIND_SAF = "saf"
        private const val KIND_INTERNAL = "internal"
        private const val KIND_DOCUMENT = "document"
    }
}
