package com.inkyandroid.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.inkyandroid.R
import com.inkyandroid.ui.InkIcons
import desktopkit.components.DeskButton
import desktopkit.components.DeskIconButton
import desktopkit.components.DeskIcons
import desktopkit.components.HorizontalRule
import desktopkit.editor.CodeEditorState
import desktopkit.editor.CodeIndent
import desktopkit.shell.keepsKeyboardOpen
import desktopkit.theme.DeskTheme

/**
 * One key of the strip: what it puts in, and where the cursor ends. A pair ([closing] not null)
 * goes around the selection when there is one, else in with the cursor between its halves.
 */
data class InkKey(val label: String, val opening: String, val closing: String? = null)

/** The symbols of ink that phone keyboards bury on their second and third pages, in the order a writer reaches for them. */
val INK_KEYS = listOf(
    InkKey("->", "->"),
    InkKey("*", "*"),
    InkKey("+", "+"),
    InkKey("-", "-"),
    InkKey("=", "="),
    InkKey("{ }", "{", "}"),
    InkKey("|", "|"),
    InkKey("#", "#"),
    InkKey("~", "~"),
    InkKey("<>", "<>"),
    InkKey("//", "//"),
    InkKey("( )", "(", ")"),
    InkKey("[ ]", "[", "]"),
)

/** Types [key] into [editor] as one step to undo. */
fun CodeEditorState.typeKey(key: InkKey) {
    val closing = key.closing
    if (closing == null) {
        insert(key.opening)
        return
    }
    val selected = selectedText
    if (selected.isNotEmpty()) {
        replaceSelection(key.opening + selected + closing, select = true)
    } else {
        insert(key.opening + closing)
        select(cursor - closing.length)
    }
}

/**
 * Tab as the editor's own key would do it: the selected lines one step in when the selection
 * spans lines, else spaces up to the next stop (upstream's soft tabs).
 */
fun CodeEditorState.typeTab(indent: CodeIndent, width: Int) {
    if ('\n' in selectedText) {
        indentSelection(indent)
        return
    }
    val column = cursorPosition.column
    insert(" ".repeat(width - column % width))
}

/**
 * A slim row right above the on-screen keyboard with what phone keyboards bury: ink's symbols,
 * Tab, undo and redo. It scrolls sideways; touching it never takes the keyboard away.
 */
@Composable
internal fun InkKeysStrip(editor: CodeEditorState, indent: CodeIndent, modifier: Modifier = Modifier) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val stripLabel = stringResource(R.string.keys_strip)
    Column(modifier.fillMaxWidth().background(colors.chrome).semantics { contentDescription = stripLabel }) {
        HorizontalRule()
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = dimens.gapS, vertical = dimens.gapXs),
            horizontalArrangement = Arrangement.spacedBy(dimens.gapXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            INK_KEYS.forEach { key ->
                val described = stringResource(R.string.keys_insert, key.label)
                DeskButton(
                    text = key.label,
                    onClick = { editor.typeKey(key) },
                    modifier = Modifier.keepsKeyboardOpen().semantics { contentDescription = described },
                )
            }
            DeskButton(stringResource(R.string.keys_tab), { editor.typeTab(indent, INK_TAB_WIDTH) }, Modifier.keepsKeyboardOpen())
            DeskIconButton(DeskIcons.Undo, stringResource(R.string.action_undo), { editor.undo() }, Modifier.keepsKeyboardOpen(), enabled = editor.canUndo)
            DeskIconButton(InkIcons.Redo, stringResource(R.string.action_redo), { editor.redo() }, Modifier.keepsKeyboardOpen(), enabled = editor.canRedo)
        }
    }
}

/** Upstream's tab stops: every four columns. */
private const val INK_TAB_WIDTH = 4
