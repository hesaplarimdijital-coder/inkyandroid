package com.inkyandroid.storage

import android.util.Log
import com.inkyandroid.core.project.ProjectStore
import com.inkyandroid.core.project.StoredFile

/** Copies a project's files from one store to another, through the stores' own text interface. */
internal object ProjectCopier {

    /**
     * Writes every file [from] lists into [to] at the same path. Files that are not UTF-8 text
     * (pictures, sounds an author keeps beside the story) cannot pass through a `ProjectStore` and
     * are skipped; their paths are returned. Any other failure stops the copy and is thrown.
     */
    suspend fun copy(from: ProjectStore, to: ProjectStore): List<String> {
        val skipped = ArrayList<String>()
        for (file in from.list().sortedBy { it.path }) {
            val text = try {
                from.read(file.path)
            } catch (e: NotTextException) {
                Log.i(LOG_TAG, "not copied, not text: ${file.path}")
                skipped += file.path
                continue
            }
            to.write(file.path, text)
        }
        return skipped
    }
}

/**
 * [inner] seen from its sub-folder [prefix]: paths gain `"<prefix>/"` on the way in and lose it on
 * the way out. Used to fill a new sub-folder of a picked tree before its document id is known.
 */
internal class PrefixedStore(private val inner: ProjectStore, private val prefix: String) : ProjectStore {
    override val displayName: String get() = prefix
    override suspend fun list(): List<StoredFile> =
        inner.list().filter { it.path.startsWith("$prefix/") }.map { it.copy(path = it.path.removePrefix("$prefix/")) }
    override suspend fun read(path: String): String = inner.read("$prefix/$path")
    override suspend fun write(path: String, text: String): StoredFile = inner.write("$prefix/$path", text).copy(path = path)
    override suspend fun delete(path: String) = inner.delete("$prefix/$path")
    override suspend fun rename(from: String, to: String): StoredFile = inner.rename("$prefix/$from", "$prefix/$to").copy(path = to)
    override suspend fun stat(path: String): StoredFile? = inner.stat("$prefix/$path")?.copy(path = path)
}
