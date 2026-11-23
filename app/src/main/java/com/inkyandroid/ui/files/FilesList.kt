package com.inkyandroid.ui.files

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.inkyandroid.R
import com.inkyandroid.core.engine.InkIssue
import com.inkyandroid.core.engine.IssueKind
import com.inkyandroid.core.project.ProjectFile
import com.inkyandroid.core.project.ProjectSnapshot
import com.inkyandroid.storage.StorageProblem
import com.inkyandroid.ui.InkIcons
import com.inkyandroid.ui.workspace.issueIcon
import com.inkyandroid.ui.workspace.issueTone
import desktopkit.appResources
import desktopkit.components.DeskButton
import desktopkit.components.DeskIcons
import desktopkit.components.DeskListRow
import desktopkit.components.MenuEntry
import desktopkit.components.SectionCaption
import desktopkit.components.verticalScrollbar
import desktopkit.theme.DeskTheme

/** What the files view asks of whoever shows it. */
class FileActions(
    /** Show the file in the editor. */
    val onOpen: (path: String) -> Unit,
    /** Desktop Inky's "Add new include" form. */
    val onNewInclude: () -> Unit,
    val onRename: (path: String) -> Unit,
    val onDelete: (path: String) -> Unit,
    /** Create a file an `INCLUDE` line names and storage does not have. */
    val onCreateMissing: (path: String) -> Unit,
    /** Read the project's folder again: a file that could not be read may have been mended meanwhile. */
    val onRetryUnreadable: () -> Unit,
    /** Whether texts of the file at a path were kept before a conflict's answer replaced them. */
    val hasKept: (path: String) -> Boolean = { false },
    /** Shows those kept texts. */
    val onKeptVersions: (path: String) -> Unit = {},
)

/** How many issues a file has, and the most serious kind among them: what its row shows. */
data class FileIssues(val count: Int, val worst: IssueKind)

/** The issues of every file by path, for the rows' marks and the sidebar's badges. */
fun issuesByFile(issues: List<InkIssue>): Map<String, FileIssues> =
    issues.filter { it.file != null }.groupBy { it.file!! }.mapValues { (_, list) -> FileIssues(list.size, list.minOf { it.kind }) }

/**
 * The menu of a project file: in the files view, and on its entry in the sidebar. The main file
 * can be neither renamed nor deleted (the project is named after it); any file whose texts were kept
 * before a conflict's answer replaced them offers those.
 */
fun fileMenu(file: ProjectFile, resources: Resources, actions: FileActions): List<MenuEntry> = buildList {
    if (actions.hasKept(file.path)) add(MenuEntry.Item(resources.getString(R.string.action_kept_versions), { actions.onKeptVersions(file.path) }))
    if (!file.isMain) {
        add(MenuEntry.Item(resources.getString(R.string.action_rename), { actions.onRename(file.path) }))
        add(MenuEntry.Item(resources.getString(R.string.action_delete), { actions.onDelete(file.path) }, icon = DeskIcons.Delete, destructive = true))
    }
}

/**
 * The files of the project as desktop Inky's files view groups them: the main ink file, then the
 * included files by folder (the folder's path as the group's title, the project's own folder first
 * and untitled), then the files nothing includes, under "Unused files" with their paths.
 *
 * A file with unsaved changes has its name in bold with a dot after it; a file with issues shows
 * their number under the mark of the most serious one. Files that `INCLUDE` lines name but storage
 * does not have are listed last, each with a button that creates it.
 */
@Composable
fun FilesList(
    project: ProjectSnapshot,
    activePath: String,
    issues: Map<String, FileIssues>,
    actions: FileActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    showAddButton: Boolean = true,
) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val resources = appResources()
    // why each unreadable file could not be read, in the user's language (storage tells a file that
    // is not text from one the app may not read)
    val unreadable = remember(project.loadFailures, resources) {
        project.loadFailures.mapValues { (_, failed) -> StorageProblem.of(failed).message(resources) }
    }
    val tree = project.tree
    val caption = Modifier.padding(start = dimens.pagePadding, end = dimens.pagePadding, top = dimens.gapL, bottom = dimens.gapXs)
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().background(colors.background).verticalScrollbar(listState),
        contentPadding = contentPadding,
    ) {
        item(key = "main-title") { SectionCaption(stringResource(R.string.files_main), caption) }
        item(key = "file:" + tree.main.path) { FileRow(tree.main, tree.main.name, activePath, issues, unreadable, actions) }
        tree.groups.forEach { group ->
            // desktop Inky leaves the group of the project's own folder untitled; a title keeps it apart from the main file
            item(key = "folder:" + group.folder) { SectionCaption(group.folder.ifEmpty { stringResource(R.string.files_root_folder) }, caption) }
            items(group.files, key = { "file:" + it.path }) { file -> FileRow(file, file.name, activePath, issues, unreadable, actions) }
        }
        if (tree.spare.isNotEmpty()) {
            item(key = "spare-title") { SectionCaption(stringResource(R.string.nav_unused_files), caption) }
            items(tree.spare, key = { "file:" + it.path }) { file -> FileRow(file, file.path, activePath, issues, unreadable, actions) }
        }
        if (project.missingIncludes.isNotEmpty()) {
            item(key = "missing-title") { SectionCaption(stringResource(R.string.files_missing_title), caption) }
            items(project.missingIncludes.distinct(), key = { "missing:$it" }) { path ->
                DeskListRow(
                    title = path,
                    leading = DeskIcons.Warning,
                    leadingTint = colors.warning,
                    trailingContent = { DeskButton(stringResource(R.string.files_missing_create), { actions.onCreateMissing(path) }) },
                    onClick = { actions.onCreateMissing(path) },
                )
            }
        }
        // files that are in the project's folder but could not be read (not text, gone while listed): they
        // are no project files, so they have no row above; what happened to each is said here
        if (unreadable.isNotEmpty()) {
            item(key = "unreadable-title") { SectionCaption(stringResource(R.string.files_unreadable_title), caption) }
            items(unreadable.keys.sorted(), key = { "unreadable:$it" }) { path ->
                DeskListRow(
                    title = path,
                    subtitle = unreadable.getValue(path),
                    subtitleColor = colors.danger,
                    subtitleMaxLines = 3,
                    leading = DeskIcons.Error,
                    leadingTint = colors.danger,
                    trailingContent = { DeskButton(stringResource(R.string.action_retry), actions.onRetryUnreadable) },
                    onClick = actions.onRetryUnreadable,
                )
            }
        }
        if (showAddButton) {
            item(key = "add") {
                Box(Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding, vertical = dimens.gapL)) {
                    DeskButton(stringResource(R.string.files_add_include), actions.onNewInclude, icon = DeskIcons.Plus)
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    file: ProjectFile,
    label: String,
    activePath: String,
    issues: Map<String, FileIssues>,
    loadErrors: Map<String, String>,
    actions: FileActions,
) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val resources = appResources()
    val found = issues[file.path]
    val loadError = loadErrors[file.path]
    val state = when {
        loadError != null -> loadError
        file.hasConflict -> stringResource(R.string.files_conflict)
        !file.isOnStorage -> stringResource(R.string.files_not_on_storage)
        else -> null
    }
    val unsaved = stringResource(R.string.files_unsaved)
    val issueText = found?.let { pluralStringResource(R.plurals.files_issue_count, it.count, it.count) }
    val described = listOfNotNull(label, if (file.hasUnsavedChanges) unsaved else null, state, issueText).joinToString(", ")
    val menu = remember(file, resources, actions) { fileMenu(file, resources, actions) }
    DeskListRow(
        title = if (file.hasUnsavedChanges) stringResource(R.string.editor_unsaved_mark, label) else label,
        modifier = Modifier.semantics { contentDescription = described },
        subtitle = state,
        subtitleColor = if (loadError != null || file.hasConflict) colors.danger else colors.textTertiary,
        leading = if (file.isMain) InkIcons.Book else InkIcons.File,
        trailingContent = if (found == null) null else {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(dimens.gapXs), verticalAlignment = Alignment.CenterVertically) {
                    Icon(issueIcon(found.worst), contentDescription = null, tint = issueTone(found.worst), modifier = Modifier.size(dimens.icon))
                    Text(found.count.toString(), style = DeskTheme.type.mono, color = colors.textSecondary)
                }
            }
        },
        strong = file.hasUnsavedChanges,
        selected = file.path == activePath,
        onClick = { actions.onOpen(file.path) },
        menu = if (menu.isEmpty()) null else ({ menu }),
        moreButton = menu.isNotEmpty(),
    )
}
