package com.inkyandroid.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.inkyandroid.R
import com.inkyandroid.core.symbols.FlowPath
import com.inkyandroid.core.symbols.FlowSymbol
import com.inkyandroid.core.symbols.FlowType
import com.inkyandroid.core.symbols.InkFileSymbols
import com.inkyandroid.ui.InkIcons
import desktopkit.components.DeskListRow
import desktopkit.components.SectionCaption
import desktopkit.components.verticalScrollbar
import desktopkit.screens.MessageScreen
import desktopkit.theme.DeskTheme

/** A row of the outline: a group's title, or a knot, function or stitch of the file. */
sealed interface OutlineRow {
    data class Group(val kind: OutlineGroup) : OutlineRow
    data class Symbol(val symbol: FlowSymbol, val isStitch: Boolean) : OutlineRow
}

/** Desktop Inky's three groups of its knots view. */
enum class OutlineGroup { Content, Functions, Externals }

/**
 * The rows of the outline for a file, as desktop Inky's knots view lists them: every top-level
 * symbol in source order followed by its stitches (a stitch name declared twice shows once; labels
 * are not listed), sorted into "Content" (knots that are no functions), "Functions", and
 * "Externals" (functions that stand in for an `EXTERNAL` of the same name). A group without rows
 * is left out.
 */
fun outlineRows(symbols: InkFileSymbols): List<OutlineRow> {
    val groups = LinkedHashMap<OutlineGroup, MutableList<OutlineRow>>()
    OutlineGroup.entries.forEach { groups[it] = mutableListOf() }
    for (symbol in symbols.root) {
        val group = when {
            !symbol.isFunction -> OutlineGroup.Content
            symbol.name in symbols.externals -> OutlineGroup.Externals
            else -> OutlineGroup.Functions
        }
        val rows = groups.getValue(group)
        rows += OutlineRow.Symbol(symbol, isStitch = false)
        symbol.innerSymbols.values.filter { it.flowType == FlowType.Stitch }.forEach { rows += OutlineRow.Symbol(it, isStitch = true) }
    }
    return groups.filterValues { it.isNotEmpty() }.flatMap { (group, rows) -> listOf(OutlineRow.Group(group)) + rows }
}

/**
 * The outline of the file in the editor: its knots with their stitches, its functions and its
 * externals. The knot and the stitch the cursor is in are marked and kept in view; activating a row
 * moves the cursor to it.
 *
 * @param symbols the file's symbols, null while it has not been parsed yet.
 * @param cursorRow the zero-based row of the cursor, for the marks.
 * @param fileName the file's name, said above the outline where nothing else says which file it is.
 */
@Composable
fun OutlinePane(
    symbols: InkFileSymbols?,
    cursorRow: Int,
    onOpen: (FlowSymbol) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    fileName: String? = null,
) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val rows = remember(symbols) { symbols?.let(::outlineRows).orEmpty() }
    if (rows.isEmpty()) {
        Box(modifier.fillMaxSize().padding(contentPadding)) {
            MessageScreen(title = stringResource(R.string.outline_content), icon = InkIcons.Outline, description = stringResource(R.string.outline_empty))
        }
        return
    }
    val current: FlowPath = remember(symbols, cursorRow) { symbols?.flowAt(cursorRow) ?: FlowPath() }
    // the deepest marked row is kept in sight as the cursor moves through the file
    val marked = rows.indexOfLast { it is OutlineRow.Symbol && (it.symbol === current.stitch || it.symbol === current.knot) }
    LaunchedEffect(marked) {
        if (marked >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == marked }) listState.animateScrollToItem((marked - 1).coerceAtLeast(0))
    }
    val here = stringResource(R.string.outline_current)
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().background(colors.background).verticalScrollbar(listState),
        contentPadding = contentPadding,
    ) {
        if (fileName != null) {
            item(key = "file") {
                Text(
                    fileName,
                    style = DeskTheme.type.labelSmall,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.padding(start = dimens.pagePadding, end = dimens.pagePadding, top = dimens.gapM),
                )
            }
        }
        items(rows.size, key = { index -> rowKey(rows[index], index) }) { index ->
            when (val row = rows[index]) {
                is OutlineRow.Group -> SectionCaption(
                    stringResource(
                        when (row.kind) {
                            OutlineGroup.Content -> R.string.outline_content
                            OutlineGroup.Functions -> R.string.outline_functions
                            OutlineGroup.Externals -> R.string.outline_externals
                        },
                    ),
                    Modifier.padding(start = dimens.pagePadding, end = dimens.pagePadding, top = dimens.gapL, bottom = dimens.gapXs),
                )
                is OutlineRow.Symbol -> {
                    val symbol = row.symbol
                    val isCurrent = symbol === current.knot || symbol === current.stitch
                    DeskListRow(
                        title = symbol.name,
                        modifier = if (isCurrent) Modifier.semantics { stateDescription = here } else Modifier,
                        leading = when {
                            row.isStitch -> InkIcons.Stitch
                            symbol.isFunction -> if (symbol.name in symbols!!.externals) InkIcons.External else InkIcons.Function
                            else -> InkIcons.Knot
                        },
                        indent = if (row.isStitch) dimens.gapL else 0.dp,
                        selected = isCurrent,
                        divider = false,
                        onClick = { onOpen(symbol) },
                    )
                }
            }
        }
    }
}

private fun rowKey(row: OutlineRow, index: Int): String = when (row) {
    is OutlineRow.Group -> "group:" + row.kind.name
    // a name can be declared twice in a file: the place in the list tells the rows apart
    is OutlineRow.Symbol -> "symbol:" + row.symbol.path + ":" + index
}
