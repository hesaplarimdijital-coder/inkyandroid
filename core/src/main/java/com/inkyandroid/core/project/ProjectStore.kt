package com.inkyandroid.core.project

import java.io.IOException

/**
 * Where a project's files live: a folder reached through Android's Storage Access Framework, a
 * directory inside the app, or a temporary folder in a test. The project model performs all of its
 * input and output through this interface and knows nothing else about storage.
 *
 * **Paths** are relative to the project's root, with `/` as the separator, no leading or trailing
 * `/`, no `.` or `..` segments (`"story.ink"`, `"chapters/one.ink"`). An implementation rejects any
 * other path with an [IllegalArgumentException]; [ProjectPaths.requireValid] does the check.
 *
 * **Failures** are [IOException]s: every function may throw one, and the caller keeps its state
 * when it does (a failed [write] leaves the file "unsaved"; nothing is retried here). A path that
 * does not exist is a [NoSuchFileException][java.nio.file.NoSuchFileException] from [read],
 * [delete] and [rename], and `null` from [stat].
 *
 * **Threads**: the functions suspend and do their own dispatching (an implementation moves to an
 * I/O dispatcher itself); callers invoke them from any coroutine. Calls for one store are made one
 * at a time by the project model, but an implementation must not rely on that.
 *
 * **Text** is UTF-8. [read] removes a leading byte order mark and changes nothing else: line
 * endings come back as stored (`\r\n` stays `\r\n`). [write] writes exactly the given text, without
 * a byte order mark. Use [ProjectText.decode] and [ProjectText.encode]: a file that is not valid
 * UTF-8 fails to read (an `IOException`) instead of being opened damaged.
 */
interface ProjectStore {

    /**
     * Every regular file under the root, at any depth, in no particular order. Directories are not
     * listed. Files and folders whose name starts with a dot are skipped (they are never part of an
     * ink project and may be huge: `.git`).
     */
    @Throws(IOException::class)
    suspend fun list(): List<StoredFile>

    /** The text of [path]: UTF-8, a leading byte order mark removed, line endings untouched. */
    @Throws(IOException::class)
    suspend fun read(path: String): String

    /**
     * Makes [path] contain exactly [text], creating the file and its parent folders when they do
     * not exist. Atomic where storage allows it (a reader sees the old or the new content, never a
     * half-written file); where it does not, the old content must not be destroyed before the new
     * one is safely written.
     *
     * Returns the file as it is on storage after the write. The project model remembers this
     * answer to tell its own saves from changes made outside, so [StoredFile.modified] and
     * [StoredFile.size] must be the values a later [stat] or [list] reports for the untouched file.
     */
    @Throws(IOException::class)
    suspend fun write(path: String, text: String): StoredFile

    /** Removes the file at [path]. Folders that become empty may stay. */
    @Throws(IOException::class)
    suspend fun delete(path: String)

    /**
     * Moves the file at [from] to [to], creating the parent folders of [to]. Fails with a
     * [FileAlreadyExistsException][java.nio.file.FileAlreadyExistsException] when [to] exists;
     * nothing is ever overwritten by a rename.
     */
    @Throws(IOException::class)
    suspend fun rename(from: String, to: String): StoredFile

    /** The file at [path], or `null` when there is none (or it is a folder). */
    @Throws(IOException::class)
    suspend fun stat(path: String): StoredFile?

    /** What the UI calls this place: the folder's name, or a path a person recognises. */
    val displayName: String
}

/**
 * A file as storage reports it.
 *
 * @property path root-relative, `/`-separated (see [ProjectStore]).
 * @property size in bytes.
 * @property modified the last modification time in milliseconds since the epoch, or `0` when
 *   storage does not tell. Together with [size] it is only ever compared for equality, to notice
 *   that a file may have changed; the content decides whether it did.
 */
data class StoredFile(
    val path: String,
    val size: Long,
    val modified: Long,
)
