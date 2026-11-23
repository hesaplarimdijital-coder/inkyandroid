package com.inkyandroid.ui

import android.content.res.Resources
import android.net.Uri
import com.inkyandroid.R
import com.inkyandroid.core.engine.InkIssue
import com.inkyandroid.core.engine.SourceLocation
import com.inkyandroid.core.project.AddIncludeResult
import com.inkyandroid.core.project.DeleteResult
import com.inkyandroid.core.project.NewProject
import com.inkyandroid.core.project.ProjectPaths
import com.inkyandroid.core.project.RenameResult
import com.inkyandroid.core.symbols.FileSymbols
import com.inkyandroid.core.symbols.FlowSymbol
import com.inkyandroid.core.symbols.InkProjectSymbols
import com.inkyandroid.ink.InkLink
import com.inkyandroid.session.CloseAnswer
import com.inkyandroid.session.ProjectSession
import com.inkyandroid.session.ProjectSessionHolder
import com.inkyandroid.session.TextPosition
import com.inkyandroid.settings.AppSettings
import com.inkyandroid.settings.InspectorTab
import com.inkyandroid.storage.FileOpening
import com.inkyandroid.storage.FolderOpening
import com.inkyandroid.storage.FolderUpgrade
import com.inkyandroid.storage.PickedFolder
import com.inkyandroid.storage.ProjectRef
import com.inkyandroid.storage.ProjectStorage
import com.inkyandroid.storage.StorageContracts
import com.inkyandroid.storage.StorageProblem
import desktopkit.components.BannerTone
import desktopkit.components.ToastResult
import desktopkit.components.ToastState
import desktopkit.input.CommonShortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

/** What a folder is being picked for; the system's picker answers later, and to whichever window asked. */
sealed interface FolderPurpose {
    /** "Open folder…": the folder is a project. */
    data object OpenProject : FolderPurpose

    /** The folder of the single file that is open, so that its includes load. */
    data class Grant(val single: ProjectRef.SingleDocument) : FolderPurpose

    /** The parent folder of a new project named [name]. */
    data class NewProjectIn(val name: String) : FolderPurpose

    /** Where an internal project is copied out to. */
    data class CopyOut(val ref: ProjectRef.Internal) : FolderPurpose
}

/**
 * What a window does: the flows behind its commands (opening and creating projects, the files of
 * a project, saving, the jumps) with their notices, written once for the menu bar, the "⋯" sheet,
 * the palette, the keys and the screens' own buttons.
 *
 * The composition gives it what only a composition has: the way to another screen ([navigate]),
 * the system's pickers ([launchFolderPicker], [launchFilePicker]) and the text zoom ([zoom]).
 */
class WindowActions(
    val holder: ProjectSessionHolder,
    val settings: AppSettings,
    val storage: ProjectStorage,
    val ui: WindowUi,
    private val scope: CoroutineScope,
    private val toasts: ToastState,
    private val resources: () -> Resources,
) {
    /** Shows the screen of a route. */
    var navigate: (String) -> Unit = {}

    /** The route of the screen in front. */
    var currentRoute: () -> String? = { null }

    /** Opens the system's folder picker at a location; its answer comes back through [folderPicked]. */
    var launchFolderPicker: (Uri?) -> Unit = {}

    /** Opens the system's picker for an ink file; its answer comes back through [filePicked]. */
    var launchFilePicker: (Uri?) -> Unit = {}

    /** Steps the zoom of the text in front: +1 larger, -1 smaller, 0 back to the theme's size. False when no text is in front. */
    var zoom: (Int) -> Boolean = { false }

    /** Shows or hides the inspector beside the editor; false where the window has none. */
    var toggleInspector: () -> Boolean = { false }

    /** The exports and the statistics of the open project; null while none is open. */
    var extras: WindowExtras? = null

    /** Opens the command palette ("Go to anything") with [query] typed into it, showing only [group] when one is given. */
    var openPalette: (query: String, group: String?) -> Unit = { _, _ -> }

    /** Opens another window of the app; null where the platform gives none (a test without an activity). */
    var newWindow: (() -> Unit)? = null

    /** Shows or hides the sidebar of a large window; false where the window has none. */
    var toggleSidebar: () -> Boolean = { false }

    private var folderPurpose: FolderPurpose = FolderPurpose.OpenProject

    val session: ProjectSession? get() = holder.session

    private val strings: Resources get() = resources()

    fun notify(message: String, tone: BannerTone? = null) {
        scope.launch { toasts.show(message, tone = tone) }
    }

    // ------------------------------------------------------------------ places

    /** Shows the project file at [path] in the editor, as it was left. */
    fun openFile(path: String) {
        if (session?.show(path) == true) showEditor()
    }

    /** Shows the place an issue names; an issue without a file leaves the editor where it is, as in desktop Inky. */
    fun openIssue(issue: InkIssue) {
        if (session?.showIssue(issue) == true) showEditor()
    }

    /** Shows the ink behind a line of the story. Desktop Inky adds no history step for it; neither is one added here. */
    fun openSource(place: SourceLocation) {
        if (session?.show(place.file, TextPosition((place.line - 1).coerceAtLeast(0)), addStep = false) == true) showEditor()
    }

    /** Moves the cursor to a knot or stitch of the outline (no history step: upstream's rule for its knots view). */
    fun openSymbol(path: String, symbol: FlowSymbol, addStep: Boolean = false) {
        if (session?.show(path, TextPosition(symbol.row, symbol.column), addStep = addStep) == true) showEditor()
    }

    /** Shows line [row] (zero-based) of [path]: a result of "Go to anything". */
    fun openLine(path: String, row: Int, column: Int = 0) {
        if (session?.show(path, TextPosition(row, column)) == true) showEditor()
    }

    private fun showEditor() {
        if (!Routes.isWorkspace(currentRoute())) navigate(Routes.WRITE)
    }

    /**
     * Follows a link of the ink as desktop Inky's Alt+click does: a divert's target to its
     * definition (searched from the cursor's own knot outwards, then in every file), an include
     * path to its file. Says so when there is nothing to go to; upstream stays silent, which on a
     * touch screen reads as a tap that did not register.
     */
    fun follow(link: InkLink, fromPath: String, fromRow: Int) {
        val session = session ?: return
        when (link) {
            is InkLink.Include -> {
                val path = link.path
                if (path != null && path in session.project) openFile(path) else notify(strings.getString(R.string.editor_no_include, link.written.trim()))
            }
            is InkLink.Divert -> {
                val files = session.project.state.value.files.mapNotNull { file -> file.symbols?.let { FileSymbols(file.path, it) } }
                val found = InkProjectSymbols.findSymbol(link.target, fromPath, fromRow, files)
                if (found != null) openSymbol(found.path, found.symbol, addStep = true) else notify(strings.getString(R.string.editor_no_definition, link.target))
            }
        }
    }

    /** "Go to definition" for the link under the cursor of the editor on screen. */
    fun gotoDefinition(): Boolean {
        val editor = ui.editor ?: return false
        val link = editor.linkAtCursor()
        if (link == null) notify(strings.getString(R.string.editor_not_a_link)) else follow(link, editor.path, editor.cursor.row)
        return true
    }

    // ------------------------------------------------------------------ the files of the project

    /** The new include form's "Add": desktop Inky's rules, and its message when the name is taken. */
    fun addInclude(name: String) {
        val session = session ?: return
        scope.launch {
            when (val result = session.addInclude(name, ui.addIncludeToMain)) {
                is AddIncludeResult.Added -> {
                    ui.dialog = null
                    showEditor()
                }
                is AddIncludeResult.AlreadyExists -> ui.dialog = WindowDialog.NewInclude(strings.getString(R.string.include_exists, result.path))
                is AddIncludeResult.InvalidName -> ui.dialog = WindowDialog.NewInclude(strings.getString(R.string.include_invalid))
            }
        }
    }

    /** Creates the file an `INCLUDE` line names and storage does not have: empty, shown in the editor. */
    fun createMissing(path: String) {
        val session = session ?: return
        scope.launch {
            when (val result = session.addInclude(path, addToMain = false)) {
                is AddIncludeResult.Added -> {
                    notify(strings.getString(R.string.include_added, result.path))
                    showEditor()
                }
                is AddIncludeResult.AlreadyExists -> notify(strings.getString(R.string.include_exists, result.path))
                is AddIncludeResult.InvalidName -> notify(strings.getString(R.string.include_invalid))
            }
        }
    }

    fun rename(path: String, newName: String) {
        val session = session ?: return
        scope.launch {
            when (val result = session.rename(path, newName)) {
                is RenameResult.Renamed -> {
                    ui.dialog = null
                    notify(strings.getString(R.string.rename_done, ProjectPaths.name(result.to)))
                }
                is RenameResult.AlreadyExists -> ui.dialog = WindowDialog.Rename(path, strings.getString(R.string.rename_exists, ProjectPaths.name(result.path)))
                is RenameResult.InvalidName -> ui.dialog = WindowDialog.Rename(path, strings.getString(R.string.include_invalid))
                RenameResult.IsMainFile -> ui.dialog = WindowDialog.Rename(path, strings.getString(R.string.rename_main))
                is RenameResult.NotInProject -> ui.dialog = null
                is RenameResult.Failed -> ui.dialog = WindowDialog.Rename(path, strings.getString(R.string.rename_failed, describe(result.error)))
            }
        }
    }

    /**
     * Deletes a file of the project. Its text is kept for as long as the notice stands: "Undo" puts
     * the file back as it was (the `INCLUDE` lines that name it were never touched).
     */
    fun delete(path: String) {
        val session = session ?: return
        ui.dialog = null
        scope.launch {
            val kept = session.project.text(path)?.toString()
            when (val result = session.delete(path)) {
                is DeleteResult.Deleted -> {
                    val name = ProjectPaths.name(path)
                    val answer = toasts.show(
                        strings.getString(R.string.delete_done, name),
                        actionLabel = if (kept != null) strings.getString(desktopkit.R.string.dk_undo) else null,
                    )
                    if (answer == ToastResult.ActionPerformed && kept != null) restoreDeleted(path, kept)
                }
                DeleteResult.IsMainFile -> notify(strings.getString(R.string.delete_main))
                is DeleteResult.NotInProject -> Unit
                is DeleteResult.Failed -> notify(strings.getString(R.string.delete_failed, describe(result.error)), BannerTone.Error)
            }
        }
    }

    private suspend fun restoreDeleted(path: String, kept: String) {
        // the session that deleted it may be gone by the time "Undo" is tapped
        val session = session ?: return
        if (session.addInclude(path, addToMain = false) is AddIncludeResult.Added && kept.isNotEmpty()) {
            session.document(path)?.buffer?.edit(0, 0, kept)
        }
    }

    // ------------------------------------------------------------------ saving

    /** "Save project": every file that differs from storage, the main file first. Says what came of it. */
    fun save(): Boolean {
        val session = session ?: return false
        scope.launch {
            val report = session.saveAll()
            when {
                // a failure and a conflict have their banners; the notice would only repeat them
                !report.isComplete -> Unit
                report.results.values.all { it is com.inkyandroid.core.project.SaveResult.NothingToSave } -> notify(strings.getString(R.string.save_nothing))
                else -> notify(strings.getString(R.string.save_done))
            }
        }
        return true
    }

    /** "Reload from storage": what changed outside comes in, by the model's rules. */
    fun reload(): Boolean {
        val session = session ?: return false
        scope.launch {
            val report = session.syncWithStorage() ?: return@launch
            val changed = report.reloaded.size + report.added.size + report.removed.size
            notify(if (changed == 0) strings.getString(R.string.reload_done) else strings.getQuantityString(R.plurals.reload_report, changed, changed))
        }
        return true
    }

    // ------------------------------------------------------------------ projects

    /**
     * Shows [ref] in this window. The open project is asked to close first: with autosave it saves
     * and goes; without, the user is asked desktop Inky's question, and the new project opens once
     * that is answered with anything but "Cancel".
     */
    fun openProject(ref: ProjectRef, notice: SingleFileNotice? = null) {
        // the project that is open (the Projects screen marks it so): nothing to close, nothing to ask
        if (holder.reference == ref.encode() && session != null) {
            navigate(Routes.WRITE)
            return
        }
        whenClosed {
            ui.singleFile = notice
            holder.open(ref.encode())
            navigate(Routes.WRITE)
        }
    }

    /** "Close project". */
    fun closeProject(): Boolean {
        if (session == null) return false
        whenClosed {
            ui.singleFile = null
            holder.closed()
        }
        return true
    }

    private fun whenClosed(then: () -> Unit) {
        val open = session
        if (open == null) {
            then()
            return
        }
        scope.launch {
            if (open.requestClose()) then() else ui.afterClose = then
        }
    }

    /** The answer to "Would you like to save changes before exiting?". */
    fun answerClose(answer: CloseAnswer) {
        val open = session ?: return
        scope.launch {
            val closed = open.answerClose(answer)
            val then = ui.afterClose
            ui.afterClose = null
            if (closed) then?.invoke()
        }
    }

    fun pickFolder(purpose: FolderPurpose) {
        folderPurpose = purpose
        launchFolderPicker(
            when (purpose) {
                is FolderPurpose.Grant -> ui.singleFile?.grantHint ?: StorageContracts.DOCUMENTS_HINT
                else -> StorageContracts.DOCUMENTS_HINT
            },
        )
    }

    /** The folder picker's answer (null: it was left without picking). */
    fun folderPicked(tree: Uri?) {
        if (tree == null) return
        val purpose = folderPurpose
        scope.launch {
            try {
                when (purpose) {
                    FolderPurpose.OpenProject -> opened(storage.openFolder(tree))
                    is FolderPurpose.Grant -> granted(storage.upgradeToFolder(purpose.single, tree), purpose.single)
                    is FolderPurpose.NewProjectIn -> createIn(storage.folderFor(tree), purpose.name)
                    is FolderPurpose.CopyOut -> {
                        val copy = storage.copyToFolder(purpose.ref, tree)
                        val message = if (copy.skipped.isEmpty()) {
                            strings.getString(R.string.copy_done, copy.ref.displayName)
                        } else {
                            strings.getString(R.string.copy_skipped, copy.ref.displayName, copy.skipped.size)
                        }
                        // the copy is the project from now on, where it is the one that is open
                        if (holder.reference == purpose.ref.encode()) holder.switchTo(copy.ref.encode())
                        notify(message)
                    }
                }
            } catch (failed: IOException) {
                notify(StorageProblem.of(failed).message(strings), BannerTone.Error)
            }
        }
    }

    private fun opened(opening: FolderOpening) {
        when (opening) {
            is FolderOpening.Opened -> openProject(opening.ref)
            is FolderOpening.ChooseMain -> ui.dialog = WindowDialog.ChooseMain(opening.folder, opening.candidates)
            is FolderOpening.NoInkFiles -> ui.dialog = WindowDialog.NewProject(opening.folder)
            is FolderOpening.Failed -> notify(opening.problem.message(strings), BannerTone.Error)
        }
    }

    /** The answer to "Which file is the main ink file?"; storage remembers it for the folder. */
    fun chooseMain(folder: PickedFolder, mainPath: String) {
        ui.dialog = null
        openProject(storage.chooseMain(folder, mainPath))
    }

    /** The file picker's answer, and a file another app handed over. */
    fun filePicked(uri: Uri?) {
        if (uri == null) return
        scope.launch {
            when (val opening = storage.openFile(uri)) {
                is FileOpening.InFolder -> openProject(opening.ref)
                is FileOpening.Single -> openProject(opening.ref, SingleFileNotice(opening.ref, readOnly = !opening.writable, grantHint = opening.grantHint))
                is FileOpening.NotText -> notify(strings.getString(R.string.not_text, opening.name), BannerTone.Error)
                is FileOpening.Failed -> notify(opening.problem.message(strings), BannerTone.Error)
            }
        }
    }

    private fun granted(upgrade: FolderUpgrade, single: ProjectRef.SingleDocument) {
        when (upgrade) {
            is FolderUpgrade.Upgraded -> {
                ui.singleFile = null
                // the same project from its folder: unsaved text moves along
                holder.switchTo(upgrade.ref.encode())
            }
            FolderUpgrade.NotContained -> notify(strings.getString(R.string.single_file_wrong_folder, single.mainPath))
            is FolderUpgrade.Failed -> notify(upgrade.problem.message(strings), BannerTone.Error)
        }
    }

    /**
     * The new project form's "Create": in [parent] when a folder was picked for it, else in the
     * projects folder, else inside the app.
     */
    fun createProject(name: String, parent: PickedFolder?, internal: Boolean) {
        ui.dialog = null
        scope.launch {
            try {
                val ref = when {
                    parent != null -> storage.createProjectIn(parent, name, NewProject.MAIN_TEXT)
                    internal -> storage.internalProjects.create(name, NewProject.MAIN_TEXT)
                    else -> storage.createProject(name, NewProject.MAIN_TEXT)
                }
                openProject(ref)
            } catch (failed: IOException) {
                notify(StorageProblem.of(failed).message(strings), BannerTone.Error)
            }
        }
    }

    private suspend fun createIn(parent: PickedFolder, name: String) {
        openProject(storage.createProjectIn(parent, name, NewProject.MAIN_TEXT))
    }

    /**
     * The app's very first start: nothing was open before, nothing is recent, nothing is kept inside
     * the app. A sample project opens, so there is something to write in and play at once (upstream
     * starts every launch with a new project of its "Once upon a time…" text; so does this one).
     */
    fun openSampleOnFirstLaunch() {
        if (holder.project.value != com.inkyandroid.session.WindowProject.None) return
        scope.launch {
            try {
                if (storage.recents.entries.value.isNotEmpty() || storage.internalProjects.list().isNotEmpty()) return@launch
                val ref = storage.internalProjects.create(strings.getString(R.string.sample_project_name), NewProject.MAIN_TEXT)
                if (holder.project.value == com.inkyandroid.session.WindowProject.None) holder.open(ref.encode())
            } catch (failed: IOException) {
                notify(StorageProblem.of(failed).message(strings), BannerTone.Error)
            }
        }
    }

    fun removeRecent(ref: ProjectRef) {
        scope.launch { storage.recents.remove(ref) }
    }

    fun clearRecents() {
        scope.launch { storage.recents.clear() }
    }

    /** Deletes a project that is kept inside the app, after the question was answered. */
    fun deleteInternal(ref: ProjectRef.Internal) {
        ui.dialog = null
        scope.launch {
            try {
                if (holder.reference == ref.encode()) holder.closed()
                storage.recents.remove(ref)
                storage.internalProjects.delete(ref)
            } catch (failed: IOException) {
                notify(StorageProblem.of(failed).message(strings), BannerTone.Error)
            }
        }
    }

    // ------------------------------------------------------------------ the story

    fun addWatch(): Boolean {
        val session = session ?: return false
        session.setWatches(session.state.value.watches + com.inkyandroid.ui.workspace.DEFAULT_WATCH)
        ui.focusNewestWatch = true
        if (ui.twoPanes && Routes.isWorkspace(currentRoute())) {
            settings.updateInspectorTab(InspectorTab.Watches)
            if (settings.inspectorCollapsed) toggleInspector()
        } else {
            ui.dialog = WindowDialog.Watches
        }
        return true
    }

    /** Shows a tab of the inspector, or the screen that holds the same where the window has no inspector. */
    fun showTab(tab: InspectorTab): Boolean {
        if (session == null) return false
        if (ui.twoPanes) {
            settings.updateInspectorTab(tab)
            if (settings.inspectorCollapsed) toggleInspector()
            showEditor()
            return true
        }
        when (tab) {
            InspectorTab.Player -> navigate(Routes.PLAY)
            InspectorTab.Issues -> navigate(Routes.ISSUES)
            InspectorTab.Outline -> {
                ui.filesTab = 1
                navigate(Routes.FILES)
            }
            InspectorTab.Watches -> ui.dialog = WindowDialog.Watches
        }
        return true
    }

    private fun describe(error: IOException): String = StorageProblem.of(error).message(strings)

    // ------------------------------------------------------------------ commands

    /**
     * Inserts [text] at the cursor of the open file as upstream inserts a snippet: replacing the
     * selection, as typed, one step to undo. With no editor on screen (the manual is in front on a
     * phone) it goes in at the file's cursor all the same, and a notice says where it went.
     */
    fun insertSnippet(text: String) {
        val session = session ?: return
        val editor = ui.editor?.takeIf { Routes.isWorkspace(currentRoute()) }
        if (editor != null) {
            editor.insert(text)
            editor.focus()
            return
        }
        val document = session.activeDocument
        val buffer = document.buffer
        val offset = offsetOf(buffer.text, buffer.cursor)
        buffer.edit(offset, offset, text)
        scope.launch {
            val answer = toasts.show(strings.getString(R.string.snippet_inserted, ProjectPaths.name(document.path)), actionLabel = strings.getString(R.string.action_show))
            if (answer == ToastResult.ActionPerformed) navigate(Routes.WRITE)
        }
    }

    /** The snippets in the palette, those of [category] (its label) first when one is given: the way to them until the kit's menus nest. */
    fun openSnippets(category: String? = null): Boolean {
        if (session == null) return false
        openPalette(category.orEmpty(), strings.getString(R.string.snippets_group))
        return true
    }

    /** Inserts the custom snippet whose key [event] is (the project's settings give it one); false when none has it. */
    fun performSnippetKey(event: android.view.KeyEvent): Boolean {
        val custom = session?.state?.value?.projectSettings?.customInkSnippets ?: return false
        val pressed = androidx.compose.ui.input.key.KeyEvent(event)
        val snippet = snippetKeys(custom).firstOrNull { it.chord.matches(pressed) }?.snippet ?: return false
        insertSnippet(snippet.ink)
        return true
    }

    /** Whether this build has the command [id] at all; a command it lacks is listed nowhere. */
    fun available(id: String): Boolean = when (id) {
        // the kit's editor brings its line commands with its next build
        Commands.TOGGLE_COMMENT -> false
        Commands.EXPORT_JSON, Commands.EXPORT_WEB, Commands.EXPORT_JS, Commands.STATISTICS -> extras != null || session == null
        CommonShortcuts.NEW_WINDOW -> newWindow != null
        else -> true
    }

    /** Runs the command [id]; false when there is nothing for it to do right now (the key is then left to the focused view). */
    fun perform(id: String): Boolean {
        val session = session
        val editor = ui.editor?.takeIf { Routes.isWorkspace(currentRoute()) }
        return when (id) {
            Commands.NEW_PROJECT -> {
                ui.dialog = WindowDialog.NewProject()
                true
            }
            Commands.NEW_INCLUDE -> session != null && run {
                ui.dialog = WindowDialog.NewInclude()
                true
            }
            Commands.OPEN_FOLDER -> {
                pickFolder(FolderPurpose.OpenProject)
                true
            }
            Commands.OPEN_FILE -> {
                launchFilePicker(StorageContracts.DOCUMENTS_HINT)
                true
            }
            Commands.SAVE -> save()
            Commands.RELOAD -> reload()
            Commands.CLOSE_PROJECT -> closeProject()
            Commands.EXPORT_JSON -> extras?.exportJson?.let { it(); true } ?: false
            Commands.EXPORT_WEB -> extras?.exportWeb?.let { it(); true } ?: false
            Commands.EXPORT_JS -> extras?.exportJs?.let { it(); true } ?: false
            Commands.STATISTICS -> session != null && extras?.statistics == true && run {
                ui.dialog = WindowDialog.Statistics
                true
            }
            CommonShortcuts.NEW_WINDOW -> newWindow?.let { it(); true } ?: false

            Commands.UNDO -> editor?.undo() != null
            Commands.REDO -> editor?.redo() != null
            Commands.CUT -> editor?.cut() != null
            Commands.COPY -> editor?.copy() != null
            Commands.PASTE -> editor?.paste() != null
            Commands.SELECT_ALL -> editor?.selectAll() != null
            Commands.FIND -> editor?.find(replace = false) != null
            Commands.REPLACE -> editor?.find(replace = true) != null
            Commands.COMPLETE -> editor?.showCompletion() != null

            Commands.ZOOM_IN -> zoom(1)
            Commands.ZOOM_OUT -> zoom(-1)
            Commands.ZOOM_RESET -> zoom(0)
            Commands.TOGGLE_INSPECTOR -> toggleInspector()
            Commands.SHOW_PLAYER -> showTab(InspectorTab.Player)
            Commands.SHOW_ISSUES -> showTab(InspectorTab.Issues)
            Commands.SHOW_OUTLINE -> showTab(InspectorTab.Outline)
            Commands.SHOW_WATCHES -> showTab(InspectorTab.Watches)
            CommonShortcuts.TOGGLE_SIDEBAR -> toggleSidebar()
            CommonShortcuts.SEARCH, Commands.GOTO_ANYTHING -> {
                openPalette("", null)
                true
            }
            // upstream's "go to line" prompt is "Go to anything" with a number: it lists that line first
            Commands.GOTO_LINE -> session != null && run {
                openPalette("", null)
                true
            }
            CommonShortcuts.SETTINGS -> {
                navigate(Routes.SETTINGS)
                true
            }
            CommonShortcuts.SHORTCUT_LIST -> {
                ui.dialog = WindowDialog.Shortcuts
                true
            }
            Commands.INSERT_SNIPPET -> openSnippets()
            Commands.OPEN_RECENT -> {
                navigate(Routes.PROJECTS)
                true
            }
            Commands.DOCUMENTATION -> {
                navigate(Routes.DOCS)
                true
            }

            Commands.GOTO_DEFINITION -> session != null && gotoDefinition()
            Commands.NEXT_ISSUE -> session?.nextIssue()?.also { moved -> if (moved) showEditor() } ?: false
            Commands.BACK -> session?.takeIf { it.state.value.canGoBack }?.let {
                it.back()
                showEditor()
                true
            } ?: false
            Commands.FORWARD -> session?.takeIf { it.state.value.canGoForward }?.let {
                it.forward()
                showEditor()
                true
            } ?: false
            Commands.ADD_WATCH -> addWatch()
            Commands.STEP_BACK -> session?.live?.session?.takeIf { it.state.value.canStepBack }?.let {
                it.stepBack()
                true
            } ?: false
            Commands.RESTART -> session?.live?.session?.let {
                it.rewind()
                true
            } ?: false
            else -> false
        }
    }

    /** Whether the command [id] has something to do right now: what greys out a menu entry. */
    fun enabled(id: String): Boolean {
        val session = session
        val editor = ui.editor?.takeIf { Routes.isWorkspace(currentRoute()) }
        return when (id) {
            Commands.NEW_INCLUDE, Commands.SAVE, Commands.RELOAD, Commands.CLOSE_PROJECT, Commands.NEXT_ISSUE, Commands.ADD_WATCH,
            Commands.RESTART, Commands.SHOW_PLAYER, Commands.SHOW_ISSUES, Commands.SHOW_OUTLINE, Commands.SHOW_WATCHES, Commands.GOTO_LINE,
            Commands.INSERT_SNIPPET,
            -> session != null
            Commands.EXPORT_JSON, Commands.EXPORT_WEB, Commands.EXPORT_JS, Commands.STATISTICS -> session != null && extras != null
            Commands.UNDO -> editor?.canUndo == true
            Commands.REDO -> editor?.canRedo == true
            Commands.CUT, Commands.COPY, Commands.PASTE, Commands.SELECT_ALL, Commands.FIND, Commands.REPLACE, Commands.TOGGLE_COMMENT,
            Commands.COMPLETE, Commands.GOTO_DEFINITION,
            -> editor != null
            Commands.BACK -> session?.state?.value?.canGoBack == true
            Commands.FORWARD -> session?.state?.value?.canGoForward == true
            Commands.STEP_BACK -> session?.live?.session?.state?.value?.canStepBack == true
            Commands.TOGGLE_INSPECTOR -> ui.twoPanes && session != null
            else -> true
        }
    }
}

/** The offset of [position] in [text], its lines split as the editor splits them; clamped to the text. */
internal fun offsetOf(text: CharSequence, position: TextPosition): Int {
    var row = 0
    var index = 0
    while (row < position.row && index < text.length) {
        val char = text[index]
        index++
        if (char == '\n' || (char == '\r' && !(index < text.length && text[index] == '\n'))) row++
    }
    var column = 0
    while (column < position.column && index < text.length && text[index] != '\n' && text[index] != '\r') {
        index++
        column++
    }
    return index
}

/**
 * What the extras screens give the window's commands: the three exports, and whether the
 * statistics dialog exists. Null members are commands this build does not have.
 */
class WindowExtras(
    val exportJson: (() -> Unit)? = null,
    val exportWeb: (() -> Unit)? = null,
    val exportJs: (() -> Unit)? = null,
    val statistics: Boolean = false,
)
