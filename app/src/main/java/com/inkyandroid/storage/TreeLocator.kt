package com.inkyandroid.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.FileNotFoundException

/**
 * Where a document lies inside a granted tree. Two ways, tried in order:
 *
 * 1. `DocumentsContract.findDocumentPath` with the document addressed through the tree: the
 *    provider answers with the ids from the tree's root down to the document. The system's
 *    external storage provider supports it; many others do not.
 * 2. The documented id scheme of the system's external storage provider (`"<root>:<path>"`), for
 *    when the first way fails; the parent found this way is confirmed by querying it through the
 *    tree.
 */
internal object TreeLocator {

    /** The authority of the system's external storage provider (internal storage, SD cards, USB drives). */
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /**
     * The document id of the folder that holds [documentId], when that folder is inside [treeUri];
     * otherwise `null`. Blocking.
     */
    fun parentInTree(resolver: ContentResolver, treeUri: Uri, documentId: String): String? {
        if (treeUri.authority == null) return null
        val viaTree = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        try {
            val path = DocumentsContract.findDocumentPath(resolver, viaTree)?.path
            if (path != null && path.size >= 2 && path.last() == documentId) return path[path.size - 2]
        } catch (e: SecurityException) {
            // the provider says the document is not in this tree
            return null
        } catch (e: UnsupportedOperationException) {
            Log.i(LOG_TAG, "findDocumentPath unsupported by ${treeUri.authority}")
        } catch (e: FileNotFoundException) {
            return null
        } catch (e: IllegalArgumentException) {
            Log.i(LOG_TAG, "findDocumentPath failed for $documentId: ${e.message}")
        } catch (e: IllegalStateException) {
            Log.i(LOG_TAG, "findDocumentPath failed for $documentId: ${e.message}")
        }
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val parent = externalStorageParent(documentId) ?: return null
        val treeRoot = DocumentsContract.getTreeDocumentId(treeUri)
        val inside = parent == treeRoot || parent.startsWith(if (treeRoot.endsWith(':')) treeRoot else "$treeRoot/")
        if (!inside) return null
        return try {
            parent.takeIf { SafDocuments.document(resolver, treeUri, it)?.isDirectory == true }
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * Where a tree picker should start to grant the folder of [documentUri]: the folder itself when
     * its id can be derived (external storage), else the document (pickers then open its folder).
     */
    fun folderHint(documentUri: Uri): Uri {
        if (documentUri.authority == EXTERNAL_STORAGE_AUTHORITY) {
            val parent = documentIdOf(documentUri)?.let(::externalStorageParent)
            if (parent != null) return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, parent)
        }
        return documentUri
    }

    /**
     * The document id of a `DocumentsContract` URI (`…/document/<id>` or `…/tree/<t>/document/<id>`),
     * or `null` for any other content URI (a file manager's own provider, the media store).
     * Decided by the URI's shape alone: `DocumentsContract.isDocumentUri` needs the provider to be
     * resolvable through the package manager, which some sharing apps' URIs are not.
     */
    fun documentIdOf(uri: Uri): String? {
        val segments = uri.pathSegments
        val isDocument = (segments.size == 2 && segments[0] == "document") ||
            (segments.size == 4 && segments[0] == "tree" && segments[2] == "document")
        return if (isDocument) segments.last() else null
    }

    /** `"primary:Stories/a.ink"` → `"primary:Stories"`; `"primary:a.ink"` → `"primary:"`. */
    fun externalStorageParent(documentId: String): String? {
        val colon = documentId.indexOf(':')
        if (colon < 0) return null
        val path = documentId.substring(colon + 1).trimEnd('/')
        if (path.isEmpty()) return null
        val slash = path.lastIndexOf('/')
        return documentId.substring(0, colon + 1) + if (slash < 0) "" else path.substring(0, slash)
    }
}
