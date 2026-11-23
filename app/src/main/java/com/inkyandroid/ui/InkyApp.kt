package com.inkyandroid.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.inkyandroid.R
import com.inkyandroid.core.live.LiveState
import com.inkyandroid.ink.inkSyntax
import com.inkyandroid.session.ProjectSession
import com.inkyandroid.session.ProjectOpenElsewhereException
import com.inkyandroid.session.ProjectSessionHolder
import com.inkyandroid.session.SessionState
import com.inkyandroid.session.WindowProject
import com.inkyandroid.settings.AppSettings
import com.inkyandroid.settings.InspectorTab
import com.inkyandroid.storage.ProjectRef
import com.inkyandroid.storage.ProjectStorage
import com.inkyandroid.storage.StorageProblem
import com.inkyandroid.storage.StorageContracts
import com.inkyandroid.ui.extras.DocsScreen
import com.inkyandroid.ui.extras.SettingsScreen
import com.inkyandroid.ui.extras.rememberExportActions
import com.inkyandroid.ui.files.fileMenu
import com.inkyandroid.ui.files.issuesByFile
import com.inkyandroid.ui.projects.ProjectsScreen
import com.inkyandroid.ui.workspace.FilesScreen
import com.inkyandroid.ui.workspace.IssuesScreen
import com.inkyandroid.ui.workspace.PlayScreen
import com.inkyandroid.ui.workspace.WorkspaceScreen
import com.inkyandroid.ui.workspace.fitsTwoPanes
import com.inkyandroid.ui.workspace.rememberFileActions
import desktopkit.DesktopKitRoot
import desktopkit.appResources
import desktopkit.components.BannerTone
import desktopkit.components.CommandPaletteState
import desktopkit.components.rememberToastState
import desktopkit.editor.CodeEditorZoom
import desktopkit.editor.rememberCodeEditorZoom
import desktopkit.input.CommonShortcuts
import desktopkit.input.showShortcutHints
import desktopkit.settings.DeskPreferences
import desktopkit.settings.rememberDeskPreferences
import desktopkit.shell.DeskWindows
import desktopkit.shell.DesktopShell
import desktopkit.shell.NavKind
import desktopkit.shell.ShellNavigator
import desktopkit.shell.ShellTransitions
import desktopkit.shell.WidthClass
import kotlinx.coroutines.launch

/**
 * The whole app inside one window: the kit's root (theme, density, insets) and its shell (bottom
 * bar, rail or sidebar by the window's width) around the screens, with the menu bar, the "⋯"
 * sheet, the command palette ("Go to anything") and the commands behind the shortcut table.
 *
 * @param holder the window's project session; the activity keeps it across configuration changes.
 * @param settings the app's own settings, shared by every window.
 * @param storage where projects are opened, created and listed.
 * @param ui what the window shows beyond its project (scroll positions, the dialog that is up).
 * @param prefs the kit's preferences (theme, density, the sidebar); tests pin their own.
 * @param transitions how the shell moves between screens; tests turn it off.
 * @param palette the command palette's state. The activity holds it, to leave the keys alone while it is open.
 * @param commands where the activity finds the window's commands, for the keys it routes.
 * @param startRoute where a window opened from another one starts.
 */
@Composable
fun InkyApp(
    holder: ProjectSessionHolder,
    settings: AppSettings,
    storage: ProjectStorage,
    ui: WindowUi,
    prefs: DeskPreferences = rememberDeskPreferences(),
    transitions: ShellTransitions = ShellTransitions.Default,
    palette: CommandPaletteState = remember { CommandPaletteState() },
    commands: CommandHost? = null,
    startRoute: String? = null,
) {
    val navController = rememberWindowNavController()
    val navigator = remember(navController) { ShellNavigator(navController) }
    val activity = LocalActivity.current
    val context = LocalContext.current
    val resources = appResources()
    val currentResources by rememberUpdatedState(resources)
    val locales = LocalConfiguration.current.locales
    val scope = rememberCoroutineScope()
    val toasts = rememberToastState()
    val window by holder.project.collectAsState()
    val session = (window as? WindowProject.Open)?.session
    val state: SessionState? = session?.state?.collectAsState()?.value
    val project = session?.project?.state?.collectAsState()?.value
    val live: LiveState? = session?.live?.session?.state?.collectAsState()?.value

    val actions = remember(holder, settings, storage, ui) {
        WindowActions(holder, settings, storage, ui, scope, toasts) { currentResources }
    }
    // the system's pickers answer later, to the window that asked
    val pickFolder = rememberLauncherForActivityResult(StorageContracts.PickFolder()) { uri -> actions.folderPicked(uri) }
    val pickFile = rememberLauncherForActivityResult(StorageContracts.OpenInkFile()) { uri -> actions.filePicked(uri) }
    val exports = session?.let { rememberExportActions(it, toasts) }
    SideEffect {
        actions.navigate = { route -> navigator.navigate(route) }
        actions.currentRoute = { navController.currentBackStackEntry?.destination?.route }
        actions.launchFolderPicker = { hint ->
            try {
                pickFolder.launch(hint)
            } catch (missing: ActivityNotFoundException) {
                actions.notify(currentResources.getString(R.string.no_picker), BannerTone.Error)
            }
        }
        actions.launchFilePicker = { hint ->
            try {
                pickFile.launch(hint)
            } catch (missing: ActivityNotFoundException) {
                actions.notify(currentResources.getString(R.string.no_picker), BannerTone.Error)
            }
        }
        actions.extras = exports?.let { export ->
            WindowExtras(
                exportJson = { if (!export.busy) export.exportJson() },
                exportWeb = { if (!export.busy) export.exportForWeb() },
                exportJs = { if (!export.busy) export.exportStoryJs() },
                statistics = true,
            )
        }
        actions.openPalette = { query, group ->
            palette.open(query)
            palette.filter = group
        }
        actions.newWindow = activity?.let { host -> { DeskWindows.openNewWindow(host) } }
        actions.copyText = { text ->
            context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText(text.take(CLIP_LABEL_LENGTH), text))
        }
        commands?.perform = actions::perform
        commands?.otherKey = actions::performSnippetKey
    }

    // a project or a place the window was asked to switch to is open in another window: the window keeps
    // what it has and says so, once
    val declined by holder.declined.collectAsState()
    LaunchedEffect(declined) {
        val refused = declined ?: return@LaunchedEffect
        actions.notify(
            if (refused is ProjectOpenElsewhereException) currentResources.getString(R.string.project_open_elsewhere_title) else StorageProblem.of(refused).message(currentResources),
            BannerTone.Warning,
        )
        holder.dismissDeclined()
    }

    // an .ink file another app handed over opens as a project; a first launch opens the sample
    LaunchedEffect(ui.pendingFile) {
        val handed = ui.pendingFile
        val fresh = ui.freshStart
        ui.freshStart = false
        when {
            handed != null -> {
                ui.pendingFile = null
                actions.filePicked(handed)
            }
            fresh -> actions.openSampleOnFirstLaunch()
        }
    }

    // The shell's graph is built from the spec: a new one only when a file comes, goes or moves,
    // the window's language changes, or the editor and the player stop (or start) fitting side by
    // side, which moves Play onto the rail (or off it). Never with a keystroke.
    val projectName = session?.let { open -> ProjectRef.decode(open.reference)?.displayName ?: open.project.store.displayName }
    val navFiles = if (project != null && projectName != null) NavFiles.of(projectName, project.tree) else null
    val spec = remember(locales, navFiles, ui.twoPanes, startRoute) {
        buildShellSpec(resources, navFiles, playOnRail = !ui.twoPanes, docs = true, settings = true, startRoute = startRoute)
    }
    val entry by navController.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route ?: spec.startRoute
    SideEffect { if (ui.route != currentRoute) ui.route = currentRoute }

    // The screen the window showed when the system ended the process comes back once the project
    // is open again and the graph is the project's (a file's route needs nothing: the sidebar marks
    // the active file by itself).
    val routeMemory = rememberSaveable(saver = RouteMemory.Saver) { RouteMemory() }
    SideEffect { routeMemory.shown = currentRoute.takeIf { Routes.filePath(it) == null && it != spec.startRoute } }
    LaunchedEffect(window is WindowProject.Opening, spec) {
        val route = routeMemory.pending ?: return@LaunchedEffect
        if (window is WindowProject.Opening) return@LaunchedEffect
        routeMemory.pending = null
        val exists = runCatching { navController.graph.findNode(route) != null }.getOrDefault(false)
        if (exists && route != navController.currentBackStackEntry?.destination?.route) navigator.navigate(route)
    }

    val fileIssues = remember(state?.issues) { issuesByFile(state?.issues.orEmpty()) }
    val unsaved = project?.files?.filter { it.hasUnsavedChanges }?.map { it.path }?.toSet().orEmpty()
    val issueCount = state?.issues?.size ?: 0
    val badges = remember(issueCount, fileIssues, unsaved) { navBadges(issueCount, fileIssues, unsaved) }
    val issuesDescribed = pluralStringResource(R.plurals.issues_count, issueCount, issueCount)
    val fileActions = rememberFileActions(actions, ui)
    val showKeys = showShortcutHints()
    val customSnippets = state?.projectSettings?.customInkSnippets.orEmpty()
    val ink = remember(customSnippets, resources, session, showKeys) { if (session == null) emptyList() else inkMenu(customSnippets, resources, actions, showKeys) }
    val recents by storage.recents.entries.collectAsState()
    val openReference = (window as? WindowProject.Open)?.session?.reference
    val recent = remember(recents, openReference, resources) { recentMenu(recents, openReference, resources, actions) }
    val menus = WindowMenus(
        resources = resources,
        registry = shortcuts,
        settings = settings,
        perform = { id -> actions.perform(id) },
        enabled = actions::enabled,
        available = actions::available,
        showKeys = showKeys,
        ink = ink,
        recent = recent,
    )
    val editorZoom = rememberCodeEditorZoom(prefs, EDITOR_ZOOM_KEY)
    val playerZoom = rememberCodeEditorZoom(prefs, PLAYER_ZOOM_KEY)
    SideEffect {
        // The zoom keys and the menu's zoom resize the editor and the player together, as upstream's
        // do; the editor's own gestures (pinch, Ctrl+wheel) resize the editor alone.
        actions.zoom = { step ->
            session != null && run {
                listOf(editorZoom, playerZoom).forEach { zoom ->
                    when {
                        step > 0 -> zoom.zoomIn()
                        step < 0 -> zoom.zoomOut()
                        else -> zoom.reset()
                    }
                }
                true
            }
        }
    }

    DesktopKitRoot(prefs, syntax = { base -> inkSyntax(base, settings.tagsVisible) }) { widthClass ->
        val sidebar = NavKind.forWidth(widthClass) == NavKind.Sidebar
        SideEffect {
            actions.toggleSidebar = {
                if (sidebar) prefs.updateSidebarCollapsed(!prefs.sidebarCollapsed)
                sidebar
            }
        }
        // The sidebar lists the project's files as entries of their own: the one in the editor is
        // marked. A file chosen there becomes the editor's (a history step, as upstream's files
        // view adds one); a jump that changes the editor's file moves the mark along.
        LaunchedEffect(currentRoute, session) {
            val path = Routes.filePath(currentRoute) ?: return@LaunchedEffect
            if (session != null && path in session.project && path != session.state.value.activePath) session.show(path)
        }
        val activePath = state?.activePath
        LaunchedEffect(activePath, sidebar, session) {
            val route = navController.currentBackStackEntry?.destination?.route
            if (!sidebar) {
                // a window narrowed from the sidebar layout: a file's route is a page of Write there, with a Back
                // arrow on the top-level screen and the file's name twice; Write itself shows the same file
                if (Routes.filePath(route) != null) navigator.navigate(Routes.WRITE)
                return@LaunchedEffect
            }
            if (activePath == null) return@LaunchedEffect
            if (Routes.isWorkspace(route) && Routes.filePath(route) != activePath) navigator.navigate(Routes.file(activePath))
        }
        DesktopShell(
            spec = spec,
            widthClass = widthClass,
            navController = navController,
            sidebarCollapsed = prefs.sidebarCollapsed,
            onSidebarCollapsedChange = prefs::updateSidebarCollapsed,
            badges = badges,
            // a phone keeps Issues under "⋯"; its count is on the editor's top bar instead
            iconBadges = if (widthClass == WidthClass.Compact) emptyMap() else navIconBadges(issueCount, issuesDescribed),
            navMenu = { route ->
                val path = Routes.filePath(route)
                val file = path?.let { project?.file(it) }
                if (file == null) emptyList() else fileMenu(file, resources, fileActions)
            },
            menus = menus.menuBar(),
            onSearch = { palette.open() },
            searchHint = stringResource(R.string.palette_hint),
            searchShortcut = if (showKeys) shortcuts.chordLabel(CommonShortcuts.SEARCH, resources) else null,
            transitions = transitions,
            moreActions = menus.moreSheet(),
            toasts = toasts,
            overlays = {
                GotoAnything(palette, session, state, project, actions, spec, storage)
                WindowSheets(session, state, ui)
            },
        ) { route, shellNavigator ->
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // the room the screens really have, inside the bars or beside the rail or the sidebar
                val twoPanes = fitsTwoPanes(maxWidth)
                SideEffect { if (ui.twoPanes != twoPanes) ui.twoPanes = twoPanes }
                RouteContent(
                    route = route,
                    navigator = shellNavigator,
                    compactHeight = maxHeight < COMPACT_HEIGHT,
                    window = window,
                    session = session,
                    state = state,
                    live = live,
                    settings = settings,
                    storage = storage,
                    prefs = prefs,
                    ui = ui,
                    actions = actions,
                    editorZoom = editorZoom,
                    playerZoom = playerZoom,
                    twoPanes = twoPanes,
                    availableWidth = maxWidth,
                )
            }
        }
        WindowDialogs(session, state, storage, ui, actions)
    }
}

/** The screen of one route, for whatever the window has open. */
@Composable
private fun RouteContent(
    compactHeight: Boolean,
    route: String,
    navigator: ShellNavigator,
    window: WindowProject,
    session: ProjectSession?,
    state: SessionState?,
    live: LiveState?,
    settings: AppSettings,
    storage: ProjectStorage,
    prefs: DeskPreferences,
    ui: WindowUi,
    actions: WindowActions,
    editorZoom: CodeEditorZoom,
    playerZoom: CodeEditorZoom,
    twoPanes: Boolean,
    availableWidth: androidx.compose.ui.unit.Dp,
) {
    // screens of their own, whatever is open
    when (route) {
        Routes.PROJECTS -> {
            ProjectsScreen(storage, holderReference(window), ui, actions)
            return
        }
        Routes.DOCS -> {
            DocsScreen(prefs, insertIntoEditor = if (session == null) null else actions::insertSnippet)
            return
        }
        Routes.SETTINGS -> {
            SettingsScreen(prefs, settings, shortcuts)
            return
        }
    }
    if (session == null || state == null) {
        NoProject(route, window, storage, ui, actions)
        return
    }
    val project = session.project.state.collectAsState().value
    val liveState = live ?: return
    when {
        Routes.isWorkspace(route) -> WorkspaceScreen(
            session = session,
            state = state,
            project = project,
            live = liveState,
            settings = settings,
            ui = ui,
            actions = actions,
            editorZoom = editorZoom,
            playerZoom = playerZoom,
            twoPanes = twoPanes,
            availableWidth = availableWidth,
            compactHeight = compactHeight,
        )
        route == Routes.PLAY -> {
            if (twoPanes) {
                // the player has its place beside the editor now: the window grew under it
                LaunchedEffect(Unit) {
                    settings.updateInspectorTab(InspectorTab.Player)
                    navigator.navigate(Routes.WRITE)
                }
            } else {
                PlayScreen(session, liveState, settings, ui, actions, playerZoom)
            }
        }
        route == Routes.FILES -> FilesScreen(session, state, project, ui, actions)
        route == Routes.ISSUES -> IssuesScreen(state, project, ui, actions)
    }
}

/** The reference of the project the window shows or is opening, to mark it in the lists. */
private fun holderReference(window: WindowProject): String? = when (window) {
    is WindowProject.Open -> window.session.reference
    is WindowProject.Opening -> window.reference
    is WindowProject.Failed -> window.reference
    WindowProject.None -> null
}

/** The key under which the editor's zoom is kept in the kit's preferences; likewise the player's. */
private const val EDITOR_ZOOM_KEY = "editor"
private const val PLAYER_ZOOM_KEY = "player"

/**
 * The window's navigation, never saved with the activity's state. The graph is built from the
 * project's files (the sidebar lists each as a route of its own), and after the system ended the
 * process those routes are not there until the project has opened again: a saved back stack that
 * holds one cannot be restored, and the window would fail at every such start. What the window
 * showed comes back through [RouteMemory] instead. (The activity handles configuration changes
 * itself, so nothing else would restore the back stack.)
 */
@Composable
private fun rememberWindowNavController(): NavHostController {
    lateinit var controller: NavHostController
    // the library's own controller, made where there is no registry to save it in
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) { controller = rememberNavController() }
    return controller
}

/**
 * The route a window shows, kept in its saved state ([shown]: a screen other than the start one and
 * not a file's), and after a restore the one to show again ([pending]).
 */
internal class RouteMemory(restored: String? = null) {
    var shown: String? = null
    var pending: String? = restored

    companion object {
        val Saver: Saver<RouteMemory, String> = Saver(save = { memory -> memory.shown }, restore = { route -> RouteMemory(restored = route) })
    }
}

/** A window lower than this (a phone on its side) has its workspace's bars folded into one. */
private val COMPACT_HEIGHT = 480.dp

/** How much of a copied text names it on the clipboard. */
private const val CLIP_LABEL_LENGTH = 40
