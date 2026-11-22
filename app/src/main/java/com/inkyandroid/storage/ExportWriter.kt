package com.inkyandroid.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Writes exports where the user chose (architecture 4.2): the story's JSON or its `story.js` into
 * a file from the create-document picker ([StorageContracts.CreateExportFile]), the web player's
 * files into a new folder of a picked tree ([StorageContracts.PickFolder]).
 */
class ExportWriter internal constructor(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher,
) {
    /**
     * Replaces the content of the document [uri] (just created by the picker, or an existing file
     * the user chose to overwrite) with [text] and verifies it.
     *
     * @return the document's name as the provider stored it (it may differ from the suggested name:
     *   the user can type another, and some providers append an extension); for the toast.
     * @throws IOException typed as the stores' failures ([StorageProblem.of]).
     */
    suspend fun writeFile(uri: Uri, text: String): String = withContext(io) {
        guarded(uri) {
            val bytes = DocumentIo.encodeText(text)
            DocumentIo.writeBytes(resolver, uri, bytes)
            val stored = DocumentIo.readBytes(resolver, uri, uri.lastPathSegment.orEmpty())
            if (!stored.contentEquals(bytes)) throw ProviderFailedException("the storage kept ${stored.size} of ${bytes.size} bytes")
            SafDocuments.query(resolver, uri)?.name ?: OpenableInfo.query(resolver, uri)?.name ?: uri.lastPathSegment.orEmpty()
        }
    }

    /**
     * Writes [files] (relative path → text, as `:core`'s web export lists them) into the folder
     * [folderName] directly in the picked tree [treeUri], creating it and its sub-folders when
     * needed. Files already there with the same names are overwritten, others are left alone, as
     * upstream's export does.
     *
     * @return the name of the folder written to.
     * @throws IllegalArgumentException when [folderName] is not a single folder name or a path is not root-relative.
     * @throws IOException typed as the stores' failures.
     */
    suspend fun writeFolder(treeUri: Uri, folderName: String, files: List<Pair<String, String>>): String {
        require(folderName.isNotEmpty() && '/' !in folderName && folderName != "." && folderName != "..") { "not a folder name: $folderName" }
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val name = withContext(io) {
            guarded(treeUri) { SafDocuments.document(resolver, treeUri, rootId)?.name ?: folderName }
        }
        // An export needs the picker's grant only for this process: nothing is persisted, and a
        // failure is the provider's (a revoked grant surfaces as a SecurityException).
        val tree = SafProjectStore(resolver, treeUri, rootId, name, permissionHeld = { true }, io = io)
        for ((path, text) in files) tree.write("$folderName/$path", text)
        return folderName
    }

    private inline fun <T> guarded(uri: Uri, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: StorageException) {
        throw e
    } catch (e: SecurityException) {
        throw PermissionLostException("no permission for $uri", e)
    } catch (e: Exception) {
        Log.w(LOG_TAG, "export to $uri failed", e)
        throw ProviderFailedException(e.message ?: e.javaClass.simpleName, e)
    }
}
