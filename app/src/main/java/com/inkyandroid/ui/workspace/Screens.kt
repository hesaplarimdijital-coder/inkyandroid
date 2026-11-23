package com.inkyandroid.ui.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.inkyandroid.R
import com.inkyandroid.core.live.LiveState
import com.inkyandroid.core.project.ProjectSnapshot
import com.inkyandroid.session.ProjectSession
import com.inkyandroid.session.SessionState
import com.inkyandroid.settings.AppSettings
import com.inkyandroid.ui.Commands
import com.inkyandroid.ui.InkIcons
import com.inkyandroid.ui.WindowActions
import com.inkyandroid.ui.WindowDialog
import com.inkyandroid.ui.WindowUi
import com.inkyandroid.ui.files.FileActions
import com.inkyandroid.ui.files.FilesList
import com.inkyandroid.ui.files.issuesByFile
import desktopkit.components.DeskIcons
import desktopkit.components.DeskTab
import desktopkit.components.HorizontalRule
import desktopkit.components.TabStrip
import desktopkit.components.VerticalRule
import desktopkit.editor.CodeEditorZoom
import desktopkit.screens.PageHeader
import desktopkit.shell.CompactTopBar
import desktopkit.shell.ShellPrimaryAction
import desktopkit.shell.TopBarAction
import desktopkit.shell.WidthClass
import desktopkit.shell.currentWidthClass
import desktopkit.shell.rememberShellContentPadding
import desktopkit.theme.DeskTheme

/**
 * The Play screen: the player on its own, where it does not fit beside the editor. Its top bar
 * has desktop Inky's two toolbar buttons (one choice back, the story from its start) and the
 * watches; the text runs under the bars.
 */
@Composable
fun PlayScreen(session: ProjectSession, live: LiveState, settings: AppSettings, ui: WindowUi, actions: WindowActions, zoom: CodeEditorZoom) {
    CompactTopBar(
        actions = listOf(
            TopBarAction(InkIcons.StepBack, stringResource(R.string.action_step_back), { actions.perform(Commands.STEP_BACK) }, enabled = live.canStepBack),
            TopBarAction(InkIcons.Restart, stringResource(R.string.action_restart), { actions.perform(Commands.RESTART) }),
            TopBarAction(DeskIcons.Eye, stringResource(R.string.watches_title), { ui.dialog = WindowDialog.Watches }),
        ),
        showOnRail = true,
    )
    PlayerPane(
        state = live,
        listState = ui.playerList,
        actions = rememberPlayerActions(session.live.session, actions),
        tagsVisible = settings.tagsVisible,
        animate = settings.playAnimation,
        zoom = zoom,
        contentPadding = rememberShellContentPadding(),
    )
}

/** The Issues screen: every problem of the project, by file. */
@Composable
fun IssuesScreen(state: SessionState, project: ProjectSnapshot, ui: WindowUi, actions: WindowActions) {
    Column(Modifier.fillMaxSize()) {
        val count = state.issues.size
        PageHeader(
            title = stringResource(R.string.nav_issues),
            subtitle = if (count > 0 && currentWidthClass() >= WidthClass.Large) pluralStringResource(R.plurals.issues_count, count, count) else null,
        )
        IssuesPane(
            issues = state.issues,
            fileOrder = project.files.map { it.path },
            onOpen = actions::openIssue,
            listState = ui.issuesList,
            contentPadding = rememberShellContentPadding(),
        )
    }
}

/** The window's answers to the files view. */
@Composable
fun rememberFileActions(actions: WindowActions, ui: WindowUi): FileActions = androidx.compose.runtime.remember(actions, ui) {
    FileActions(
        onOpen = actions::openFile,
        onNewInclude = { ui.dialog = WindowDialog.NewInclude() },
        onRename = { path -> ui.dialog = WindowDialog.Rename(path) },
        onDelete = { path -> ui.dialog = WindowDialog.Delete(path) },
        onCreateMissing = actions::createMissing,
        onRetryUnreadable = { actions.reload() },
    )
}

/**
 * The Files screen of a phone and a tablet: the project's files and the outline of the one in the
 * editor. Side by side where there is room, else as two tabs. "Add new include" is the screen's
 * main action.
 */
@Composable
fun FilesScreen(session: ProjectSession, state: SessionState, project: ProjectSnapshot, ui: WindowUi, actions: WindowActions) {
    val fileActions = rememberFileActions(actions, ui)
    val issues = issuesByFile(state.issues)
    val active = project.file(state.activePath) ?: project.main
    ShellPrimaryAction(stringResource(R.string.files_add_include), DeskIcons.Plus) { ui.dialog = WindowDialog.NewInclude() }
    val outline: @Composable (Modifier) -> Unit = { modifier ->
        OutlinePane(
            symbols = active.symbols,
            cursorRow = cursorRow(session, state),
            onOpen = { symbol -> actions.openSymbol(active.path, symbol) },
            modifier = modifier,
            listState = ui.outlineList,
            contentPadding = rememberShellContentPadding(),
        )
    }
    val files: @Composable (Modifier) -> Unit = { modifier ->
        FilesList(
            project = project,
            activePath = state.activePath,
            issues = issues,
            actions = fileActions,
            modifier = modifier,
            listState = ui.filesList,
            contentPadding = rememberShellContentPadding(),
            // the screen's main action adds a file; the list needs no second button for it
            showAddButton = false,
        )
    }
    if (currentWidthClass() >= WidthClass.Expanded) {
        Row(Modifier.fillMaxSize()) {
            files(Modifier.weight(1f))
            VerticalRule()
            Column(Modifier.weight(1f)) {
                PageHeader(title = stringResource(R.string.outline_title), subtitle = active.name)
                outline(Modifier)
            }
        }
    } else {
        // the tabs stand under the top bar; each list scrolls on its own and runs under the bottom bar
        val padding = rememberShellContentPadding()
        val listPadding = PaddingValues(bottom = padding.calculateBottomPadding())
        Column(Modifier.fillMaxSize()) {
            TabStrip(Modifier.padding(top = padding.calculateTopPadding())) {
                DeskTab(stringResource(R.string.nav_files), selected = ui.filesTab == 0, onClick = { ui.filesTab = 0 })
                DeskTab(stringResource(R.string.outline_title), selected = ui.filesTab == 1, onClick = { ui.filesTab = 1 })
            }
            HorizontalRule()
            if (ui.filesTab == 0) {
                FilesList(project, state.activePath, issues, fileActions, listState = ui.filesList, contentPadding = listPadding, showAddButton = false)
            } else {
                OutlinePane(
                    active.symbols,
                    cursorRow(session, state),
                    { symbol -> actions.openSymbol(active.path, symbol) },
                    listState = ui.outlineList,
                    contentPadding = listPadding,
                    fileName = active.name,
                )
            }
        }
    }
}
