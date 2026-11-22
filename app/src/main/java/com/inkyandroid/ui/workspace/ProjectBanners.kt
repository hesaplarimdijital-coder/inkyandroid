package com.inkyandroid.ui.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.inkyandroid.R
import com.inkyandroid.core.project.ProjectPaths
import com.inkyandroid.core.project.ProjectSnapshot
import com.inkyandroid.core.settings.SettingsProblem
import com.inkyandroid.session.SessionState
import com.inkyandroid.storage.StorageProblem
import com.inkyandroid.ui.SingleFileNotice
import desktopkit.appResources
import desktopkit.components.BannerTone
import desktopkit.components.StatusBanner

/** What the banners above the editor ask for. */
class BannerActions(
    /** "Try again" after a failed save, and after storage could not be reached. */
    val onRetrySave: () -> Unit,
    val onRetryStorage: () -> Unit,
    /** A file changed outside while it has unsaved changes: keep this text, or take storage's. */
    val onResolveConflict: (path: String, keepMine: Boolean) -> Unit,
    val onDismissRestored: () -> Unit,
    /** Grant the folder of the single file that is open. */
    val onGrantFolder: () -> Unit,
    val onDismissSingleFile: () -> Unit,
)

/**
 * Whether [ProjectBanners] has anything to show for these states: the editor leaves room for the
 * phone's top bar itself only when no banner stands there.
 */
fun hasBanners(state: SessionState, project: ProjectSnapshot, singleFile: SingleFileNotice?): Boolean =
    state.saveProblem != null || state.storageProblem != null || state.draftProblem != null || project.conflicts.isNotEmpty() ||
        state.restoredDrafts != null || state.projectSettings.problems.isNotEmpty() || (singleFile != null && !singleFile.dismissed)

/**
 * What the user has to know about the project's text and its storage, above the editor, most
 * serious first: a save that failed (the text is safe in the drafts, and can be tried again), a
 * file that changed outside while it has unsaved changes (both versions are kept until one is
 * chosen), a folder that cannot be reached, drafts that could not be written; then the quiet ones:
 * unsaved changes that were brought back, a settings file that is not understood, a single file
 * whose folder is not granted.
 */
@Composable
fun ProjectBanners(
    state: SessionState,
    project: ProjectSnapshot,
    singleFile: SingleFileNotice?,
    actions: BannerActions,
    modifier: Modifier = Modifier,
) {
    val resources = appResources()
    Column(modifier) {
        state.saveProblem?.let { problem ->
            val failed = problem.failed
            val only = failed.entries.singleOrNull()?.let { (path, reason) -> path to (problem.errors[path]?.let { StorageProblem.of(it).message(resources) } ?: reason) }
            StatusBanner(
                tone = BannerTone.Error,
                title = stringResource(R.string.save_failed_title),
                text = if (only != null) {
                    stringResource(R.string.save_failed_one, ProjectPaths.name(only.first), only.second)
                } else {
                    pluralStringResource(R.plurals.save_failed_many, failed.size, failed.size)
                },
                actionLabel = stringResource(R.string.action_retry),
                onAction = actions.onRetrySave,
            )
        }
        project.conflicts.firstOrNull()?.let { file ->
            StatusBanner(
                tone = BannerTone.Warning,
                title = stringResource(R.string.conflict_title, file.name),
                text = stringResource(R.string.conflict_text),
                actionLabel = stringResource(R.string.conflict_keep_mine),
                onAction = { actions.onResolveConflict(file.path, true) },
                secondaryActionLabel = stringResource(R.string.conflict_load_theirs),
                onSecondaryAction = { actions.onResolveConflict(file.path, false) },
            )
        }
        state.storageProblem?.let { problem ->
            StatusBanner(
                tone = BannerTone.Error,
                title = stringResource(R.string.storage_problem_title),
                text = state.storageError?.let { StorageProblem.of(it).message(resources) } ?: problem,
                actionLabel = stringResource(R.string.action_retry),
                onAction = actions.onRetryStorage,
            )
        }
        state.draftProblem?.let { problem ->
            StatusBanner(
                tone = BannerTone.Warning,
                title = stringResource(R.string.draft_problem_title),
                text = state.draftError?.let { StorageProblem.of(it).message(resources) } ?: problem,
            )
        }
        state.restoredDrafts?.let { report ->
            val count = report.restored.size + report.conflicts.size
            StatusBanner(
                tone = BannerTone.Info,
                text = pluralStringResource(R.plurals.restored_files, count, count),
                closeLabel = stringResource(R.string.action_close),
                onClose = actions.onDismissRestored,
            )
        }
        state.projectSettings.problems.firstOrNull()?.let { problem ->
            val file = ProjectPaths.name(com.inkyandroid.core.settings.ProjectSettings.pathFor(project.mainPath))
            StatusBanner(
                tone = BannerTone.Warning,
                title = stringResource(R.string.settings_problem_title),
                text = when (problem) {
                    SettingsProblem.Empty -> stringResource(R.string.settings_problem_empty, file)
                    is SettingsProblem.InvalidJson -> stringResource(R.string.settings_problem_json, file, problem.message)
                    else -> stringResource(R.string.settings_problem_entry, file, problem.location)
                },
            )
        }
        if (singleFile != null && !singleFile.dismissed) {
            StatusBanner(
                tone = if (singleFile.readOnly) BannerTone.Warning else BannerTone.Info,
                text = stringResource(if (singleFile.readOnly) R.string.single_file_read_only else R.string.single_file_banner),
                actionLabel = stringResource(R.string.single_file_grant),
                onAction = actions.onGrantFolder,
                closeLabel = stringResource(R.string.action_close),
                onClose = actions.onDismissSingleFile,
            )
        }
    }
}
