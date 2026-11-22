package com.inkyandroid.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import com.inkyandroid.R
import com.inkyandroid.core.project.ProjectPaths
import desktopkit.components.CheckboxRow
import desktopkit.components.DeskButton
import desktopkit.components.DeskDialog
import desktopkit.components.DeskTextField
import desktopkit.theme.DeskTheme

/**
 * Desktop Inky's "Add new include" form: the new file's name (a folder may lead it; `.ink` is added
 * to a name without it) and whether an `INCLUDE` line for it goes into the main file. Return or
 * "Add" adds; an empty name marks the field instead.
 *
 * @param addToMain the box's state, kept by the caller between openings as desktop Inky keeps it.
 * @param problem what the last attempt came to (the name exists, it cannot be a file name), shown under the field.
 */
@Composable
fun NewIncludeDialog(
    addToMain: Boolean,
    onAddToMainChange: (Boolean) -> Unit,
    problem: String?,
    onAdd: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var empty by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val add = {
        if (name.isBlank()) empty = true else onAdd(name.trim())
    }
    DeskDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.include_title),
        actions = {
            DeskButton(stringResource(R.string.action_cancel), onDismiss)
            DeskButton(stringResource(R.string.action_add), add, primary = true)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DeskTheme.dimens.gapM)) {
            DeskTextField(
                value = name,
                onValueChange = {
                    name = it
                    empty = false
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                hint = stringResource(R.string.include_placeholder),
                label = stringResource(R.string.include_name),
                error = empty || problem != null,
                supportingText = if (empty) null else problem,
                mono = true,
                imeAction = ImeAction.Done,
                onImeAction = add,
            )
            CheckboxRow(
                title = stringResource(R.string.include_add_to_main),
                checked = addToMain,
                onCheckedChange = onAddToMainChange,
                subtitle = stringResource(R.string.include_add_to_main_hint),
            )
        }
    }
}

/**
 * A new name for the project file at [path]. The field starts with the file's name, the part
 * before `.ink` selected: the part one means to change.
 */
@Composable
fun RenameDialog(path: String, problem: String?, onRename: (newName: String) -> Unit, onDismiss: () -> Unit) {
    val current = ProjectPaths.name(path)
    var name by remember(path) {
        val stem = if (ProjectPaths.hasInkExtension(current)) current.length - INK_EXTENSION.length else current.length
        mutableStateOf(TextFieldValue(current, TextRange(0, stem)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val usable = name.text.isNotBlank() && name.text.trim() != current
    val rename = { if (usable) onRename(name.text.trim()) }
    DeskDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.rename_title),
        actions = {
            DeskButton(stringResource(R.string.action_cancel), onDismiss)
            DeskButton(stringResource(R.string.action_rename_confirm), rename, primary = true, enabled = usable)
        },
    ) {
        DeskTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            label = stringResource(R.string.rename_name),
            error = problem != null,
            supportingText = problem ?: stringResource(R.string.rename_hint),
            mono = true,
            imeAction = ImeAction.Done,
            onImeAction = rename,
        )
    }
}

/** The question before a file is deleted from the project and from storage. */
@Composable
fun DeleteFileDialog(path: String, onDelete: () -> Unit, onDismiss: () -> Unit) {
    DeskDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.delete_title, ProjectPaths.name(path)),
        actions = {
            DeskButton(stringResource(R.string.action_cancel), onDismiss)
            DeskButton(stringResource(R.string.delete_confirm), onDelete, danger = true)
        },
    ) {
        Text(stringResource(R.string.delete_text), style = DeskTheme.type.body, color = DeskTheme.colors.textSecondary)
    }
}

private const val INK_EXTENSION = ".ink"
