package com.inkyandroid.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.inkyandroid.R
import com.inkyandroid.core.live.LiveState
import com.inkyandroid.core.project.ProjectFile
import com.inkyandroid.core.project.ProjectSnapshot
import com.inkyandroid.core.symbols.FlowPath
import com.inkyandroid.session.ProjectSession
import com.inkyandroid.session.SessionState
import com.inkyandroid.settings.AppSettings
import com.inkyandroid.settings.InspectorTab as SettingsTab
import com.inkyandroid.ui.Commands
import com.inkyandroid.ui.InkIcons
import com.inkyandroid.ui.Routes
import com.inkyandroid.ui.WindowActions
import com.inkyandroid.ui.WindowUi
import com.inkyandroid.ui.shortcuts
import desktopkit.appResources
import desktopkit.components.DeskIconButton
import desktopkit.components.DeskIcons
import desktopkit.components.HorizontalRule
import desktopkit.components.InspectorPane
import desktopkit.components.InspectorState
import desktopkit.components.InspectorTab
import desktopkit.components.InspectorToggleButton
import desktopkit.components.WorkstationLayout
import desktopkit.components.animatedStateBackground
import desktopkit.components.collectState
import desktopkit.editor.CodeEditorZoom
import desktopkit.components.deskClickable
import desktopkit.components.focusRing
import desktopkit.input.showShortcutHints
import desktopkit.shell.CompactTopBar
import desktopkit.shell.TopBarAction
import desktopkit.shell.WidthClass
import desktopkit.shell.rememberShellContentPadding
import desktopkit.theme.DeskTheme

/** The narrowest the editor and the inspector may be beside each other; below their sum the window shows one of them. */
val MIN_EDITOR_WIDTH = 360.dp
val MIN_INSPECTOR_WIDTH = 320.dp

/** Whether the editor and the inspector fit side by side in a space [width] wide. */
fun fitsTwoPanes(width: Dp): Boolean = width >= MIN_EDITOR_WIDTH + MIN_INSPECTOR_WIDTH

/**
 * The Write screen: the editor of the active file, and where the window has room for two panes
 * the inspector beside it, with the player, the issues, the outline and the watches as its tabs.
 *
 * Which of the two it is depends on the room the screen really has ([twoPanes], measured by the
 * app's root), not on the kind of navigation around it: a phone on its side has a rail and room for
 * both, a tablet's narrow split window has a rail and room for one.
 */
@Composable
fun WorkspaceScreen(
    session: ProjectSession,
    state: SessionState,
    project: ProjectSnapshot,
    live: LiveState,
    settings: AppSettings,
    ui: WindowUi,
    actions: WindowActions,
    editorZoom: CodeEditorZoom,
    playerZoom: CodeEditorZoom,
    twoPanes: Boolean,
    availableWidth: Dp,
) {
    val file = project.file(state.activePath) ?: project.main
    if (!twoPanes) {
        EditorTopBar(file, state, actions)
        EditorPane(session, state, project, settings, ui, actions, editorZoom, contentPadding = rememberShellContentPadding())
        return
    }
    // The divider's place is a share of the workspace, as desktop Inky's is: a window that grows
    // keeps the proportion. What the divider is dragged to, and whether the pane is shut, outlive
    // the window; they are stored when a drag ends, not on every frame of it.
    val width by rememberUpdatedState(availableWidth)
    val inspector = remember(session) {
        InspectorState(
            initialWidthDp = (availableWidth.value * settings.inspectorShare).coerceAtLeast(MIN_INSPECTOR_WIDTH.value),
            initialCollapsed = settings.inspectorCollapsed,
        ) { widthDp, collapsed ->
            settings.updateInspectorShare(widthDp / width.value)
            settings.updateInspectorCollapsed(collapsed)
        }
    }
    val maxInspector = (availableWidth - MIN_EDITOR_WIDTH).coerceAtLeast(MIN_INSPECTOR_WIDTH)
    LaunchedEffect(inspector, availableWidth) {
        inspector.resizeBy(availableWidth.value * settings.inspectorShare - inspector.widthDp, MIN_INSPECTOR_WIDTH, maxInspector)
    }
    DisposableEffect(inspector) {
        val toggle = {
            inspector.toggle()
            true
        }
        actions.toggleInspector = toggle
        // a workspace that leaves after the next one came (a cross-fade) leaves that one's switch alone
        onDispose { if (actions.toggleInspector === toggle) actions.toggleInspector = { false } }
    }
    val hints = showShortcutHints()
    val resources = appResources()
    val liveSession = session.live.session
    WorkstationLayout(
        state = inspector,
        main = {
            Column(Modifier.fillMaxSize()) {
                EditorHeader(file, state, project, session, live, inspector, actions)
                HorizontalRule()
                EditorPane(session, state, project, settings, ui, actions, editorZoom)
            }
        },
        inspector = {
            val tabs = listOf(
                InspectorTab(SettingsTab.Player.name, stringResource(R.string.player_title)),
                InspectorTab(SettingsTab.Issues.name, stringResource(R.string.nav_issues)),
                InspectorTab(SettingsTab.Outline.name, stringResource(R.string.outline_title)),
                InspectorTab(SettingsTab.Watches.name, stringResource(R.string.watches_title)),
            )
            InspectorPane(
                tabs = tabs,
                selected = settings.inspectorTab.ordinal,
                onSelect = { settings.updateInspectorTab(SettingsTab.entries[it]) },
                onCollapse = inspector::toggle,
            ) { tab ->
                when (SettingsTab.valueOf(tab.id)) {
                    SettingsTab.Player -> Column(Modifier.fillMaxSize()) {
                        PlayerHeader(
                            state = live,
                            onStepBack = { actions.perform(Commands.STEP_BACK) },
                            onRestart = { actions.perform(Commands.RESTART) },
                            stepBackShortcut = if (hints) shortcuts.chordLabel(Commands.STEP_BACK, resources) else null,
                            restartShortcut = if (hints) shortcuts.chordLabel(Commands.RESTART, resources) else null,
                        )
                        PlayerPane(
                            state = live,
                            listState = ui.playerList,
                            actions = rememberPlayerActions(liveSession, actions),
                            tagsVisible = settings.tagsVisible,
                            animate = settings.playAnimation,
                            zoom = playerZoom,
                        )
                    }
                    SettingsTab.Issues -> IssuesPane(state.issues, project.files.map { it.path }, actions::openIssue, listState = ui.issuesList)
                    SettingsTab.Outline -> OutlinePane(
                        symbols = file.symbols,
                        cursorRow = cursorRow(session, state),
                        onOpen = { symbol -> actions.openSymbol(file.path, symbol) },
                        listState = ui.outlineList,
                        fileName = file.name,
                    )
                    SettingsTab.Watches -> WatchesPane(
                        watches = state.watches,
                        onChange = session::setWatches,
                        focusNewest = ui.focusNewestWatch,
                        onFocused = { ui.focusNewestWatch = false },
                    )
                }
            }
        },
        minMain = MIN_EDITOR_WIDTH,
        minInspector = MIN_INSPECTOR_WIDTH,
        maxInspector = maxInspector,
        // the room was measured: the inspector is beside the editor whatever the window's class
        showFrom = WidthClass.Compact,
    )
}

/** The zero-based row of the cursor in the active file. Read in composition, it follows the cursor. */
@Composable
internal fun cursorRow(session: ProjectSession, state: SessionState): Int =
    remember(session, state.activePath) { session.document(state.activePath) }?.buffer?.cursor?.row ?: 0

/** A file's name as the bars show it: with a dot behind it while it has unsaved changes. */
@Composable
internal fun fileLabel(file: ProjectFile): String = if (file.hasUnsavedChanges) stringResource(R.string.editor_unsaved_mark, file.name) else file.name

/**
 * The phone's top bar over the editor: the file's name, Play (to the player's tab), the issues when
 * there are any, and under "⋮" what a keyboard has keys for.
 */
@Composable
private fun EditorTopBar(file: ProjectFile, state: SessionState, actions: WindowActions) {
    val counts = countIssues(state.issues)
    val issueCount = state.issues.size
    val issuesLabel = pluralStringResource(R.plurals.issues_count, issueCount, issueCount)
    val editor = actions.ui.editor
    CompactTopBar(
        title = fileLabel(file),
        actions = buildList {
            add(TopBarAction(DeskIcons.Play, stringResource(R.string.action_play), { actions.navigate(Routes.PLAY) }, primary = true))
            if (issueCount > 0) {
                add(TopBarAction(if (counts.errors > 0) DeskIcons.Error else DeskIcons.Warning, issuesLabel, { actions.navigate(Routes.ISSUES) }))
            }
            add(TopBarAction(DeskIcons.Undo, stringResource(R.string.action_undo), { actions.perform(Commands.UNDO) }, enabled = editor?.canUndo == true))
            add(TopBarAction(InkIcons.Redo, stringResource(R.string.action_redo), { actions.perform(Commands.REDO) }, enabled = editor?.canRedo == true))
            if (actions.available(Commands.FIND)) add(TopBarAction(DeskIcons.Search, stringResource(R.string.action_find), { actions.perform(Commands.FIND) }))
            add(TopBarAction(InkIcons.ArrowRight, stringResource(R.string.action_goto_definition), { actions.perform(Commands.GOTO_DEFINITION) }))
            add(TopBarAction(InkIcons.Snippet, stringResource(R.string.action_insert_snippet), { actions.perform(Commands.INSERT_SNIPPET) }))
        },
        // a narrow window with a rail has the same single pane, and wants the same bar
        showOnRail = true,
    )
}

/**
 * The row above the editor in the workspace: back and forward through the places visited, the
 * file's name with the knot and the stitch the cursor is in, the issue counts (they lead to the
 * issues tab) and the inspector's switch.
 */
@Composable
private fun EditorHeader(
    file: ProjectFile,
    state: SessionState,
    project: ProjectSnapshot,
    session: ProjectSession,
    live: LiveState,
    inspector: InspectorState,
    actions: WindowActions,
) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val hints = showShortcutHints()
    val resources = appResources()
    val row = cursorRow(session, state)
    val flow = remember(file.symbols, row) { file.symbols?.flowAt(row) ?: FlowPath() }
    Row(
        Modifier.fillMaxWidth().background(colors.surface).heightIn(min = dimens.minTarget.coerceAtLeast(HEADER_HEIGHT)).padding(horizontal = dimens.gapXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.gapXs),
    ) {
        DeskIconButton(
            DeskIcons.ArrowLeft,
            stringResource(R.string.action_back),
            { actions.perform(Commands.BACK) },
            enabled = state.canGoBack,
            shortcut = if (hints) shortcuts.chordLabel(Commands.BACK, resources) else null,
        )
        DeskIconButton(
            InkIcons.ArrowRight,
            stringResource(R.string.action_forward),
            { actions.perform(Commands.FORWARD) },
            enabled = state.canGoForward,
            shortcut = if (hints) shortcuts.chordLabel(Commands.FORWARD, resources) else null,
        )
        Row(Modifier.weight(1f).padding(horizontal = dimens.gapS), verticalAlignment = Alignment.CenterVertically) {
            Text(fileLabel(file), style = DeskTheme.type.bodyStrong, color = colors.text, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            listOfNotNull(flow.knot, flow.stitch).forEach { symbol ->
                Text(CRUMB, style = DeskTheme.type.body, color = colors.textTertiary, maxLines = 1)
                Text(symbol.name, style = DeskTheme.type.body, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
        }
        if (state.issues.isNotEmpty()) {
            val interaction = remember { MutableInteractionSource() }
            val visual = interaction.collectState()
            val shape = RoundedCornerShape(dimens.radiusControl)
            val label = pluralStringResource(R.plurals.issues_count, state.issues.size, state.issues.size)
            Box(
                Modifier
                    .focusRing(visual, shape)
                    .background(animatedStateBackground(visual).value, shape)
                    .deskClickable(interaction, { actions.perform(Commands.SHOW_ISSUES) }, role = Role.Button)
                    .padding(horizontal = dimens.gapS, vertical = dimens.gapXs)
                    .semantics(mergeDescendants = true) { contentDescription = label },
            ) {
                IssueSummary(countIssues(state.issues))
            }
        }
        InspectorToggleButton(inspector)
    }
}

/** What stands between the parts of the place shown in the header: file › knot › stitch. */
private const val CRUMB = "  ›  "

/** The header is as high as the inspector's row of tabs beside it, so the two rules under them meet. */
private val HEADER_HEIGHT = 36.dp
