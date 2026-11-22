package com.inkyandroid.core.project

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption

/**
 * A project in a directory of the file system: the projects the app keeps in its own storage, and
 * the store the tests run on.
 *
 * A write goes to a temporary file beside the target, is forced to the disk, and then replaces the
 * target in one step, so a crash or a full disk leaves the old file whole. The temporary files'
 * names start with a dot and are therefore never listed.
 *
 * @param root the project's root directory; created by the first write if it does not exist.
 * @param displayName what the UI calls the project's place.
 * @param io where the blocking file calls run.
 */
class DirectoryProjectStore(
    val root: File,
    override val displayName: String = root.name,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ProjectStore {

    override suspend fun list(): List<StoredFile> = withContext(io) {
        val found = ArrayList<StoredFile>()
        collect(root, "", found)
        found
    }

    private fun collect(directory: File, prefix: String, into: MutableList<StoredFile>) {
        val children = directory.listFiles() ?: if (directory.isDirectory) {
            throw IOException("Cannot list ${directory.path}")
        } else {
            return // a project whose folder does not exist yet has no files
        }
        for (child in children) {
            val name = child.name
            if (name.startsWith(".")) continue
            val path = prefix + name
            if (child.isDirectory) collect(child, "$path/", into) else if (child.isFile) into.add(describe(path, child))
        }
    }

    override suspend fun read(path: String): String = withContext(io) {
        val file = resolve(path)
        if (!file.isFile) throw NoSuchFileException(path)
        ProjectText.decode(Files.readAllBytes(file.toPath()))
    }

    override suspend fun write(path: String, text: String): StoredFile = withContext(io) {
        val target = resolve(path)
        val parent = target.parentFile ?: throw IOException("No folder for $path")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Cannot create the folder ${parent.path}")
        }
        if (target.isDirectory) throw IOException("$path is a folder")
        val temporary = File(parent, ".${target.name}.$TEMPORARY_SUFFIX")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(ProjectText.encode(text))
                stream.fd.sync()
            }
            replace(temporary, target)
        } finally {
            // only still there when the write or the move failed
            temporary.delete()
        }
        describe(path, target)
    }

    override suspend fun delete(path: String) = withContext(io) {
        val file = resolve(path)
        if (!file.isFile) throw NoSuchFileException(path)
        Files.delete(file.toPath())
    }

    override suspend fun rename(from: String, to: String): StoredFile = withContext(io) {
        val source = resolve(from)
        val target = resolve(to)
        if (!source.isFile) throw NoSuchFileException(from)
        // checked first: on a case-insensitive file system a move could otherwise replace silently
        if (target.exists()) throw FileAlreadyExistsException(to)
        val parent = target.parentFile ?: throw IOException("No folder for $to")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Cannot create the folder ${parent.path}")
        }
        Files.move(source.toPath(), target.toPath())
        describe(to, target)
    }

    override suspend fun stat(path: String): StoredFile? = withContext(io) {
        val file = resolve(path)
        if (file.isFile) describe(path, file) else null
    }

    private fun resolve(path: String): File = File(root, ProjectPaths.requireValid(path))

    private fun describe(path: String, file: File) = StoredFile(path, file.length(), file.lastModified())

    private fun replace(temporary: File, target: File) {
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (notAtomic: AtomicMoveNotSupportedException) {
            // a file system without atomic rename: still never a half-written target
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val BYTE_ORDER_MARK = "﻿"
        const val TEMPORARY_SUFFIX = "inky-tmp"
    }
}
