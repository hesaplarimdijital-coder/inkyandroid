package com.inkyandroid.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.inkyandroid.R
import com.inkyandroid.storage.ProjectRef
import com.inkyandroid.storage.ProjectStorage
import com.inkyandroid.storage.RecentProject
import com.inkyandroid.ui.Commands
import com.inkyandroid.ui.FolderPurpose
import com.inkyandroid.ui.InkIcons
import com.inkyandroid.ui.WindowActions
import com.inkyandroid.ui.WindowUi
import desktopkit.components.DeskIcons
import desktopkit.components.DeskListRow
import desktopkit.components.MenuEntry
import desktopkit.components.SectionCaption
import desktopkit.components.verticalScrollbar
import desktopkit.screens.PageHeader
import desktopkit.shell.rememberShellContentPadding
import desktopkit.theme.DeskTheme

/**
 * The Projects screen: the three ways to a project (a new one, a folder, a single ink file), the
 * recent projects (desktop Inky's "Open Recent", with "Clear"), and the projects kept inside the
 * app, which can be copied out to a folder or deleted.
 *
 * @param openReference the reference of the project this window shows, to mark it in the lists.
 */
@Composable
fun ProjectsScreen(storage: ProjectStorage, openReference: String?, ui: WindowUi, actions: WindowActions) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val recents by storage.recents.entries.collectAsState()
    // entries whose folder is gone or no longer granted leave the list when it is looked at
    LaunchedEffect(Unit) { storage.recents.removeUnavailable() }
    // listed again when a dialog closes (a project was made or deleted) and when the window's project changes
    val internal by produceState(emptyList<ProjectRef.Internal>(), ui.dialog, openReference) { value = storage.internalProjects.list() }
    val listState = rememberLazyListState()
    val caption = Modifier.padding(start = dimens.pagePadding, end = dimens.pagePadding, top = dimens.gapL, bottom = dimens.gapXs)
    Column(Modifier.fillMaxSize().background(colors.background)) {
        PageHeader(title = stringResource(R.string.nav_projects))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().verticalScrollbar(listState), contentPadding = rememberShellContentPadding()) {
            item(key = "start") { SectionCaption(stringResource(R.string.projects_start), caption) }
            item(key = "new") {
                DeskListRow(stringResource(R.string.action_new_project), leading = DeskIcons.Plus, onClick = { actions.perform(Commands.NEW_PROJECT) })
            }
            item(key = "folder") {
                DeskListRow(stringResource(R.string.action_open_folder), leading = DeskIcons.Folder, onClick = { actions.perform(Commands.OPEN_FOLDER) })
            }
            item(key = "file") {
                DeskListRow(stringResource(R.string.action_open_file), leading = InkIcons.File, onClick = { actions.perform(Commands.OPEN_FILE) })
            }

            item(key = "recent-title") { SectionCaption(stringResource(R.string.projects_recent), caption) }
            if (recents.isEmpty()) {
                item(key = "recent-none") { DeskListRow(stringResource(R.string.projects_recent_none), enabled = false) }
            } else {
                items(recents, key = { "recent:" + it.ref.key }) { recent -> RecentRow(recent, openReference, actions) }
                item(key = "recent-clear") {
                    DeskListRow(stringResource(R.string.action_clear_recent), leading = DeskIcons.Close, onClick = actions::clearRecents)
                }
            }

            if (internal.isNotEmpty()) {
                item(key = "internal-title") { SectionCaption(stringResource(R.string.projects_on_device), caption) }
                items(internal, key = { "internal:" + it.key }) { ref ->
                    val copyOut = stringResource(R.string.projects_copy_out)
                    val delete = stringResource(R.string.projects_delete_internal)
                    ProjectRow(
                        ref = ref,
                        open = ref.encode() == openReference,
                        menu = listOf(
                            MenuEntry.Item(copyOut, { actions.pickFolder(FolderPurpose.CopyOut(ref)) }, icon = InkIcons.Export),
                            MenuEntry.Item(delete, { actions.askDeleteInternal(ref) }, icon = DeskIcons.Delete, destructive = true),
                        ),
                        onClick = { actions.openProject(ref) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentRow(recent: RecentProject, openReference: String?, actions: WindowActions) {
    val remove = stringResource(R.string.projects_remove_recent)
    ProjectRow(
        ref = recent.ref,
        open = recent.ref.encode() == openReference,
        menu = listOf(MenuEntry.Item(remove, { actions.removeRecent(recent.ref) }, icon = DeskIcons.Close)),
        onClick = { actions.openProject(recent.ref) },
    )
}

/** One project: its name, what kind of place it is in and its main file; marked when this window shows it. */
@Composable
private fun ProjectRow(ref: ProjectRef, open: Boolean, menu: List<MenuEntry>, onClick: () -> Unit) {
    DeskListRow(
        title = ref.displayName,
        subtitle = stringResource(R.string.projects_row_subtitle, projectKind(ref), ref.mainPath),
        leading = projectIcon(ref),
        trailing = if (open) stringResource(R.string.projects_open_current) else null,
        selected = open,
        onClick = onClick,
        menu = { menu },
        moreButton = true,
    )
}

@Composable
internal fun projectKind(ref: ProjectRef): String = stringResource(
    when (ref) {
        is ProjectRef.Saf -> R.string.projects_kind_folder
        is ProjectRef.Internal -> R.string.projects_kind_internal
        is ProjectRef.SingleDocument -> R.string.projects_kind_single
    },
)

internal fun projectIcon(ref: ProjectRef): ImageVector = when (ref) {
    is ProjectRef.Saf -> DeskIcons.Folder
    is ProjectRef.Internal -> InkIcons.Book
    is ProjectRef.SingleDocument -> InkIcons.File
}
