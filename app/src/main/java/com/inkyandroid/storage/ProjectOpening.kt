package com.inkyandroid.storage

import android.net.Uri

/** A folder the user picked, before a main file is known. */
data class PickedFolder(
    /** The granted tree. */
    val treeUri: String,
    /** The folder's document id inside [treeUri]. */
    val rootDocumentId: String,
    /** The folder's name. */
    val displayName: String,
) {
    /** The project in this folder with [mainPath] as its main file. */
    fun project(mainPath: String) = ProjectRef.Saf(treeUri, rootDocumentId, mainPath, displayName)
}

/** What [ProjectStorage.openFolder] found in a picked folder. */
sealed interface FolderOpening {
    /** One main file (or the one remembered for this folder): open [ref]. */
    data class Opened(val ref: ProjectRef.Saf) : FolderOpening

    /**
     * Several files could be the main one (none of them is included by another): the UI asks
     * which, then calls [ProjectStorage.chooseMain], which remembers the answer for the folder.
     *
     * @property candidates root-relative paths of the folder's top-level `.ink` files that no
     *   other file includes, sorted by name.
     */
    data class ChooseMain(val folder: PickedFolder, val candidates: List<String>) : FolderOpening

    /** The folder holds no `.ink` file directly: the UI can offer [ProjectStorage.createProjectIn]. */
    data class NoInkFiles(val folder: PickedFolder) : FolderOpening

    /** The folder could not be read. */
    data class Failed(val problem: StorageProblem, val cause: Throwable) : FolderOpening
}

/** What [ProjectStorage.openFile] made of a single file. */
sealed interface FileOpening {
    /**
     * The file lies in a folder the app may already use: open [ref], the project rooted at the
     * file's folder with the file as its main file. `INCLUDE`s resolve.
     */
    data class InFolder(val ref: ProjectRef.Saf) : FileOpening

    /**
     * The file alone: its folder is not granted. `INCLUDE`s cannot be resolved; the UI shows a
     * banner that offers to grant the folder (the tree picker started at [grantHint]), and calls
     * [ProjectStorage.upgradeToFolder] with the picked tree.
     *
     * @property writable `false` when the file was shared read-only (saving will fail with
     *   [ReadOnlyException]; the banner says so, and granting the folder gives write access).
     * @property remembered whether the permission survives a restart (a file from the system's
     *   picker: yes; a file another app sent with `VIEW`: usually not). Only remembered files go to
     *   the recent projects.
     * @property grantHint where the folder picker should start: the file's folder when it can be
     *   worked out, else the file itself (pickers open its folder then).
     */
    data class Single(
        val ref: ProjectRef.SingleDocument,
        val writable: Boolean,
        val remembered: Boolean,
        val grantHint: Uri?,
    ) : FileOpening

    /** The file is not text (an image, a file in another encoding): Inky does not open it. */
    data class NotText(val name: String) : FileOpening

    /** The file could not be read. */
    data class Failed(val problem: StorageProblem, val cause: Throwable) : FileOpening
}

/** What [ProjectStorage.upgradeToFolder] made of the folder the user granted for a single file. */
sealed interface FolderUpgrade {
    /** The file was found in the picked folder: open [ref] instead of the single-file project. */
    data class Upgraded(val ref: ProjectRef.Saf) : FolderUpgrade

    /** The picked folder does not hold the file directly (the user picked another folder): the UI asks again. */
    data object NotContained : FolderUpgrade

    /** The folder could not be read. */
    data class Failed(val problem: StorageProblem, val cause: Throwable) : FolderUpgrade
}

/** What [ProjectStorage.copyToFolder] and [ProjectStorage.copyToInternal] did. */
data class ProjectCopy(
    /** The new project. */
    val ref: ProjectRef,
    /** Root-relative paths of files that were not copied because they are not text (images, audio): copy those by hand. */
    val skipped: List<String>,
)
