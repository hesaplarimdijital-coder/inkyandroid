package com.inkyandroid.storage

import android.util.Log
import com.inkyandroid.core.project.ProjectStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Projects in the app's own storage, `filesDir/projects/<name>/`: no permission, nothing to pick,
 * instant. New projects go here until the user picks a projects folder, and the first start opens a
 * sample here. They can be copied out to a folder later ([ProjectStorage.copyToFolder]).
 *
 * Their files are read and written through [store], `:core`'s `java.io.File` implementation of
 * the store.
 */
class InternalProjects internal constructor(
    /** `filesDir/projects`. */
    val root: File,
    private val io: CoroutineDispatcher,
    private val storeFactory: (File, String) -> ProjectStore,
) {

    /** Every internal project, by name. The main file is `<name>.ink` when it exists, otherwise the first candidate. */
    suspend fun list(): List<ProjectRef.Internal> = withContext(io) {
        val folders = root.listFiles { f -> f.isDirectory && !f.name.startsWith('.') }.orEmpty()
        folders.sortedBy { it.name.lowercase() }.mapNotNull { folder -> mainOf(folder)?.let { ProjectRef.Internal(folder.name, it) } }
    }

    /**
     * A new project from [typedName] ([ProjectNames.sanitize]; a taken name gets a number) with
     * its main file `<name>.ink` holding [mainText].
     *
     * @throws IllegalArgumentException when nothing of [typedName] can be a folder name.
     */
    suspend fun create(typedName: String, mainText: String): ProjectRef.Internal {
        val clean = requireNotNull(ProjectNames.sanitize(typedName)) { "\"$typedName\" cannot be a project name" }
        val name = newFolder(clean)
        val ref = ProjectRef.Internal(name, ProjectNames.mainFileOf(name))
        store(ref).write(ref.mainPath, mainText)
        return ref
    }

    /** An empty internal project folder named after [typedName] (unique), for a copy to fill. */
    internal suspend fun createEmpty(typedName: String, mainPath: String): ProjectRef.Internal =
        ProjectRef.Internal(newFolder(ProjectNames.sanitize(typedName) ?: FALLBACK_NAME), mainPath)

    /** Creates `root/<clean or a numbered variant>` and returns the name used. */
    private suspend fun newFolder(clean: String): String = withContext(io) {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("$root could not be created")
        val name = ProjectNames.unique(clean, root.list().orEmpty().toList())
        if (!File(root, name).mkdir()) throw IOException("the project folder $name could not be created")
        name
    }

    /** Removes the project and every file in it. */
    suspend fun delete(ref: ProjectRef.Internal) {
        withContext(io) {
            val folder = folderOf(ref)
            if (folder.exists() && !folder.deleteRecursively()) throw IOException("${ref.name} could not be deleted completely")
        }
    }

    /** The store of [ref]'s folder. */
    fun store(ref: ProjectRef.Internal): ProjectStore = storeFactory(folderOf(ref), ref.name)

    /** `null` when the project and its main file exist, otherwise [StorageProblem.NotFound]. */
    suspend fun checkAccess(ref: ProjectRef.Internal): StorageProblem? = withContext(io) {
        if (File(folderOf(ref), ref.mainPath).isFile) null else StorageProblem.NotFound
    }

    internal fun folderOf(ref: ProjectRef.Internal): File {
        require(ref.name.isNotEmpty() && '/' !in ref.name && ref.name != "." && ref.name != "..") { "not a project name: ${ref.name}" }
        return File(root, ref.name)
    }

    private fun mainOf(folder: File): String? {
        val preferred = ProjectNames.mainFileOf(folder.name)
        if (File(folder, preferred).isFile) return preferred
        val ink = folder.walkTopDown()
            .onEnter { it == folder || !it.name.startsWith('.') }
            .filter { it.isFile && MainFileCandidates.isInk(it.name) }
            .map { it.relativeTo(folder).invariantSeparatorsPath }
            .toList()
        return MainFileCandidates.find(ink) { path ->
            try {
                DocumentIo.decodeText(File(folder, path).readBytes(), path)
            } catch (e: IOException) {
                Log.w(LOG_TAG, "internal project ${folder.name}: $path unreadable: ${e.message}")
                ""
            }
        }.firstOrNull()
    }

    private companion object {
        /** The folder name of a copied project whose name has nothing usable (a folder called "..."). */
        const val FALLBACK_NAME = "Inky"
    }
}
