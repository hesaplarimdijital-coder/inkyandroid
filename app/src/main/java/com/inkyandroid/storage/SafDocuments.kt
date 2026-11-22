package com.inkyandroid.storage

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import java.io.FileNotFoundException

/** The tag every log line of the app's storage code carries, so a bug report can be filtered for it. */
internal const val LOG_TAG = "Inky"

/**
 * One row of a `DocumentsContract` query. Providers differ in what they fill in, so every column
 * but the id and the name is optional: [size] is `-1` and [modified] `0` when the provider leaves
 * them out, [flags] `null`.
 */
internal data class SafDocument(
    val documentId: String,
    val name: String,
    val mimeType: String?,
    val size: Long,
    val modified: Long,
    val flags: Int?,
) {
    val isDirectory: Boolean get() = mimeType == Document.MIME_TYPE_DIR

    /** Neither a folder nor something without a type (some providers list shortcuts and virtual entries that way). */
    val isFile: Boolean get() = !isDirectory && !declares(Document.FLAG_VIRTUAL_DOCUMENT)

    /** Whether the provider says the document has [flag]. */
    fun declares(flag: Int): Boolean = flags != null && (flags and flag) != 0

    /**
     * Whether the provider says it does *not* support [flag]. A provider that sends no flags at all
     * is given the benefit of the doubt: the call is tried, and its failure handled.
     */
    fun refuses(flag: Int): Boolean = flags != null && (flags and flag) == 0
}

/**
 * The `DocumentsContract` queries the stores use: one cursor per folder, with only the columns
 * needed, closed at once. Blocking; callers are on an I/O dispatcher. Queries use the `Bundle`
 * form, which is what `DocumentsProvider` implements (the older form is translated to it).
 *
 * Providers are inconsistent about missing documents: some return `null` or an empty cursor, some
 * throw [FileNotFoundException], and the system's external storage provider throws an
 * [IllegalArgumentException] ("Failed to determine if … is child of …") for a tree URI that names
 * a document which no longer exists. All of these mean "not there" and come back as `null`.
 * [SecurityException] is passed on: it means the permission is gone.
 */
internal object SafDocuments {

    private val PROJECTION = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS,
    )

    /**
     * The direct children of [parentId], or `null` when the provider gives no cursor (the folder
     * is gone, the provider crashed). Rows without an id or a name are skipped and logged.
     */
    fun children(resolver: ContentResolver, treeUri: Uri, parentId: String): List<SafDocument>? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val cursor = try {
            resolver.query(uri, PROJECTION, null as Bundle?, null)
        } catch (e: FileNotFoundException) {
            null
        } catch (e: IllegalArgumentException) {
            Log.w(LOG_TAG, "children of $parentId: ${e.message}")
            null
        } ?: return null
        return cursor.use { c ->
            val columns = Columns(c)
            buildList(c.count.coerceAtLeast(0)) {
                while (c.moveToNext()) {
                    val row = columns.read(c)
                    if (row == null) Log.w(LOG_TAG, "a child of $parentId without id or name was skipped") else add(row)
                }
            }
        }
    }

    /**
     * The document [documentId] inside [treeUri], or `null` when it does not exist.
     *
     * A provider checks that a document addressed through a tree lies inside the tree, and for a
     * document that is gone that check fails: with a [SecurityException] ("… is not a descendant
     * of …") from providers whose `isChildDocument` answers `false` for it. That is not a lost
     * permission when the tree's own root still answers, and then it means "not there".
     */
    fun document(resolver: ContentResolver, treeUri: Uri, documentId: String): SafDocument? = try {
        query(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId))
    } catch (e: SecurityException) {
        val treeRoot = DocumentsContract.getTreeDocumentId(treeUri)
        if (documentId == treeRoot) throw e
        // throws the SecurityException again when the permission is really gone
        query(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, treeRoot))
        Log.i(LOG_TAG, "$documentId is no longer inside $treeUri: ${e.message}")
        null
    }

    /** The document behind a plain document URI (not inside a tree), or `null`. */
    fun query(resolver: ContentResolver, documentUri: Uri): SafDocument? {
        val cursor = try {
            resolver.query(documentUri, PROJECTION, null as Bundle?, null)
        } catch (e: FileNotFoundException) {
            null
        } catch (e: IllegalArgumentException) {
            Log.w(LOG_TAG, "query $documentUri: ${e.message}")
            null
        } ?: return null
        return cursor.use { c -> if (c.moveToFirst()) Columns(c).read(c) else null }
    }

    /**
     * Column positions looked up by name, since a provider may return them in another order or
     * leave some out.
     */
    private class Columns(c: Cursor) {
        private val id = c.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
        private val name = c.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
        private val mime = c.getColumnIndex(Document.COLUMN_MIME_TYPE)
        private val size = c.getColumnIndex(Document.COLUMN_SIZE)
        private val modified = c.getColumnIndex(Document.COLUMN_LAST_MODIFIED)
        private val flags = c.getColumnIndex(Document.COLUMN_FLAGS)

        fun read(c: Cursor): SafDocument? {
            val documentId = c.stringOrNull(id) ?: return null
            val displayName = c.stringOrNull(name) ?: return null
            return SafDocument(
                documentId = documentId,
                name = displayName,
                mimeType = c.stringOrNull(mime),
                size = c.longOrNull(size) ?: -1L,
                modified = c.longOrNull(modified) ?: 0L,
                flags = c.longOrNull(flags)?.toInt(),
            )
        }

        private fun Cursor.stringOrNull(index: Int): String? =
            if (index < 0 || isNull(index)) null else getString(index)

        private fun Cursor.longOrNull(index: Int): Long? =
            if (index < 0 || isNull(index)) null else getLong(index)
    }
}
