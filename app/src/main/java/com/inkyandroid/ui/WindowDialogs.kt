package com.inkyandroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.inkyandroid.R
import com.inkyandroid.session.ProjectSession
import com.inkyandroid.session.SessionState
import com.inkyandroid.storage.ProjectStorage
import com.inkyandroid.ui.extras.StatisticsDialog
import com.inkyandroid.ui.files.DeleteFileDialog
import com.inkyandroid.ui.files.NewIncludeDialog
import com.inkyandroid.ui.files.RenameDialog
import com.inkyandroid.ui.projects.ChooseMainDialog
import com.inkyandroid.ui.projects.CloseQuestionDialog
import com.inkyandroid.ui.projects.DeleteProjectDialog
import com.inkyandroid.ui.projects.NewProjectDialog
import com.inkyandroid.ui.projects.NewProjectPlace
import com.inkyandroid.ui.workspace.WatchesPane
import desktopkit.components.DeskButton
import desktopkit.components.DeskDialog
import desktopkit.components.GlassBottomSheet
import desktopkit.settings.ShortcutListSection

/**
 * The dialog the window has up ([WindowUi.dialog]), and the session's question about unsaved
 * changes, which comes before anything else.
 */
@Composable
internal fun WindowDialogs(session: ProjectSession?, state: SessionState?, storage: ProjectStorage, ui: WindowUi, actions: WindowActions) {
    val dismiss = { ui.dialog = null }
    when (val dialog = ui.dialog) {
        is WindowDialog.NewInclude -> NewIncludeDialog(
            addToMain = ui.addIncludeToMain,
            onAddToMainChange = { ui.addIncludeToMain = it },
            problem = dialog.problem,
            onAdd = actions::addInclude,
            onDismiss = dismiss,
        )
        is WindowDialog.Rename -> RenameDialog(dialog.path, dialog.problem, onRename = { name -> actions.rename(dialog.path, name) }, onDismiss = dismiss)
        is WindowDialog.Delete -> DeleteFileDialog(dialog.path, onDelete = { actions.delete(dialog.path) }, onDismiss = dismiss)
        is WindowDialog.NewProject -> {
            val projectsFolder by storage.projectsFolder.collectAsState()
            NewProjectDialog(
                parent = dialog.parent,
                projectsFolder = projectsFolder?.displayName,
                onCreate = { name, place ->
                    when {
                        dialog.parent != null -> actions.createProject(name, dialog.parent, internal = false)
                        place == NewProjectPlace.PickFolder -> {
                            ui.dialog = null
                            actions.pickFolder(FolderPurpose.NewProjectIn(name))
                        }
                        else -> actions.createProject(name, parent = null, internal = place == NewProjectPlace.Internal)
                    }
                },
                onDismiss = dismiss,
            )
        }
        is WindowDialog.ChooseMain -> ChooseMainDialog(dialog.folder, dialog.candidates, onChoose = { main -> actions.chooseMain(dialog.folder, main) }, onDismiss = dismiss)
        is WindowDialog.DeleteProject -> DeleteProjectDialog(dialog.ref, onDelete = { actions.deleteInternal(dialog.ref) }, onDismiss = dismiss)
        WindowDialog.Statistics -> if (session != null) {
            StatisticsDialog(session, onDismiss = dismiss)
        } else {
            // the project went while the dialog was asked for
            LaunchedEffect(Unit) { ui.dialog = null }
        }
        WindowDialog.Shortcuts -> ShortcutsDialog(onDismiss = dismiss)
        // a sheet of the shell's, drawn by WindowSheets
        WindowDialog.Watches, null -> Unit
    }
    if (state?.closeQuestion == true) CloseQuestionDialog(onAnswer = actions::answerClose)
}

/** The sheets the window puts over its screen: the watch expressions, where there is no inspector to hold them. */
@Composable
internal fun WindowSheets(session: ProjectSession?, state: SessionState?, ui: WindowUi) {
    GlassBottomSheet(
        visible = ui.dialog == WindowDialog.Watches && session != null,
        onDismiss = { if (ui.dialog == WindowDialog.Watches) ui.dialog = null },
        title = stringResource(R.string.watches_title),
    ) {
        if (session != null && state != null) {
            WatchesPane(
                watches = state.watches,
                onChange = session::setWatches,
                modifier = Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * SHEET_SHARE).dp),
                focusNewest = ui.focusNewestWatch,
                onFocused = { ui.focusNewestWatch = false },
            )
        }
    }
}

/**
 * Upstream's "Useful Keyboard Shortcuts", grown into the whole table: every key of the app by menu,
 * those the editor answers itself marked as such.
 */
@Composable
private fun ShortcutsDialog(onDismiss: () -> Unit) {
    val inEditor = stringResource(R.string.shortcuts_in_editor)
    DeskDialog(
        onDismiss = onDismiss,
        title = stringResource(desktopkit.R.string.dk_shortcut_list),
        actions = { DeskButton(stringResource(R.string.action_close), onDismiss, primary = true) },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * SHEET_SHARE).dp).verticalScroll(rememberScrollState())) {
            ShortcutListSection(shortcuts, scopeLabel = { scope -> if (scope == EditorScope) inEditor else null })
        }
    }
}

/** How much of the window's height a sheet or a long dialog may take. */
private const val SHEET_SHARE = 0.7f
