package com.inkyandroid.storage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract

/**
 * The system pickers the storage flows start, as `ActivityResultContract`s for
 * `rememberLauncherForActivityResult`. Each returns the picked URI, or `null` when the user backed
 * out. Hand the result to [ProjectStorage] ([ProjectStorage.openFolder], [ProjectStorage.openFile],
 * [ProjectStorage.setProjectsFolder], [ProjectStorage.upgradeToFolder], [ExportWriter]).
 */
object StorageContracts {

    /**
     * Where the folder pickers start when nothing better is known: the device's Documents folder
     * (the suggested projects folder is `Documents/Inky`, which the user creates in the picker).
     */
    val DOCUMENTS_HINT: Uri = DocumentsContract.buildDocumentUri(TreeLocator.EXTERNAL_STORAGE_AUTHORITY, "primary:Documents")

    /**
     * `ACTION_OPEN_DOCUMENT_TREE`: a folder, with read, write and persistable access. The input is
     * where the picker starts ([DOCUMENTS_HINT], [FileOpening.Single.grantHint]) or `null`.
     */
    class PickFolder : ActivityResultContract<Uri?, Uri?>() {
        override fun createIntent(context: Context, input: Uri?): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                if (input != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, input)
                addFlags(GRANTS)
            }

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
    }

    /**
     * `ACTION_OPEN_DOCUMENT`: one file. `.ink` has no MIME type, so every file is offered (as
     * upstream's dialog filters by extension, which Android's picker cannot); [ProjectStorage.openFile]
     * refuses what is not text. The input is where the picker starts, or `null`.
     */
    class OpenInkFile : ActivityResultContract<Uri?, Uri?>() {
        override fun createIntent(context: Context, input: Uri?): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                if (input != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, input)
                addFlags(GRANTS)
            }

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
    }

    /** `ACTION_CREATE_DOCUMENT` for an export file; write it with [ExportWriter.writeFile]. */
    class CreateExportFile : ActivityResultContract<ExportFileRequest, Uri?>() {
        override fun createIntent(context: Context, input: ExportFileRequest): Intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = input.mimeType
                putExtra(Intent.EXTRA_TITLE, input.suggestedName)
                if (input.initialLocation != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, input.initialLocation)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
    }

    private const val GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
}

/**
 * What the create-document picker is asked for.
 *
 * @property suggestedName the file name the picker proposes (`:core`'s export naming rules give it).
 * @property mimeType the created document's type; see [json] and [javascript] for why these.
 * @property initialLocation where the picker starts, or `null`.
 */
data class ExportFileRequest(
    val suggestedName: String,
    val mimeType: String,
    val initialLocation: Uri? = null,
) {
    companion object {
        /** The story's JSON. Providers map `application/json` to `.json`, so the name stays as suggested. */
        fun json(suggestedName: String, initialLocation: Uri? = null) =
            ExportFileRequest(suggestedName, "application/json", initialLocation)

        /**
         * The `story.js` form. The JavaScript MIME type differs between Android versions
         * (`text/javascript`, `application/javascript`) and a mismatch makes the system's provider
         * append a second extension; `application/octet-stream` keeps the name as typed.
         */
        fun javascript(suggestedName: String, initialLocation: Uri? = null) =
            ExportFileRequest(suggestedName, SafProjectStore.MIME_TEXT_FILE, initialLocation)
    }
}

/** The intents other apps send to open a file in Inky (the manifest's `VIEW` filters). */
object ProjectIntents {

    /**
     * The file [intent] asks Inky to open, or `null` when it asks for nothing of the kind. Only
     * `content:` URIs: since Android 7 apps may not share `file:` URIs, and Inky has no permission
     * to read them. Pass the result to [ProjectStorage.openFile] (from `onCreate` and
     * `onNewIntent`); it checks the content, since the filters accept files without a name or type.
     */
    fun fileToOpen(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        return intent.data?.takeIf { it.scheme == "content" }
    }
}
