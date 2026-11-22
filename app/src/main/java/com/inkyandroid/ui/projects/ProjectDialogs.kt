package com.inkyandroid.ui.projects

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
import androidx.compose.ui.text.input.ImeAction
import com.inkyandroid.R
import com.inkyandroid.session.CloseAnswer
import com.inkyandroid.storage.PickedFolder
import com.inkyandroid.storage.ProjectNames
import com.inkyandroid.storage.ProjectRef
import com.inkyandroid.ui.InkIcons
import desktopkit.components.DeskButton
import desktopkit.components.DeskDialog
import desktopkit.components.DeskIcons
import desktopkit.components.DeskListRow
import desktopkit.components.DeskTextField
import desktopkit.theme.DeskTheme

/** Where a new project is made. */
enum class NewProjectPlace {
    /** In the projects folder the user chose once. */
    ProjectsFolder,

    /** In a folder that is picked now. */
    PickFolder,

    /** In the app's own storage. */
    Internal,
}

/**
 * The new project form: its name, and where it goes. A folder project is a folder of that name
 * with a main ink file of that name in it.
 *
 * @param parent a folder already picked for the project (an empty folder that was opened): the
 *   project goes there and no place is asked for.
 * @param projectsFolder the projects folder's name, null when none was chosen yet.
 */
@Composable
fun NewProjectDialog(
    parent: PickedFolder?,
    projectsFolder: String?,
    onCreate: (name: String, place: NewProjectPlace) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var place by remember { mutableStateOf(if (projectsFolder != null) NewProjectPlace.ProjectsFolder else NewProjectPlace.Internal) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val usable = ProjectNames.sanitize(name) != null
    val create = { if (usable) onCreate(name, place) }
    DeskDialog(
        onDismiss = onDismiss,
        title = if (parent == null) stringResource(R.string.new_project_title) else stringResource(R.string.no_ink_title, parent.displayName),
        actions = {
            DeskButton(stringResource(R.string.action_cancel), onDismiss)
            DeskButton(stringResource(R.string.action_create), create, primary = true, enabled = usable)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DeskTheme.dimens.gapM)) {
            if (parent != null) Text(stringResource(R.string.no_ink_text), style = DeskTheme.type.body, color = DeskTheme.colors.textSecondary)
            DeskTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                label = stringResource(R.string.new_project_name),
                error = name.isNotBlank() && !usable,
                supportingText = if (name.isNotBlank() && !usable) stringResource(R.string.new_project_invalid) else stringResource(R.string.new_project_folder_hint),
                imeAction = ImeAction.Done,
                onImeAction = create,
            )
            if (parent == null) {
                Column {
                    Text(stringResource(R.string.new_project_where), style = DeskTheme.type.label, color = DeskTheme.colors.textSecondary)
                    if (projectsFolder != null) {
                        PlaceRow(stringResource(R.string.new_project_in_folder, projectsFolder), null, DeskIcons.Folder, place == NewProjectPlace.ProjectsFolder) {
                            place = NewProjectPlace.ProjectsFolder
                        }
                    }
                    PlaceRow(stringResource(R.string.new_project_pick), null, DeskIcons.Folder, place == NewProjectPlace.PickFolder) { place = NewProjectPlace.PickFolder }
                    PlaceRow(
                        stringResource(R.string.new_project_internal),
                        stringResource(R.string.new_project_internal_hint),
                        InkIcons.Book,
                        place == NewProjectPlace.Internal,
                    ) { place = NewProjectPlace.Internal }
                }
            }
        }
    }
}

/** One of the places a new project can go: marked when it is the chosen one. */
@Composable
private fun PlaceRow(title: String, subtitle: String?, icon: androidx.compose.ui.graphics.vector.ImageVector, chosen: Boolean, onClick: () -> Unit) {
    DeskListRow(
        title = title,
        subtitle = subtitle,
        subtitleMaxLines = 2,
        leading = if (chosen) DeskIcons.Check else icon,
        leadingTint = if (chosen) DeskTheme.colors.accentText else DeskTheme.colors.textSecondary,
        selected = chosen,
        divider = false,
        onClick = onClick,
    )
}

/** A folder holds several files nothing includes: the user says which one the story starts in. */
@Composable
fun ChooseMainDialog(folder: PickedFolder, candidates: List<String>, onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    DeskDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.main_file_title),
        actions = { DeskButton(stringResource(R.string.action_cancel), onDismiss) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DeskTheme.dimens.gapS)) {
            Text(stringResource(R.string.main_file_text), style = DeskTheme.type.body, color = DeskTheme.colors.textSecondary)
            Column {
                candidates.forEach { path -> DeskListRow(path, leading = InkIcons.File, onClick = { onChoose(path) }) }
            }
        }
    }
}

/** The question before a project kept inside the app is deleted for good. */
@Composable
fun DeleteProjectDialog(ref: ProjectRef.Internal, onDelete: () -> Unit, onDismiss: () -> Unit) {
    DeskDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.projects_delete_title, ref.displayName),
        actions = {
            DeskButton(stringResource(R.string.action_cancel), onDismiss)
            DeskButton(stringResource(R.string.delete_confirm), onDelete, danger = true)
        },
    ) {
        Text(stringResource(R.string.projects_delete_text), style = DeskTheme.type.body, color = DeskTheme.colors.textSecondary)
    }
}

/**
 * Desktop Inky's question when a project with unsaved changes is closed: "Would you like to save
 * changes before exiting?", with Save, Don't save and Cancel. Leaving the dialog is Cancel.
 */
@Composable
fun CloseQuestionDialog(onAnswer: (CloseAnswer) -> Unit) {
    DeskDialog(
        onDismiss = { onAnswer(CloseAnswer.Cancel) },
        title = stringResource(R.string.close_question_title),
        actions = {
            DeskButton(stringResource(R.string.close_dont_save), { onAnswer(CloseAnswer.DontSave) }, danger = true)
            DeskButton(stringResource(R.string.action_cancel), { onAnswer(CloseAnswer.Cancel) })
            DeskButton(stringResource(R.string.close_save), { onAnswer(CloseAnswer.Save) }, primary = true)
        },
    ) {
        Text(stringResource(R.string.close_question_text), style = DeskTheme.type.body, color = DeskTheme.colors.textSecondary)
    }
}
