package com.inkyandroid.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.inkyandroid.storage.PickedFolder
import com.inkyandroid.storage.ProjectRef
import com.inkyandroid.ui.workspace.ActiveEditor

/** A question or a form the window has up over its screen. One at a time. */
sealed interface WindowDialog {
    /** Desktop Inky's "Add new include" form; [problem] is what the last attempt came to. */
    data class NewInclude(val problem: String? = null) : WindowDialog

    data class Rename(val path: String, val problem: String? = null) : WindowDialog

    data class Delete(val path: String) : WindowDialog

    /** The new project form; [parent] is a folder that was picked for it (an empty folder that was opened). */
    data class NewProject(val parent: PickedFolder? = null) : WindowDialog

    /** A folder with several files that could be the main one: which is it? */
    data class ChooseMain(val folder: PickedFolder, val candidates: List<String>) : WindowDialog

    /** An internal project is about to be deleted for good. */
    data class DeleteProject(val ref: ProjectRef.Internal) : WindowDialog

    /** "Word count and more". */
    data object Statistics : WindowDialog

    /** The watch expressions as a sheet, where the window has no inspector to show them in. */
    data object Watches : WindowDialog

    /** The list of keyboard shortcuts (upstream's "Useful Keyboard Shortcuts"). */
    data object Shortcuts : WindowDialog
}

/**
 * What one window shows beyond its project: the scroll positions of its panes, the dialog that is
 * up, what the last forms were left at. It lives as long as the window (the activity keeps it), so
 * a screen that goes and comes back (another tab, a window resized across a layout) is where it was.
 */
@Stable
class WindowUi {
    /** The player's list: one for the window, whether the player is a screen of its own or a tab beside the editor. */
    val playerList = LazyListState()
    val issuesList = LazyListState()
    val outlineList = LazyListState()
    val filesList = LazyListState()

    var dialog: WindowDialog? by mutableStateOf(null)

    /** "Add to main ink" of the new include form: kept between its openings, as desktop Inky keeps it. */
    var addIncludeToMain by mutableStateOf(true)

    /** The newest watch expression wants the keyboard (it was just added from a menu). */
    var focusNewestWatch by mutableStateOf(false)

    /** Whether the editor and the player fit side by side in this window: the workspace measures, the navigation follows. */
    var twoPanes by mutableStateOf(false)

    /** The tab of the Files screen on a phone: 0 the files, 1 the outline. */
    var filesTab by mutableIntStateOf(0)

    /** The editor on screen, for the commands that act on it; null while none is. */
    var editor: ActiveEditor? by mutableStateOf(null)

    /** What to do once the open project has been closed (the user answered the question about unsaved changes). */
    var afterClose: (() -> Unit)? = null

    /** A project of one file whose folder is not granted: what the banner that offers to grant it needs. */
    var singleFile: SingleFileNotice? by mutableStateOf(null)

    /** An .ink file another app handed over, to be opened as a project. */
    var pendingFile: android.net.Uri? by mutableStateOf(null)

    /** The window was just started (not recreated): with nothing to show, a first launch gets its sample project. */
    var freshStart = false
}

/** The open project is a single file: [readOnly] when even that could not be opened for writing; [dismissed] once the banner was closed. */
data class SingleFileNotice(val ref: ProjectRef.SingleDocument, val readOnly: Boolean, val grantHint: android.net.Uri?, val dismissed: Boolean = false)
