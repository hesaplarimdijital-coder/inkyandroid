package com.inkyandroid.core.project

import com.inkyandroid.core.symbols.InkFileSymbols
import java.io.IOException
import java.text.Collator
import java.util.Locale

/**
 * One file of an open project as the rest of the app sees it. Immutable; a new one is published
 * whenever something in it changes (never for a mere keystroke: the text is not part of it).
 *
 * @property path root-relative, `/`-separated.
 * @property isMain the project's main ink file.
 * @property isSpare no chain of `INCLUDE` lines leads from the main file to this file (upstream's
 *   "unused files").
 * @property isOnStorage false for a file that exists only here: a new include that was never saved,
 *   or a file removed outside the app that had to be kept (it had unsaved changes, or it is the main
 *   file). Saving creates it.
 * @property hasUnsavedChanges the text was edited since it was last read from or written to
 *   storage, or the file is not on storage at all.
 * @property hasConflict storage holds a version the app did not write while this file has unsaved
 *   changes. Nothing overwrites either side until [InkProject.resolveConflict] is called.
 * @property includes the project paths its `INCLUDE` lines name, in order ([ProjectPaths.normalizeInclude]).
 * @property symbols the latest parse of the file (200 ms behind typing), null until the first one.
 */
data class ProjectFile(
    val path: String,
    val isMain: Boolean,
    val isSpare: Boolean,
    val isOnStorage: Boolean,
    val hasUnsavedChanges: Boolean,
    val hasConflict: Boolean,
    val includes: List<String>,
    val symbols: InkFileSymbols?,
) {
    /** The file's name without its folder. */
    val name: String get() = ProjectPaths.name(path)

    /** The file's folder, `""` in the project root. */
    val folder: String get() = ProjectPaths.folder(path)
}

/** The included files of one folder, as upstream's files view groups them. */
data class FileGroup(
    /** The folder's root-relative path; `""` for the project root, whose group comes first and has no title. */
    val folder: String,
    /** In include order (the order the main file reaches them). Shown by [ProjectFile.name]. */
    val files: List<ProjectFile>,
)

/**
 * The files of a project arranged as upstream's files view shows them (`navView.js: setFiles`):
 * the main file, then one group per folder of the included files with the groups sorted by folder
 * name, then the files nothing includes.
 */
data class ProjectTree(
    val main: ProjectFile,
    val groups: List<FileGroup>,
    /** Upstream's "Unused files", shown by their [ProjectFile.path]. */
    val spare: List<ProjectFile>,
)

/**
 * The state of an open project at one moment. Immutable.
 *
 * @property files every file: the main file, then the included files in include order, then the
 *   spare files by path.
 * @property missingIncludes paths that `INCLUDE` lines name and that are neither in the project nor
 *   on storage (the compiler reports these; nothing creates them).
 * @property loadErrors files that are on storage but could not be read, with the reason; they are
 *   not part of [files]. Cleared when a later attempt succeeds.
 */
data class ProjectSnapshot(
    val mainPath: String,
    val files: List<ProjectFile>,
    val missingIncludes: List<String> = emptyList(),
    val loadErrors: Map<String, String> = emptyMap(),
) {
    private val byPath: Map<String, ProjectFile> = files.associateBy { it.path }

    val main: ProjectFile get() = byPath.getValue(mainPath)

    fun file(path: String): ProjectFile? = byPath[path]

    /** The files with unsaved changes, in [files] order. */
    val unsavedFiles: List<ProjectFile> get() = files.filter { it.hasUnsavedChanges }

    val hasUnsavedChanges: Boolean get() = files.any { it.hasUnsavedChanges }

    /** The files waiting for [InkProject.resolveConflict]. */
    val conflicts: List<ProjectFile> get() = files.filter { it.hasConflict }

    /** The arrangement for the files view. */
    val tree: ProjectTree by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val included = files.filter { !it.isMain && !it.isSpare }
        val groups = included.groupBy { it.folder }
            .map { (folder, members) -> FileGroup(folder, members) }
            .sortedWith { a, b -> FOLDER_ORDER.compare(a.folder, b.folder) }
        ProjectTree(main, groups, files.filter { it.isSpare })
    }

    private companion object {
        // upstream sorts the groups with String.localeCompare; the root locale keeps it the same everywhere
        val FOLDER_ORDER: Comparator<String> = Collator.getInstance(Locale.ROOT).let { collator ->
            Comparator { a, b ->
                val byLocale = synchronized(collator) { collator.compare(a, b) }
                if (byLocale != 0) byLocale else a.compareTo(b)
            }
        }
    }
}

/** What "add new include" did. */
sealed interface AddIncludeResult {
    /** The file [path] is in the project, empty and unsaved; when asked for, the main file names it in a new `INCLUDE` line. */
    data class Added(val path: String) : AddIncludeResult

    /** A file with that path is already in the project or on storage; nothing was changed (upstream: "…because it already exists"). */
    data class AlreadyExists(val path: String) : AddIncludeResult

    /** The typed name cannot be a file inside the project (empty, absolute, or leaving the root). */
    data class InvalidName(val name: String) : AddIncludeResult
}

/** What a rename did. */
sealed interface RenameResult {
    /** The file moved from [from] to [to]; the files in [rewrittenIn] had `INCLUDE` lines changed to follow it and are now unsaved. */
    data class Renamed(val from: String, val to: String, val rewrittenIn: List<String>) : RenameResult

    data class AlreadyExists(val path: String) : RenameResult

    data class InvalidName(val name: String) : RenameResult

    /** The main file keeps its name: the project is known by it. */
    data object IsMainFile : RenameResult

    data class NotInProject(val path: String) : RenameResult

    /** Storage refused; nothing changed. */
    data class Failed(val error: IOException) : RenameResult
}

/** What a delete did. */
sealed interface DeleteResult {
    /** The file is gone from the project and from storage. `INCLUDE` lines that name it are left for the writer to see. */
    data class Deleted(val path: String) : DeleteResult

    /** The main file is never deleted. */
    data object IsMainFile : DeleteResult

    data class NotInProject(val path: String) : DeleteResult

    /** Storage refused; the file is still in the project. */
    data class Failed(val error: IOException) : DeleteResult
}

/** What saving one file did. */
sealed interface SaveResult {
    /** Storage now holds the text the file had when the save began. (Typing during the save leaves the file unsaved again.) */
    data object Saved : SaveResult

    /** There was nothing to write. */
    data object NothingToSave : SaveResult

    /**
     * Storage holds a version the app did not write, so nothing was written. The file is marked
     * [ProjectFile.hasConflict]; the user decides through [InkProject.resolveConflict].
     */
    data object Conflict : SaveResult

    /** The write failed. The file stays unsaved and its text untouched. */
    data class Failed(val error: IOException) : SaveResult
}

/** What "save all" did, file by file (only files that had something to save are listed). */
data class SaveReport(val results: Map<String, SaveResult>) {
    val failed: Map<String, IOException>
        get() = results.mapNotNull { (path, result) -> (result as? SaveResult.Failed)?.let { path to it.error } }.toMap()

    val conflicts: List<String> get() = results.filterValues { it is SaveResult.Conflict }.keys.toList()

    /** Nothing is left unsaved by this save. */
    val isComplete: Boolean get() = results.values.all { it is SaveResult.Saved || it is SaveResult.NothingToSave }
}

/**
 * What a look at storage found and did ([InkProject.syncWithStorage]). Every list holds paths.
 *
 * @property reloaded changed outside and had no unsaved changes: the new text was taken silently
 *   (upstream's rule). The UI keeps cursor and scroll.
 * @property added new `.ink` files found under the root; they joined the project (as spare files
 *   unless something includes them).
 * @property removed gone from storage, had no unsaved changes and were not the main file: they left
 *   the project.
 * @property conflicts changed outside while they have unsaved changes: newly marked
 *   [ProjectFile.hasConflict]. Both versions are intact.
 * @property keptMissing gone from storage but kept, because they have unsaved changes or are the
 *   main file; they are now not on storage and saving re-creates them.
 * @property settingsChanged the project settings file was added, changed or removed.
 * @property errors files that could not be read, with the reason.
 */
data class SyncReport(
    val reloaded: List<String> = emptyList(),
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
    val keptMissing: List<String> = emptyList(),
    val settingsChanged: Boolean = false,
    val errors: Map<String, String> = emptyMap(),
) {
    /** Nothing differed from what the project already knew. */
    val isEmpty: Boolean
        get() = reloaded.isEmpty() && added.isEmpty() && removed.isEmpty() && conflicts.isEmpty() &&
            keptMissing.isEmpty() && !settingsChanged && errors.isEmpty()
}
