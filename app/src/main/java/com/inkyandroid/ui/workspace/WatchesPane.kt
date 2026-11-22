package com.inkyandroid.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.inkyandroid.R
import desktopkit.components.DeskButton
import desktopkit.components.DeskIconButton
import desktopkit.components.DeskIcons
import desktopkit.components.DeskTextField
import desktopkit.components.verticalScrollbar
import desktopkit.theme.DeskTheme

/** What a new watch starts as, as in desktop Inky: a pattern to type a variable's name into. */
const val DEFAULT_WATCH = "x is {x}"

/** A watch is one line of ink: line breaks that are typed or pasted become spaces, as in desktop Inky. */
fun watchText(typed: String): String = typed.replace(Regex("[\\r\\n]+"), " ")

/**
 * The watch expressions: lines of ink evaluated after every turn, whose results show in the
 * player. Each is edited in place; every change goes to the story at once (the live session waits
 * for a pause before it plays again).
 *
 * @param watches the expressions, in the order they are evaluated.
 * @param onChange the whole list after an edit, an addition or a removal.
 * @param focusNewest asks for the keyboard in the last expression once: after "Add watch expression"
 *   from a menu, so the name can be typed at once. [onFocused] acknowledges it.
 */
@Composable
fun WatchesPane(
    watches: List<String>,
    onChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    focusNewest: Boolean = false,
    onFocused: () -> Unit = {},
) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val scroll = rememberScrollState()
    Column(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScrollbar(scroll)
            .verticalScroll(scroll)
            .padding(contentPadding)
            .padding(horizontal = dimens.pagePadding, vertical = dimens.gapL),
        verticalArrangement = Arrangement.spacedBy(dimens.gapM),
    ) {
        if (watches.isEmpty()) {
            Text(stringResource(R.string.watches_empty_title), style = DeskTheme.type.bodyStrong, color = colors.text)
            Text(stringResource(R.string.watches_empty_description), style = DeskTheme.type.body, color = colors.textSecondary)
        } else {
            Text(stringResource(R.string.watches_every_turn), style = DeskTheme.type.label, color = colors.textSecondary)
        }
        watches.forEachIndexed { index, watch ->
            WatchField(
                text = watch,
                focus = focusNewest && index == watches.lastIndex,
                onFocused = onFocused,
                onChange = { typed -> onChange(watches.toMutableList().also { it[index] = watchText(typed) }) },
                onRemove = { onChange(watches.toMutableList().also { it.removeAt(index) }) },
            )
        }
        DeskButton(stringResource(R.string.watches_add), { onChange(watches + DEFAULT_WATCH) }, icon = DeskIcons.Plus)
    }
}

/**
 * One expression with its remove button. The field keeps its own cursor; a new watch gets the
 * keyboard with its first `x` selected, so typing a name replaces it.
 */
@Composable
private fun WatchField(text: String, focus: Boolean, onFocused: () -> Unit, onChange: (String) -> Unit, onRemove: () -> Unit) {
    val dimens = DeskTheme.dimens
    var value by remember { mutableStateOf(TextFieldValue(text)) }
    // the list changed under the field (another watch was removed): show what is there now
    if (value.text != text) value = TextFieldValue(text, TextRange(text.length))
    val requester = remember { FocusRequester() }
    var asked by remember { mutableIntStateOf(0) }
    LaunchedEffect(focus) {
        if (focus && asked == 0) {
            asked++
            requester.requestFocus()
            if (text == DEFAULT_WATCH) value = TextFieldValue(text, TextRange(0, 1))
            onFocused()
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dimens.gapS), verticalAlignment = Alignment.CenterVertically) {
        DeskTextField(
            value = value,
            onValueChange = { next ->
                val cleaned = watchText(next.text)
                value = if (cleaned == next.text) next else next.copy(text = cleaned)
                if (cleaned != text) onChange(cleaned)
            },
            modifier = Modifier.weight(1f).focusRequester(requester),
            hint = stringResource(R.string.watches_expression),
            mono = true,
            imeAction = ImeAction.Done,
        )
        DeskIconButton(DeskIcons.Close, stringResource(R.string.watches_remove), onRemove)
    }
}
