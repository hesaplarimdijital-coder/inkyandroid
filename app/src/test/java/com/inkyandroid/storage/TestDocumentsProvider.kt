package com.inkyandroid.storage

import android.Manifest
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import org.robolectric.Robolectric
import java.io.File
import java.io.FileNotFoundException

/**
 * A real `DocumentsProvider` over a temporary directory, registered with Robolectric's content
 * resolver, so the stores are tested through the same `DocumentsContract` calls, cursors, tree
 * checks and file descriptors as on a device.
 *
 * Document ids follow the system's external storage provider: `"root:"` for the top, `"root:a/b.ink"`
 * below it. [Behaviour] switches on the oddities real providers have.
 */
class TestDocumentsProvider : DocumentsProvider() {

    /** What this provider does differently from a well-behaved one; tests change it freely. */
    class Behaviour {
        /** Every call throws a SecurityException, as the system does once the grant is gone. */
        var denyAccess = false

        /** Every call throws an IllegalStateException, as a crashing provider's binder does. */
        var crash = false

        /** Names of created files are changed by this (an extension derived from the MIME type). */
        var renameOnCreate: ((String) -> String)? = null

        /** Whether rename and move are offered (flags and calls). */
        var supportsRename = true
        var supportsMove = true

        /** Writes are accepted and thrown away, as a provider that fails silently. */
        var loseWrites = false

        /** "wt" is refused, as some providers refuse it. */
        var refuseTruncateMode = false

        /** findDocumentPath is not implemented. */
        var noFindPath = false

        /** The size column is left out. */
        var omitSize = false
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = guard {
        MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE)).apply {
            newRow().add(Root.COLUMN_ROOT_ID, "root").add(Root.COLUMN_DOCUMENT_ID, ROOT_ID).add(Root.COLUMN_TITLE, "Test")
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = guard {
        val file = fileOf(documentId)
        if (!file.exists()) throw FileNotFoundException("Missing file for $documentId")
        cursor(projection).also { addRow(it, documentId, file) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = guard {
        val dir = fileOf(parentDocumentId)
        if (!dir.isDirectory) throw FileNotFoundException("Not a folder: $parentDocumentId")
        cursor(projection).also { c ->
            dir.listFiles().orEmpty().sortedBy { it.name }.forEach { addRow(c, idOf(it), it) }
        }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor = guard {
        if (mode == "wt" && behaviour.refuseTruncateMode) throw IllegalArgumentException("Unsupported mode: wt")
        val file = fileOf(documentId)
        if (!file.isFile) throw FileNotFoundException("Missing file for $documentId")
        val target = if (mode != "r" && behaviour.loseWrites) File.createTempFile("lost", null) else file
        ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String = guard {
        val dir = fileOf(parentDocumentId)
        if (!dir.isDirectory) throw FileNotFoundException("Not a folder: $parentDocumentId")
        val isDir = mimeType == Document.MIME_TYPE_DIR
        var name = if (isDir) displayName else behaviour.renameOnCreate?.invoke(displayName) ?: displayName
        var n = 1
        val base = name
        while (File(dir, name).exists()) name = "${base.substringBeforeLast('.')} (${n++})" + if ('.' in base) ".${base.substringAfterLast('.')}" else ""
        val created = File(dir, name)
        if (isDir) check(created.mkdir()) else check(created.createNewFile())
        idOf(created)
    }

    override fun renameDocument(documentId: String, displayName: String): String = guard {
        if (!behaviour.supportsRename) throw UnsupportedOperationException("Rename not supported")
        val file = fileOf(documentId)
        val target = File(file.parentFile, displayName)
        if (target.exists() || !file.renameTo(target)) throw IllegalStateException("Failed to rename to $displayName")
        idOf(target)
    }

    override fun deleteDocument(documentId: String) = guard {
        val file = fileOf(documentId)
        if (!file.exists()) throw FileNotFoundException("Missing file for $documentId")
        check(file.deleteRecursively())
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String = guard {
        if (!behaviour.supportsMove) throw UnsupportedOperationException("Move not supported")
        val file = fileOf(sourceDocumentId)
        val target = File(fileOf(targetParentDocumentId), file.name)
        if (target.exists() || !file.renameTo(target)) throw IllegalStateException("Failed to move ${file.name}")
        idOf(target)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = guard {
        val parent = parentDocumentId.removePrefix(ROOT_ID).trimEnd('/')
        val child = documentId.removePrefix(ROOT_ID)
        documentId.startsWith(ROOT_ID) && (parent.isEmpty() || child.startsWith("$parent/")) && fileOf(documentId).exists()
    }

    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String): DocumentsContract.Path = guard {
        if (behaviour.noFindPath) throw UnsupportedOperationException("findDocumentPath not supported")
        val top = parentDocumentId ?: ROOT_ID
        val ids = ArrayList<String>()
        var current: String? = childDocumentId
        while (current != null) {
            ids.add(0, current)
            if (current == top) break
            current = parentIdOf(current)
        }
        if (ids.first() != top) throw FileNotFoundException("$childDocumentId is not below $top")
        DocumentsContract.Path(if (parentDocumentId == null) "root" else null, ids)
    }

    private fun cursor(projection: Array<out String>?): MatrixCursor {
        val columns = (projection ?: DEFAULT_COLUMNS).filter { !(behaviour.omitSize && it == Document.COLUMN_SIZE) }
        return MatrixCursor(columns.toTypedArray())
    }

    private fun addRow(c: MatrixCursor, id: String, file: File) {
        var flags = 0
        if (file.isDirectory) {
            flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        flags = flags or Document.FLAG_SUPPORTS_DELETE
        if (behaviour.supportsRename) flags = flags or Document.FLAG_SUPPORTS_RENAME
        if (behaviour.supportsMove) flags = flags or Document.FLAG_SUPPORTS_MOVE
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, file.name)
            .add(Document.COLUMN_MIME_TYPE, if (file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream")
            .add(Document.COLUMN_SIZE, if (file.isDirectory) null else file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_FLAGS, flags)
    }

    private inline fun <T> guard(block: () -> T): T {
        if (behaviour.denyAccess) throw SecurityException("Permission Denial: no grant for the test tree")
        if (behaviour.crash) throw IllegalStateException("the test provider crashed")
        return block()
    }

    companion object {
        const val AUTHORITY = "com.inkyandroid.test.documents"
        const val ROOT_ID = "root:"

        private val DEFAULT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        lateinit var rootDir: File
        var behaviour = Behaviour()

        /** Registers a fresh provider over [dir] with Robolectric's content resolver. */
        fun install(dir: File) {
            rootDir = dir
            behaviour = Behaviour()
            val info = ProviderInfo().apply {
                authority = AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = Manifest.permission.MANAGE_DOCUMENTS
                writePermission = Manifest.permission.MANAGE_DOCUMENTS
            }
            Robolectric.buildContentProvider(TestDocumentsProvider::class.java).create(info)
        }

        fun idOf(file: File): String = ROOT_ID + file.relativeTo(rootDir).invariantSeparatorsPath

        fun fileOf(documentId: String): File {
            if (!documentId.startsWith(ROOT_ID)) throw FileNotFoundException("Not ours: $documentId")
            return File(rootDir, documentId.removePrefix(ROOT_ID))
        }

        fun parentIdOf(documentId: String): String? {
            if (documentId == ROOT_ID) return null
            val path = documentId.removePrefix(ROOT_ID)
            return ROOT_ID + if ('/' in path) path.substringBeforeLast('/') else ""
        }

        /** The tree URI the folder picker would return for [relativePath] (`""` = the provider's top). */
        fun treeUri(relativePath: String = ""): Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID + relativePath)

        /** A plain document URI, as the file picker returns it. */
        fun documentUri(relativePath: String): Uri = DocumentsContract.buildDocumentUri(AUTHORITY, ROOT_ID + relativePath)
    }
}
