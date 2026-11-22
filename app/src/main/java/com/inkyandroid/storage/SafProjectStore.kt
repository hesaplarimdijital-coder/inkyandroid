package com.inkyandroid.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import com.inkyandroid.core.project.ProjectPaths
import com.inkyandroid.core.project.ProjectStore
import com.inkyandroid.core.project.StoredFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException

/**
 * A project folder reached through the Storage Access Framework: the folder [rootDocumentId]
 * inside the granted tree [treeUri]. Talks to the provider with `DocumentsContract` directly
 * (one query per folder, only the needed columns), never through `DocumentFile`.
 *
 * Paths map to document ids through a cache filled by [list] and by every lookup, so that saving a
 * file does not walk the tree again; an id that no longer leads to a document of the expected
 * name is forgotten and the path looked up afresh, one folder query per level.
 *
 * Names are matched exactly (case included), as ink's `INCLUDE` does. Two documents with the same
 * name in one folder (cloud providers allow it) are logged; the first one listed is the project's.
 *
 * Writes replace the document's content in place, which keeps the document (its id, the grants
 * other apps hold for it, a cloud provider's version history) instead of writing a temporary
 * document and renaming it: SAF has no atomic replace, a rename onto an existing name is not
 * defined, and on cloud providers every replaced document would land in the trash. The text is
 * safe meanwhile in the session's draft journal, which is only cleared once [write] returned, and
 * [write] returns only after it verified what the provider stored.
 *
 * Failures are typed (see [StorageProblem]): [PermissionLostException] when the grant for the
 * tree is gone, [NoSuchFileException] for a missing path or project folder,
 * [ProviderFailedException] with the provider's words for everything else.
 */
class SafProjectStore internal constructor(
    private val resolver: ContentResolver,
    /** The granted tree. */
    val treeUri: Uri,
    /** The project's root folder inside [treeUri]. */
    val rootDocumentId: String,
    override val displayName: String,
    private val permissionHeld: () -> Boolean,
    private val io: CoroutineDispatcher,
) : ProjectStore {

    private val lock = Mutex()

    /** Root-relative path (of a file or a folder; `""` is the root) → document id. Guarded by [lock]. */
    private val ids = HashMap<String, String>()

    override suspend fun list(): List<StoredFile> = guarded("list", null) { walk() }

    override suspend fun read(path: String): String {
        ProjectPaths.requireValid(path)
        return guarded("read", path) {
            val doc = resolve(path)?.takeIf { it.isFile } ?: throw NoSuchFileException(path)
            if (doc.size > StorageLimits.MAX_TEXT_BYTES) throw NotTextException("$path is larger than ${StorageLimits.MAX_TEXT_BYTES} bytes")
            DocumentIo.decodeText(DocumentIo.readBytes(resolver, uriOf(doc.documentId), path), path)
        }
    }

    override suspend fun write(path: String, text: String): StoredFile {
        ProjectPaths.requireValid(path)
        return guarded("write", path) {
            val bytes = DocumentIo.encodeText(text)
            val existing = resolve(path)
            val doc = when {
                existing == null -> createFile(ensureFolder(parentOf(path)), path)
                existing.isDirectory -> throw FileAlreadyExistsException(path, null, "a folder has this name")
                existing.refuses(Document.FLAG_SUPPORTS_WRITE) -> throw ReadOnlyException("$path is read-only")
                else -> existing
            }
            ids[path] = doc.documentId
            DocumentIo.writeBytes(resolver, uriOf(doc.documentId), bytes)
            verifiedAfterWrite(path, doc.documentId, bytes)
        }
    }

    override suspend fun delete(path: String) {
        ProjectPaths.requireValid(path)
        guarded("delete", path) {
            val doc = resolve(path)?.takeIf { it.isFile } ?: throw NoSuchFileException(path)
            DocumentsContract.deleteDocument(resolver, uriOf(doc.documentId))
            ids.remove(path)
            if (SafDocuments.document(resolver, treeUri, doc.documentId)?.name == doc.name) {
                throw ProviderFailedException("$path is still there after deleting it")
            }
        }
    }

    override suspend fun rename(from: String, to: String): StoredFile {
        ProjectPaths.requireValid(from)
        ProjectPaths.requireValid(to)
        return guarded("rename", "$from → $to") {
            val source = resolve(from)?.takeIf { it.isFile } ?: throw NoSuchFileException(from)
            if (from == to) return@guarded source.toStored(to)
            if (resolve(to) != null) throw FileAlreadyExistsException(to)
            val moved = if (parentOf(from) == parentOf(to)) {
                renameInPlace(source, from, to)
            } else {
                move(source, from, to)
            }
            ids.remove(from)
            ids[to] = moved.documentId
            moved.toStored(to)
        }
    }

    override suspend fun stat(path: String): StoredFile? {
        ProjectPaths.requireValid(path)
        return guarded("stat", path) { resolve(path)?.takeIf { it.isFile }?.toStored(path) }
    }

    /**
     * Whether the project can be used right now: `null` when its folder answers, otherwise the
     * reason (permission gone, folder gone, provider failing). Never throws, except on
     * cancellation.
     */
    suspend fun checkAccess(): StorageProblem? = try {
        guarded("check", null) {
            if (!permissionHeld()) throw PermissionLostException("the permission for $displayName is gone")
            SafDocuments.document(resolver, treeUri, rootDocumentId)?.takeIf { it.isDirectory }
                ?: throw NoSuchFileException(displayName, null, "the project folder is gone")
            null
        }
    } catch (e: IOException) {
        StorageProblem.of(e)
    }

    /** The names of everything directly in the root folder, hidden entries included (to pick a free name). */
    internal suspend fun childNames(): List<String> = guarded("names", null) {
        SafDocuments.children(resolver, treeUri, rootDocumentId)?.map { it.name } ?: throw folderGone()
    }

    /** The document id of the file or folder at [path], or `null` when there is none. */
    internal suspend fun documentIdOf(path: String): String? {
        ProjectPaths.requireValid(path)
        return guarded("lookup", path) { resolve(path)?.documentId }
    }

    // ---- walking and looking up ----------------------------------------------------------------

    private suspend fun walk(): List<StoredFile> {
        val files = ArrayList<StoredFile>()
        val found = HashMap<String, String>()
        found[""] = rootDocumentId
        val pending = ArrayDeque<String>()
        pending.add("")
        var entries = 0
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val folder = pending.removeFirst()
            val children = SafDocuments.children(resolver, treeUri, found.getValue(folder))
                ?: throw if (folder.isEmpty()) folderGone() else ProviderFailedException("the folder $folder could not be listed")
            for (child in children) {
                // hidden files and folders are never part of an ink project (.git, .Trash)
                if (child.name.startsWith('.')) continue
                val path = if (folder.isEmpty()) child.name else "$folder/${child.name}"
                if (!ProjectPaths.isValid(path)) {
                    Log.w(LOG_TAG, "skipped a document whose name cannot be a project path: \"${child.name}\" in \"$folder\"")
                    continue
                }
                if (++entries > StorageLimits.MAX_ENTRIES) {
                    throw FolderTooLargeException("$displayName holds more than ${StorageLimits.MAX_ENTRIES} files and folders")
                }
                if (path in found) {
                    Log.w(LOG_TAG, "two documents are named \"$path\"; the second one (${child.documentId}) is ignored")
                    continue
                }
                when {
                    child.isDirectory -> {
                        if (path.count { it == '/' } + 1 > StorageLimits.MAX_DEPTH) {
                            throw FolderTooLargeException("$displayName has folders deeper than ${StorageLimits.MAX_DEPTH} levels")
                        }
                        found[path] = child.documentId
                        pending.add(path)
                    }
                    child.isFile -> {
                        found[path] = child.documentId
                        files += child.toStored(path)
                    }
                    else -> Log.i(LOG_TAG, "skipped \"$path\": neither a regular file nor a folder (${child.mimeType}, flags ${child.flags})")
                }
            }
        }
        ids.clear()
        ids.putAll(found)
        return files
    }

    /** The document at [path] (file or folder), or `null` when there is none. */
    private fun resolve(path: String): SafDocument? {
        cached(path)?.let { return it }
        val parentId = resolveFolder(parentOf(path)) ?: return null
        val doc = childNamed(parentId, nameOf(path)) ?: return null
        ids[path] = doc.documentId
        return doc
    }

    /** The document id of the folder at [path], or `null` when there is no such folder. */
    private fun resolveFolder(path: String): String? {
        if (path.isEmpty()) return rootDocumentId
        cached(path)?.let { return it.documentId.takeIf { _ -> it.isDirectory } }
        val parentId = resolveFolder(parentOf(path)) ?: return null
        val doc = childNamed(parentId, nameOf(path))?.takeIf { it.isDirectory } ?: return null
        ids[path] = doc.documentId
        return doc.documentId
    }

    /** The cached document for [path], if the id still leads to a document of that name. */
    private fun cached(path: String): SafDocument? {
        val id = ids[path] ?: return null
        val doc = SafDocuments.document(resolver, treeUri, id)
        if (doc != null && doc.name == nameOf(path)) return doc
        ids.remove(path)
        return null
    }

    private fun childNamed(parentId: String, name: String): SafDocument? =
        SafDocuments.children(resolver, treeUri, parentId)?.firstOrNull { it.name == name }

    // ---- creating --------------------------------------------------------------------------------

    /** The folder at [path], created with its missing parents. */
    private fun ensureFolder(path: String): String {
        if (path.isEmpty()) return rootDocumentId
        resolveFolder(path)?.let { return it }
        val parentId = ensureFolder(parentOf(path))
        childNamed(parentId, nameOf(path))?.let {
            if (!it.isDirectory) throw FileAlreadyExistsException(path, null, "a file has the name of a needed folder")
            ids[path] = it.documentId
            return it.documentId
        }
        val created = create(parentId, Document.MIME_TYPE_DIR, path)
        if (!created.isDirectory) throw ProviderFailedException("$path was created as a file, not a folder")
        ids[path] = created.documentId
        return created.documentId
    }

    private fun createFile(parentId: String, path: String): SafDocument {
        val created = create(parentId, MIME_TEXT_FILE, path)
        if (created.isDirectory) throw FileAlreadyExistsException(path, null, "a folder has this name")
        return created
    }

    /**
     * Creates the document [path] (its name is the last segment) in the folder [parentId].
     *
     * Providers may store it under another name: "story (1).ink" when a document of that name
     * exists that the cache did not know, or "story.ink.txt" when the provider derives the
     * extension from the MIME type (hence [MIME_TEXT_FILE], which keeps every name). The first case
     * uses the existing document; the second renames the new one back; when neither works the new
     * document is removed again and the call fails.
     */
    private fun create(parentId: String, mimeType: String, path: String): SafDocument {
        val name = nameOf(path)
        val uri = DocumentsContract.createDocument(resolver, uriOf(parentId), mimeType, name)
            ?: throw ProviderFailedException("$path could not be created")
        val id = DocumentsContract.getDocumentId(uri)
        val doc = SafDocuments.document(resolver, treeUri, id)
            ?: throw ProviderFailedException("$path was created but cannot be found")
        if (doc.name == name) return doc
        Log.w(LOG_TAG, "the provider stored \"$path\" as \"${doc.name}\"")
        val sameNamed = childNamed(parentId, name)
        if (sameNamed != null && sameNamed.documentId != id) {
            deleteQuietly(id)
            return sameNamed
        }
        renamed(doc, name)?.let { return it }
        deleteQuietly(id)
        throw ProviderFailedException("the storage saves \"$name\" only as \"${doc.name}\"")
    }

    // ---- writing ---------------------------------------------------------------------------------

    /**
     * What storage holds after writing [bytes] to [documentId]: the size the provider reports must
     * match, and when it does not (or it reports none) the content is read back and compared.
     */
    private fun verifiedAfterWrite(path: String, documentId: String, bytes: ByteArray): StoredFile {
        val after = SafDocuments.document(resolver, treeUri, documentId)
            ?: throw ProviderFailedException("$path is gone after writing it")
        if (after.size != bytes.size.toLong()) {
            val stored = DocumentIo.readBytes(resolver, uriOf(documentId), path)
            if (!stored.contentEquals(bytes)) {
                throw ProviderFailedException("the storage kept ${stored.size} of ${bytes.size} bytes of $path")
            }
            Log.i(LOG_TAG, "$path: the provider reports ${after.size} bytes for ${bytes.size} written; the content is right")
        }
        return after.toStored(path)
    }

    // ---- renaming and moving ---------------------------------------------------------------------

    private fun renameInPlace(source: SafDocument, from: String, to: String): SafDocument {
        if (!source.refuses(Document.FLAG_SUPPORTS_RENAME)) {
            renamed(source, nameOf(to))?.let { return it }
            // A failed rename may still have changed the name to something else: put it back.
            SafDocuments.document(resolver, treeUri, source.documentId)?.let { now ->
                if (now.name != source.name) renamed(now, source.name)
            }
        }
        return copyThenDelete(source, from, to)
    }

    private fun move(source: SafDocument, from: String, to: String): SafDocument {
        val fromParentId = resolveFolder(parentOf(from)) ?: throw NoSuchFileException(from)
        val toParentId = ensureFolder(parentOf(to))
        if (!source.refuses(Document.FLAG_SUPPORTS_MOVE)) {
            val movedUri = try {
                DocumentsContract.moveDocument(resolver, uriOf(source.documentId), uriOf(fromParentId), uriOf(toParentId))
            } catch (e: UnsupportedOperationException) {
                Log.i(LOG_TAG, "move not supported for $from: ${e.message}")
                null
            } catch (e: IllegalStateException) {
                Log.w(LOG_TAG, "move failed for $from: ${e.message}")
                null
            }
            if (movedUri != null) {
                val moved = SafDocuments.document(resolver, treeUri, DocumentsContract.getDocumentId(movedUri))
                    ?: throw ProviderFailedException("$from was moved but cannot be found")
                if (moved.name == nameOf(to)) return moved
                return renamed(moved, nameOf(to))
                    ?: throw ProviderFailedException("$from was moved to the folder of $to but could not take its name")
            }
        }
        return copyThenDelete(source, from, to)
    }

    /**
     * The fallback for providers without rename or move: the bytes are copied to [to], verified,
     * and only then is [from] deleted. If deleting fails both stay, and the failure is reported.
     */
    private fun copyThenDelete(source: SafDocument, from: String, to: String): SafDocument {
        Log.i(LOG_TAG, "renaming $from to $to by copying")
        val bytes = DocumentIo.readBytes(resolver, uriOf(source.documentId), from)
        val target = createFile(ensureFolder(parentOf(to)), to)
        ids[to] = target.documentId
        DocumentIo.writeBytes(resolver, uriOf(target.documentId), bytes)
        verifiedAfterWrite(to, target.documentId, bytes)
        DocumentsContract.deleteDocument(resolver, uriOf(source.documentId))
        ids.remove(from)
        return SafDocuments.document(resolver, treeUri, target.documentId)
            ?: throw ProviderFailedException("$to is gone after copying")
    }

    /** [doc] renamed to [name], or `null` when the provider refused or chose another name. */
    private fun renamed(doc: SafDocument, name: String): SafDocument? {
        val uri = try {
            DocumentsContract.renameDocument(resolver, uriOf(doc.documentId), name)
        } catch (e: UnsupportedOperationException) {
            Log.i(LOG_TAG, "rename not supported for ${doc.name}: ${e.message}")
            return null
        } catch (e: IllegalStateException) {
            Log.w(LOG_TAG, "rename of ${doc.name} to $name failed: ${e.message}")
            return null
        } catch (e: FileNotFoundException) {
            Log.w(LOG_TAG, "rename of ${doc.name} to $name failed: ${e.message}")
            return null
        }
        // null: the provider kept the document id (the call may still have succeeded)
        val id = uri?.let { DocumentsContract.getDocumentId(it) } ?: doc.documentId
        return SafDocuments.document(resolver, treeUri, id)?.takeIf { it.name == name }
    }

    private fun deleteQuietly(documentId: String) {
        try {
            DocumentsContract.deleteDocument(resolver, uriOf(documentId))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "could not remove the document $documentId again: ${e.message}")
        }
    }

    // ---- plumbing ----------------------------------------------------------------------------------

    private fun uriOf(documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    private fun folderGone(): IOException =
        if (!permissionHeld()) {
            PermissionLostException("the permission for $displayName is gone")
        } else {
            NoSuchFileException(displayName, null, "the project folder is gone")
        }

    /**
     * Runs [block] on the I/O dispatcher, one call of this store at a time, and turns whatever the
     * provider threw into the failures the interface promises.
     */
    private suspend fun <T> guarded(operation: String, path: String?, block: suspend () -> T): T =
        withContext(io) {
            lock.withLock {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: StorageException) {
                    Log.w(LOG_TAG, "$operation ${path.orEmpty()} in $displayName: ${e.message}")
                    throw e
                } catch (e: NoSuchFileException) {
                    throw if (!permissionHeld()) PermissionLostException("the permission for $displayName is gone", e) else e
                } catch (e: FileAlreadyExistsException) {
                    throw e
                } catch (e: SecurityException) {
                    Log.w(LOG_TAG, "$operation ${path.orEmpty()} in $displayName: permission denied: ${e.message}")
                    throw PermissionLostException("the permission for $displayName is gone", e)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "$operation ${path.orEmpty()} in $displayName ($treeUri) failed", e)
                    ids.clear()
                    throw if (!permissionHeld()) {
                        PermissionLostException("the permission for $displayName is gone", e)
                    } else if (e is FileNotFoundException && path != null) {
                        NoSuchFileException(path, null, e.message)
                    } else {
                        ProviderFailedException(e.message ?: e.javaClass.simpleName, e)
                    }
                }
            }
        }

    private fun SafDocument.toStored(path: String) = StoredFile(path, size.coerceAtLeast(0L), modified)

    internal companion object {
        /**
         * The MIME type of the files the store creates: with it providers keep the name as given
         * (with `text/plain` the system's own provider turns "story.ink" into "story.ink.txt").
         */
        const val MIME_TEXT_FILE = "application/octet-stream"

        fun parentOf(path: String): String = path.substringBeforeLast('/', "")
        fun nameOf(path: String): String = path.substringAfterLast('/')
    }
}
