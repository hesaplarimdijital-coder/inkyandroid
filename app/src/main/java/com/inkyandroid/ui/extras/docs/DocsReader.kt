package com.inkyandroid.ui.extras.docs

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkyandroid.R
import com.inkyandroid.core.syntax.InkTokenizer
import com.inkyandroid.ink.InkHighlight
import com.inkyandroid.ink.inkSyntax
import desktopkit.components.DeskIconButton
import desktopkit.components.DeskIcons
import desktopkit.components.DeskListRow
import desktopkit.components.DeskSearchField
import desktopkit.components.GlassBottomSheet
import desktopkit.components.HorizontalSplitPane
import desktopkit.components.rememberSplitState
import desktopkit.components.verticalScrollbar
import desktopkit.editor.CodeEditorZoom
import desktopkit.editor.rememberCodeEditorZoom
import desktopkit.editor.textZoom
import desktopkit.screens.PageHeader
import desktopkit.settings.DeskPreferences
import desktopkit.shell.CompactTopBar
import desktopkit.shell.TopBarAction
import desktopkit.shell.WidthClass
import desktopkit.shell.currentWidthClass
import desktopkit.shell.rememberShellContentPadding
import desktopkit.theme.DeskTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Names the tests find the screen's parts by. */
object DocsTags {
    const val TEXT = "docs-text"
    const val CONTENTS = "docs-contents"
    const val FIND = "docs-find"
    const val FIND_COUNT = "docs-find-count"
    const val CONTENTS_SHEET = "docs-contents-sheet"
}

/**
 * The manual's screen once the manual is parsed: the layout by the window's width, find, zoom,
 * the table of contents, and the reading position kept in [memory].
 */
@Composable
internal fun DocsReader(
    manual: Manual,
    prefs: DeskPreferences,
    insertIntoEditor: ((String) -> Unit)?,
    memory: DocsMemory = DocsMemory.of(LocalContext.current),
) {
    val widthClass = currentWidthClass()
    // the contents fit beside a comfortable measure of text from the tablet's width on
    val wide = widthClass >= WidthClass.Expanded
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // the editor's zoom (its steps, keys, wheel and pinch), with a size of the manual's own
    val zoom = rememberCodeEditorZoom(prefs, DOCS_ZOOM_KEY)
    val listState = remember(manual, memory) {
        val position = memory.position
        LazyListState(position.block.coerceIn(0, manual.blocks.lastIndex), position.offset.coerceAtLeast(0))
    }
    RememberPosition(listState, memory)

    // find
    var findOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(manual, query) { manual.find(query) }
    var matchIndex by rememberSaveable(query) { mutableIntStateOf(0) }
    val current = matches.getOrNull(matchIndex)
    val findFocus = remember { FocusRequester() }

    // the phone's (and the rail's) bars lie over the text: scrolled-to blocks stop below the top one
    val shell = rememberShellContentPadding()
    val findShownAbove = !wide && findOpen
    val topInsetPx = with(density) { (if (findShownAbove) 0.dp else shell.calculateTopPadding()).roundToPx() }
    fun scrollTo(block: Int) {
        scope.launch {
            // a short way is shown, a long one (across chapters) is a jump: scrolling past pages of text says nothing
            if (kotlin.math.abs(block - listState.firstVisibleItemIndex) <= ANIMATED_SCROLL_BLOCKS) {
                listState.animateScrollToItem(block, -topInsetPx)
            } else {
                listState.scrollToItem(block, -topInsetPx)
            }
        }
    }
    fun showMatch(index: Int) {
        if (matches.isEmpty()) return
        matchIndex = Math.floorMod(index, matches.size)
        scrollTo(matches[matchIndex].block)
    }
    // a new query starts from the first match at or after what is on screen
    LaunchedEffect(matches) {
        if (matches.isEmpty()) return@LaunchedEffect
        val top = listState.firstVisibleItemIndex
        showMatch(matches.indexOfFirst { it.block >= top }.takeIf { it >= 0 } ?: 0)
    }
    val openFind = {
        findOpen = true
        scope.launch {
            // the field exists from the next frame on when find was closed
            delay(FOCUS_DELAY_MS)
            runCatching { findFocus.requestFocus() }
        }
        Unit
    }

    val section by remember(manual, listState) { derivedStateOf { manual.contentsEntryAt(listState.firstVisibleItemIndex) } }
    var contentsSheet by rememberSaveable { mutableStateOf(false) }
    val openSection = { entry: ManualSection ->
        contentsSheet = false
        scrollTo(entry.block)
    }

    val keys = Modifier.onPreviewKeyEvent { event ->
        docsKey(event, zoom, openFind = openFind, next = { showMatch(matchIndex + 1) }, previous = { showMatch(matchIndex - 1) })
    }

    val findControls: @Composable (Modifier) -> Unit = { fieldModifier ->
        FindControls(
            query = query,
            onQueryChange = { query = it },
            count = matches.size,
            position = if (current != null) matchIndex + 1 else 0,
            onNext = { showMatch(matchIndex + 1) },
            onPrevious = { showMatch(matchIndex - 1) },
            onClose = if (wide) null else ({ findOpen = false; query = "" }),
            fieldModifier = fieldModifier.focusRequester(findFocus),
        )
    }

    val reader: @Composable (PaddingValues) -> Unit = { padding ->
        ManualText(
            manual = manual,
            listState = listState,
            zoom = zoom,
            matches = matches,
            current = current,
            contentPadding = padding,
            insertIntoEditor = if (wide) insertIntoEditor else null,
            onInternalLink = { anchor -> manual.resolve(anchor)?.let(::scrollTo) },
        )
    }

    if (wide) {
        val split = rememberSplitState(CONTENTS_WIDTH, prefs, SPLIT_KEY)
        Column(Modifier.fillMaxSize().then(keys)) {
            PageHeader(stringResource(R.string.docs_title), subtitle = stringResource(R.string.docs_subtitle)) {
                findControls(Modifier.widthIn(max = FIND_FIELD_WIDTH))
                ZoomButtons(zoom)
            }
            HorizontalSplitPane(
                state = split,
                minStart = CONTENTS_MIN_WIDTH,
                minEnd = TEXT_MIN_WIDTH,
                start = { ManualContents(manual, section, openSection, PaddingValues(bottom = DeskTheme.dimens.gapS)) },
                end = { reader(PaddingValues(bottom = DeskTheme.dimens.gapXl)) },
            )
        }
    } else {
        CompactTopBar(
            actions = listOf(
                TopBarAction(DeskIcons.ListView, stringResource(R.string.docs_contents), { contentsSheet = true }),
                TopBarAction(DeskIcons.Search, stringResource(R.string.docs_find), openFind),
                // past the bar's room: they go to its "⋮" menu
                TopBarAction(DeskIcons.Plus, stringResource(R.string.docs_zoom_in), zoom::zoomIn, enabled = zoom.scale < zoom.maxScale),
                TopBarAction(DeskIcons.Minus, stringResource(R.string.docs_zoom_out), zoom::zoomOut, enabled = zoom.scale > zoom.minScale),
            ),
            showSearch = false,
            showOnRail = true,
        )
        Column(Modifier.fillMaxSize().then(keys)) {
            if (findOpen) {
                Row(
                    Modifier.fillMaxWidth().padding(top = shell.calculateTopPadding())
                        .padding(horizontal = DeskTheme.dimens.pagePadding, vertical = DeskTheme.dimens.gapS),
                    verticalAlignment = Alignment.CenterVertically,
                ) { findControls(Modifier.weight(1f)) }
            }
            reader(
                PaddingValues(
                    top = if (findOpen) 0.dp else shell.calculateTopPadding(),
                    bottom = shell.calculateBottomPadding() + DeskTheme.dimens.gapXl,
                ),
            )
        }
        GlassBottomSheet(visible = contentsSheet, onDismiss = { contentsSheet = false }, title = stringResource(R.string.docs_contents)) {
            Box(Modifier.heightIn(max = SHEET_LIST_HEIGHT).testTag(DocsTags.CONTENTS_SHEET)) {
                ManualContents(manual, section, openSection, PaddingValues(bottom = DeskTheme.dimens.gapS))
            }
        }
    }
}

/** Ctrl+F, F3 / Shift+F3, Ctrl with + − 0, as in the editor; true if the key was used. */
private fun docsKey(event: KeyEvent, zoom: CodeEditorZoom, openFind: () -> Unit, next: () -> Unit, previous: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val ctrl = event.isCtrlPressed || event.isMetaPressed
    val char = event.utf16CodePoint.toChar()
    return when {
        ctrl && event.key == Key.F -> openFind().let { true }
        event.key == Key.F3 -> (if (event.isShiftPressed) previous() else next()).let { true }
        ctrl && (event.key == Key.Equals || event.key == Key.Plus || event.key == Key.NumPadAdd || char == '+') -> zoom.zoomIn().let { true }
        ctrl && (event.key == Key.Minus || event.key == Key.NumPadSubtract || char == '-') -> zoom.zoomOut().let { true }
        ctrl && (event.key == Key.Zero || event.key == Key.NumPad0) -> zoom.reset().let { true }
        else -> false
    }
}

/** Saves the reading position whenever scrolling comes to rest, and when the screen goes. */
@Composable
private fun RememberPosition(listState: LazyListState, memory: DocsMemory) {
    LaunchedEffect(listState, memory) {
        snapshotFlow {
            if (listState.isScrollInProgress) null else DocsMemory.Position(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
            .filterNotNull()
            .collect(memory::updatePosition)
    }
    DisposableEffect(listState, memory) {
        onDispose { memory.updatePosition(DocsMemory.Position(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)) }
    }
}

/** The manual's blocks at a reading measure, with find's marks, links and the examples' actions. */
@Composable
private fun ManualText(
    manual: Manual,
    listState: LazyListState,
    zoom: CodeEditorZoom,
    matches: List<ManualMatch>,
    current: ManualMatch?,
    contentPadding: PaddingValues,
    insertIntoEditor: ((String) -> Unit)?,
    onInternalLink: (String) -> Unit,
) {
    val type = DeskTheme.type
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val scale = zoom.scale
    val styles = remember(type, colors, dimens, scale) { ManualStyles(type, colors, dimens, scale) }
    val byBlock = remember(matches) { matches.groupBy { it.block } }
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val actions = CodeActions(
        copyLabel = stringResource(R.string.docs_copy),
        copy = { code -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("ink", code))) } },
        insertLabel = stringResource(R.string.docs_insert),
        insert = insertIntoEditor,
    )
    val onLink = { link: LinkSpan ->
        val anchor = link.anchor
        if (anchor != null) onInternalLink(anchor) else runCatching { uriHandler.openUri(link.target) }
        Unit
    }
    val colourCode = rememberCodeColouring()
    // the measure grows with the text, so a zoomed page keeps about the same words to a line
    val measure = MEASURE * scale
    SelectionContainer(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            // Ctrl with the wheel and a pinch over the text, as over the editor
            modifier = Modifier.fillMaxSize().textZoom(zoom).verticalScrollbar(listState).testTag(DocsTags.TEXT),
        ) {
            itemsIndexed(manual.blocks, contentType = { _, block -> block::class }) { index, block ->
                val found = byBlock[index]
                val blockMatches = if (found == null) {
                    BlockMatches.NONE
                } else {
                    BlockMatches(found.map { it.start until it.end }, current?.takeIf { it.block == index }?.let { it.start until it.end })
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding), contentAlignment = Alignment.TopCenter) {
                    ManualBlockView(block, styles, blockMatches, onLink, colourCode, actions, Modifier.widthIn(max = measure))
                }
            }
        }
    }
}

/**
 * How the manual's examples are coloured: `:core`'s tokenizer in the editor's roles and colours
 * (tags as the editor shows them while they are visible). Each example is coloured once per theme,
 * when it first scrolls into view.
 */
@Composable
private fun rememberCodeColouring(): (ManualBlock.Code) -> AnnotatedString {
    val syntax = inkSyntax(DeskTheme.syntax, tagsVisible = true)
    val tokenizer = remember { InkTokenizer() }
    return remember(syntax) {
        val coloured = HashMap<ManualBlock.Code, AnnotatedString>()
        val colour = { block: ManualBlock.Code -> coloured.getOrPut(block) { colourCode(block, InkHighlight.spans(block.code, tokenizer), syntax) } }
        colour
    }
}

/** The table of contents: parts, chapters and sections, the reader's current one marked and kept in view. */
@Composable
private fun ManualContents(manual: Manual, current: ManualSection?, onOpen: (ManualSection) -> Unit, contentPadding: PaddingValues) {
    val state = rememberLazyListState()
    val currentIndex = current?.let { manual.contents.indexOf(it) } ?: -1
    LaunchedEffect(currentIndex) {
        if (currentIndex < 0) return@LaunchedEffect
        val visible = state.layoutInfo.visibleItemsInfo
        val shown = visible.any { it.index == currentIndex && it.offset >= 0 && it.offset + it.size <= state.layoutInfo.viewportEndOffset }
        if (!shown) state.scrollToItem((currentIndex - CONTENTS_CONTEXT).coerceAtLeast(0))
    }
    LazyColumn(state = state, contentPadding = contentPadding, modifier = Modifier.fillMaxSize().verticalScrollbar(state).testTag(DocsTags.CONTENTS)) {
        itemsIndexed(manual.contents, key = { _, entry -> entry.anchor }) { index, entry ->
            DeskListRow(
                title = entry.title,
                strong = entry.level == 1,
                indent = DeskTheme.dimens.gapL * (entry.level - 1),
                selected = index == currentIndex,
                divider = false,
                onClick = { onOpen(entry) },
            )
        }
    }
}

/** The find field with the count, previous and next, and (on a phone) a way to close it. */
@Composable
private fun FindControls(
    query: String,
    onQueryChange: (String) -> Unit,
    count: Int,
    position: Int,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: (() -> Unit)?,
    fieldModifier: Modifier,
) {
    val dimens = DeskTheme.dimens
    DeskSearchField(
        value = query,
        onValueChange = onQueryChange,
        hint = stringResource(R.string.docs_find_hint),
        onSearch = { onNext() },
        modifier = fieldModifier.testTag(DocsTags.FIND).onPreviewKeyEvent { event ->
            // Enter goes to the next match, Shift+Enter to the one before; Esc on an empty field closes find
            when {
                event.type != KeyEventType.KeyDown -> false
                event.key == Key.Enter && event.isShiftPressed -> onPrevious().let { true }
                event.key == Key.Escape && query.isEmpty() && onClose != null -> onClose().let { true }
                else -> false
            }
        },
    )
    if (query.isNotEmpty()) {
        val description = pluralStringResource(R.plurals.docs_find_results, count, count)
        Text(
            if (count == 0) stringResource(R.string.docs_find_none) else stringResource(R.string.docs_find_position, position, count),
            style = DeskTheme.type.label,
            color = if (count == 0) DeskTheme.colors.danger else DeskTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = dimens.gapS).testTag(DocsTags.FIND_COUNT).semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        DeskIconButton(DeskIcons.ChevronUp, stringResource(R.string.docs_find_previous), onPrevious, enabled = count > 0, shortcut = "Shift+F3")
        DeskIconButton(DeskIcons.ChevronDown, stringResource(R.string.docs_find_next), onNext, enabled = count > 0, shortcut = "F3")
        if (onClose != null) DeskIconButton(DeskIcons.Close, stringResource(R.string.docs_find_close), onClose)
    }
}

/** − and + for the text size; Ctrl+0 (and nothing on screen) goes back to the theme's. */
@Composable
private fun ZoomButtons(zoom: CodeEditorZoom) {
    DeskIconButton(DeskIcons.Minus, stringResource(R.string.docs_zoom_out), zoom::zoomOut, enabled = zoom.scale > zoom.minScale, shortcut = "Ctrl+−")
    DeskIconButton(DeskIcons.Plus, stringResource(R.string.docs_zoom_in), zoom::zoomIn, enabled = zoom.scale < zoom.maxScale, shortcut = "Ctrl++")
}

private val CONTENTS_WIDTH = 280.dp
private val CONTENTS_MIN_WIDTH = 200.dp
private val TEXT_MIN_WIDTH = 360.dp
private val FIND_FIELD_WIDTH = 320.dp
private val SHEET_LIST_HEIGHT = 520.dp

/** About 75 characters of body text to a line: the measure books are set to. */
private val MEASURE = 680.dp
private const val SPLIT_KEY = "docs"

/** The key of the manual's text size among the kit's preferences (the editor's is another). */
internal const val DOCS_ZOOM_KEY = "docs"
private const val CONTENTS_CONTEXT = 3
private const val ANIMATED_SCROLL_BLOCKS = 12
private const val FOCUS_DELAY_MS = 50L
