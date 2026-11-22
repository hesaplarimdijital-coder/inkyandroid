package com.inkyandroid.storage

import android.content.res.Resources
import com.inkyandroid.R
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException

/**
 * The failures of this package's stores beyond the two `ProjectStore` defines itself
 * ([NoSuchFileException] for a missing path, [FileAlreadyExistsException] for a rename onto an
 * existing one). All are [IOException]s, so the project model treats them as any failed I/O; the
 * UI tells them apart with [StorageProblem.of].
 */
sealed class StorageException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The app no longer holds the permission for the folder or file (revoked in the system settings, app data cleared, the grant was never persistable). */
class PermissionLostException(message: String, cause: Throwable? = null) : StorageException(message, cause)

/** The storage provider refused or did something other than asked (a write that did not stick, a file stored under another name). [detail] is the provider's own words, for the bug report. */
class ProviderFailedException(val detail: String, cause: Throwable? = null) : StorageException(detail, cause)

/** The folder is too big to be an ink project (more than [StorageLimits.MAX_ENTRIES] entries or deeper than [StorageLimits.MAX_DEPTH]): the user picked a whole drive or their Documents folder. */
class FolderTooLargeException(message: String) : StorageException(message)

/** The file is not UTF-8 text, or is larger than [StorageLimits.MAX_TEXT_BYTES]; it is never decoded with replacement characters, since saving it again would damage it. */
class NotTextException(message: String) : StorageException(message)

/** The file can be read but not written (another app shared it read-only). */
class ReadOnlyException(message: String) : StorageException(message)

/** Limits that keep a wrong pick from walking a whole drive or loading a video into memory. */
object StorageLimits {
    /** Files and folders a project folder may hold, all depths together. */
    const val MAX_ENTRIES = 5_000

    /** Folder levels below the project's root. */
    const val MAX_DEPTH = 16

    /** Size of a text file the stores read. */
    const val MAX_TEXT_BYTES = 16L * 1024 * 1024
}

/** What went wrong with storage, as the UI explains it to the user. */
enum class StorageProblem {
    PermissionLost,
    NotFound,
    AlreadyExists,
    FolderTooLarge,
    NotText,
    ReadOnly,
    ProviderFailed;

    /** The sentence for a banner or a toast, in the user's language. */
    fun message(resources: Resources): String = resources.getString(
        when (this) {
            PermissionLost -> R.string.storage_error_permission_lost
            NotFound -> R.string.storage_error_not_found
            AlreadyExists -> R.string.storage_error_already_exists
            FolderTooLarge -> R.string.storage_error_folder_too_large
            NotText -> R.string.storage_error_not_text
            ReadOnly -> R.string.storage_error_read_only
            ProviderFailed -> R.string.storage_error_provider_failed
        },
    )

    companion object {
        /** The problem behind any exception a store of this package (or `DirectoryProjectStore`) threw. */
        fun of(failure: Throwable): StorageProblem = when (failure) {
            is PermissionLostException -> PermissionLost
            is NoSuchFileException -> NotFound
            is FileAlreadyExistsException -> AlreadyExists
            is FolderTooLargeException -> FolderTooLarge
            is NotTextException -> NotText
            is ReadOnlyException -> ReadOnly
            is SecurityException -> PermissionLost
            else -> ProviderFailed
        }
    }
}
