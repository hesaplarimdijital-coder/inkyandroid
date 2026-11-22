package com.inkyandroid.storage

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.inkyandroid.core.project.ProjectPaths
import com.inkyandroid.core.project.ProjectStore
import com.inkyandroid.core.project.StoredFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.NoSuchFileException

/**
 * One ink file whose folder is not accessible: the project is that file alone. [list] returns
 * only it, every other path does not exist and cannot be created (an `INCLUDE` of this file
 * cannot be resolved until the user grants the folder: [ProjectStorage.upgradeToFolder]).
 *
 * [documentUri] may be a document URI from the system picker or any `content:` URI another app
 * sent; the store uses only what every content provider offers (`openFileDescriptor`, and the
 * `OpenableColumns` for the size), plus `DocumentsContract` columns when the URI is a document.
 */
class SingleDocumentStore internal constructor(
    private val resolver: ContentResolver,
    /** The file. */
    val documentUri: Uri,
    /** The file's name, which is also its only path. */
    val fileName: String,
    /** Whether the file accepts writes; `false` for a file another app shared read-only. */
    val isWritable: Boolean,
    private val permissionHeld: () -> Boolean,
    private val io: CoroutineDispatcher,
) : ProjectStore {

    private val lock = Mutex()

    init {
        ProjectPaths.requireValid(fileName)
    }

    override val displayName: String get() = fileName

    override suspend fun list(): List<StoredFile> = guarded("list") { listOfNotNull(current()) }

    override suspend fun read(path: String): String {
        requireOwn(path)
        return guarded("read") {
            DocumentIo.decodeText(DocumentIo.readBytes(resolver, documentUri, fileName), fileName)
        }
    }

    override suspend fun write(path: String, text: String): StoredFile {
        requireOwn(path)
        if (!isWritable) throw ReadOnlyException("$fileName was shared read-only")
        return guarded("write") {
            val bytes = DocumentIo.encodeText(text)
            DocumentIo.writeBytes(resolver, documentUri, bytes)
            val after = current() ?: throw ProviderFailedException("$fileName is gone after writing it")
            if (after.size != bytes.size.toLong()) {
                val stored = DocumentIo.readBytes(resolver, documentUri, fileName)
                if (!stored.contentEquals(bytes)) {
                    throw ProviderFailedException("the storage kept ${stored.size} of ${bytes.size} bytes of $fileName")
                }
            }
            after
        }
    }

    /** The file of a single-file project is its main file, which the project model never deletes. */
    override suspend fun delete(path: String) {
        requireOwn(path)
        throw ReadOnlyException("the only file of a single-file project is not deleted")
    }

    /** Renaming would need the folder. */
    override suspend fun rename(from: String, to: String): StoredFile {
        ProjectPaths.requireValid(to)
        requireOwn(from)
        throw ReadOnlyException("a single-file project cannot rename its file")
    }

    override suspend fun stat(path: String): StoredFile? {
        ProjectPaths.requireValid(path)
        if (path != fileName) return null
        return guarded("stat") { current() }
    }

    /** `null` when the file can be read now, otherwise why not. Never throws, except on cancellation. */
    suspend fun checkAccess(): StorageProblem? = try {
        guarded("check") { current() ?: throw NoSuchFileException(fileName) }
        null
    } catch (e: IOException) {
        StorageProblem.of(e)
    }

    /**
     * The file's size and modification time. A document URI answers with `DocumentsContract`
     * columns; any other URI with `OpenableColumns` (size only) or nothing, in which case the size
     * is measured by reading.
     */
    private fun current(): StoredFile? {
        val doc = SafDocuments.query(resolver, documentUri)
        if (doc != null) return StoredFile(fileName, doc.size.takeIf { it >= 0 } ?: measured(), doc.modified)
        val size = OpenableInfo.query(resolver, documentUri)?.size
        return StoredFile(fileName, size ?: measured(), 0L)
    }

    private fun measured(): Long = DocumentIo.readBytes(resolver, documentUri, fileName).size.toLong()

    private fun requireOwn(path: String) {
        ProjectPaths.requireValid(path)
        if (path != fileName) throw NoSuchFileException(path, null, "a single-file project holds only $fileName")
    }

    private suspend fun <T> guarded(operation: String, block: () -> T): T = withContext(io) {
        lock.withLock {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: StorageException) {
                throw e
            } catch (e: NoSuchFileException) {
                throw e
            } catch (e: SecurityException) {
                Log.w(LOG_TAG, "$operation $documentUri: permission denied: ${e.message}")
                throw PermissionLostException("the permission for $fileName is gone", e)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "$operation $documentUri failed", e)
                throw when {
                    !permissionHeld() -> PermissionLostException("the permission for $fileName is gone", e)
                    e is FileNotFoundException -> NoSuchFileException(fileName, null, e.message)
                    else -> ProviderFailedException(e.message ?: e.javaClass.simpleName, e)
                }
            }
        }
    }
}
