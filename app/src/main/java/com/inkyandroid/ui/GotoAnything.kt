package com.inkyandroid.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import com.inkyandroid.R
import com.inkyandroid.core.project.ProjectSnapshot
import com.inkyandroid.core.search.GotoCandidates
import com.inkyandroid.core.search.GotoQuery
import com.inkyandroid.core.search.GotoSymbol
import com.inkyandroid.core.search.SearchableFile
import com.inkyandroid.core.search.TextSearchIndex
import com.inkyandroid.core.symbols.FileSymbols
import com.inkyandroid.core.symbols.FlowType
import com.inkyandroid.core.symbols.InkFileSymbols
import com.inkyandroid.core.syntax.InkLines
import com.inkyandroid.session.ProjectSession
import com.inkyandroid.session.SessionState
import com.inkyandroid.storage.ProjectStorage
import com.inkyandroid.ui.extras.snippetPaletteItems
import desktopkit.appResources
import desktopkit.components.CommandPalette
import desktopkit.components.CommandPaletteState
import desktopkit.components.PaletteItem
import desktopkit.components.paletteItems
import desktopkit.shell.LocalNavKind
import desktopkit.shell.ShellSpec
import kotlinx.coroutines.launch

/**
 * A snapshot of the project for one opening of "Go to anything", as upstream takes one when its
 * panel opens: the files, the symbols (every level of the file in the editor, the top level of the
 * others), the lines of the file in the editor and an index over every line of every file.
 */
class GotoSnapshot(val activePath: String, val candidates: GotoCandidates, val activeLines: List<String>, val text: TextSearchIndex) {
    companion object {
        fun of(session: ProjectSession, project: ProjectSnapshot, state: SessionState): GotoSnapshot {
            val empty by lazy { InkFileSymbols.parse("", state.tokenizer) }
            val symbols = project.files.map { file -> FileSymbols(file.path, file.symbols ?: empty) }
            val texts = project.files.mapNotNull { file -> session.project.text(file.path)?.let { SearchableFile(file.path, it) } }
            val active = session.project.text(state.activePath) ?: ""
            return GotoSnapshot(
                activePath = state.activePath,
                candidates = GotoCandidates.collect(symbols, state.activePath),
                activeLines = InkLines.split(active),
                text = TextSearchIndex(texts, state.activePath),
            )
        }
    }
}

/** The group names of the palette's project entries, in upstream's order. */
class GotoGroups(val goto: String, val files: String, val symbols: String, val text: String) {
    companion object {
        fun of(resources: Resources) = GotoGroups(
            goto = resources.getString(R.string.palette_group_goto),
            files = resources.getString(R.string.palette_group_files),
            symbols = resources.getString(R.string.palette_group_symbols),
            text = resources.getString(R.string.palette_group_text),
        )
    }
}

/**
 * The palette's entries of the project for [query], in upstream's order: the line of the file in
 * the editor when the query is a number, the internal runtime path when it looks like one, the
 * files, the knots and stitches, and the lines that contain the query. The palette ranks within the
 * groups itself; upstream's limit of 1,000 results holds for the whole list.
 *
 * @param openLine shows a zero-based row of a file, with a history step.
 * @param lookUpPath resolves an internal runtime path through the live story.
 */
fun gotoItems(
    query: String,
    snapshot: GotoSnapshot,
    groups: GotoGroups,
    resources: Resources,
    openFile: (String) -> Unit,
    openLine: (path: String, row: Int, column: Int) -> Unit,
    lookUpPath: (String) -> Unit,
): List<PaletteItem> {
    val items = ArrayList<PaletteItem>()
    val trimmed = query.trim()
    GotoQuery.lineRow(query)?.let { row ->
        val number = row + 1
        if (row < snapshot.activeLines.size) {
            items += PaletteItem(
                id = "goto-line",
                title = resources.getString(R.string.palette_goto_line, number),
                group = groups.goto,
                onSelect = { openLine(snapshot.activePath, row, 0) },
                subtitle = snapshot.activeLines[row].trim().ifEmpty { null },
                icon = InkIcons.ArrowRight,
                keywords = listOf(number.toString()),
            )
        }
    }
    GotoQuery.runtimePath(trimmed)?.let { path ->
        items += PaletteItem(
            id = "runtime-path",
            title = resources.getString(R.string.palette_runtime_path_lookup, path),
            group = groups.goto,
            onSelect = { lookUpPath(path) },
            icon = DeskSearchIcon,
            keywords = listOf(path),
        )
    }
    if (trimmed.isEmpty()) return items
    snapshot.candidates.files.forEach { file ->
        items += PaletteItem(
            id = "file:" + file.path,
            title = file.name,
            group = groups.files,
            onSelect = { openFile(file.path) },
            subtitle = file.directory.ifEmpty { null },
            icon = InkIcons.File,
            keywords = listOf(file.path),
        )
    }
    (snapshot.candidates.activeFileSymbols + snapshot.candidates.otherSymbols).forEachIndexed { index, symbol ->
        items += PaletteItem(
            id = "symbol:$index:" + symbol.path + ":" + symbol.row,
            title = symbol.name,
            group = groups.symbols,
            onSelect = { openLine(symbol.path, symbol.row, symbol.column) },
            subtitle = symbolPlace(resources, symbol),
            icon = symbolIcon(symbol),
            keywords = listOf(symbol.ancestors + symbol.name),
        )
    }
    val room = (MAX_RESULTS - items.size).coerceAtLeast(0)
    snapshot.text.find(trimmed, room).forEach { match ->
        items += PaletteItem(
            id = "text:" + match.path + ":" + match.row,
            title = match.line.trim(),
            group = groups.text,
            onSelect = { openLine(match.path, match.row, match.matchStart) },
            subtitle = resources.getString(R.string.palette_line_place, match.path, match.row + 1),
            icon = InkIcons.TextLine,
        )
    }
    return items
}

private val DeskSearchIcon get() = desktopkit.components.DeskIcons.Search

/** "chapters/one.ink – line 12", with the knot and stitch a symbol is in before it ("in knot.stitch · …"). */
private fun symbolPlace(resources: Resources, symbol: GotoSymbol): String {
    val outer = symbol.ancestors.trimEnd('.')
    return if (outer.isEmpty()) {
        resources.getString(R.string.palette_line_place, symbol.path, symbol.row + 1)
    } else {
        resources.getString(R.string.palette_symbol_place, outer, symbol.path, symbol.row + 1)
    }
}

private fun symbolIcon(symbol: GotoSymbol) = when {
    symbol.isFunction -> InkIcons.Function
    symbol.flowType == FlowType.Knot -> InkIcons.Knot
    symbol.flowType == FlowType.Stitch -> InkIcons.Stitch
    else -> InkIcons.Label
}

/** Upstream stops building results at a thousand. */
private const val MAX_RESULTS = 1000

/**
 * The window's command palette: Ctrl+K (the kit's) and Ctrl+P (upstream's "Go to anything") open
 * it. Its entries: the project's places ([gotoItems]), the screens, the recent projects, the
 * snippets and every command of the menus.
 */
@Composable
internal fun GotoAnything(
    palette: CommandPaletteState,
    session: ProjectSession?,
    state: SessionState?,
    project: ProjectSnapshot?,
    actions: WindowActions,
    spec: ShellSpec,
    storage: ProjectStorage,
) {
    val resources = appResources()
    val scope = rememberCoroutineScope()
    val navKind = LocalNavKind.current
    val recents by storage.recents.entries.collectAsState()
    // the project as it was when the palette opened (upstream takes the same snapshot)
    val snapshot = remember(palette.isOpen, session) {
        if (palette.isOpen && session != null && project != null && state != null) GotoSnapshot.of(session, project, state) else null
    }
    val groups = remember(resources) { GotoGroups.of(resources) }
    val screensGroup = stringResource(R.string.palette_group_screens)
    val projectsGroup = stringResource(R.string.palette_group_projects)
    val notFound = stringResource(R.string.palette_runtime_path_unknown)
    CommandPalette(
        state = palette,
        items = { query ->
            val places = if (snapshot == null) emptyList() else gotoItems(
                query = query,
                snapshot = snapshot,
                groups = groups,
                resources = resources,
                openFile = actions::openFile,
                openLine = { path, row, column -> actions.openLine(path, row, column) },
                lookUpPath = { path ->
                    scope.launch {
                        val place = session?.live?.session?.locateRuntimePath(path)
                        if (place != null) actions.openLine(place.file, (place.line - 1).coerceAtLeast(0)) else actions.notify(String.format(notFound, path))
                    }
                },
            )
            // the screens this window's navigation shows; a file's own entry is among the places
            val screens = spec.items.filter { navKind in it.showIn && Routes.filePath(it.route) == null }.map { item ->
                PaletteItem(id = "screen:" + item.route, title = item.label, group = screensGroup, onSelect = { actions.navigate(item.route) }, icon = item.icon)
            }
            val projects = recents.filter { it.ref.encode() != session?.reference }.map { recent ->
                PaletteItem(
                    id = "project:" + recent.ref.key,
                    title = recent.ref.displayName,
                    group = projectsGroup,
                    onSelect = { actions.openProject(recent.ref) },
                    subtitle = recent.ref.mainPath,
                    icon = desktopkit.components.DeskIcons.Folder,
                )
            }
            val snippets = if (session == null) emptyList() else snippetPaletteItems(session, resources) { ink -> actions.insertSnippet(ink) }
            val commands = shortcuts.paletteItems(resources) { id -> actions.perform(id) }.filter { actions.available(it.id) } + extraCommandItems(resources, actions)
            places + screens + projects + snippets + commands
        },
        filters = listOf(groups.files, groups.symbols, groups.text, resources.getString(R.string.snippets_group)),
        hint = stringResource(R.string.palette_hint),
    )
}

/** The commands of the menus that have no key, and so are not in the shortcut table's palette entries. */
private fun extraCommandItems(resources: Resources, actions: WindowActions): List<PaletteItem> {
    val group = resources.getString(R.string.menu_story)
    val file = resources.getString(R.string.menu_file)
    return listOfNotNull(
        PaletteItem("cmd:" + Commands.ADD_WATCH, resources.getString(R.string.action_add_watch), group, { actions.perform(Commands.ADD_WATCH) }, icon = desktopkit.components.DeskIcons.Command),
        PaletteItem("cmd:" + Commands.OPEN_RECENT, resources.getString(R.string.action_open_recent), file, { actions.perform(Commands.OPEN_RECENT) }, icon = desktopkit.components.DeskIcons.Command),
        if (actions.enabled(Commands.KEPT_VERSIONS)) {
            PaletteItem("cmd:" + Commands.KEPT_VERSIONS, resources.getString(R.string.action_kept_versions), file, { actions.perform(Commands.KEPT_VERSIONS) }, icon = desktopkit.components.DeskIcons.Command)
        } else {
            null
        },
        if (actions.available(Commands.EXPORT_WEB)) {
            PaletteItem("cmd:" + Commands.EXPORT_WEB, resources.getString(R.string.action_export_web), file, { actions.perform(Commands.EXPORT_WEB) }, icon = desktopkit.components.DeskIcons.Command)
        } else {
            null
        },
    )
}
