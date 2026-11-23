package com.inkyandroid.ui.files

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.inkyandroid.R
import com.inkyandroid.core.project.KeptVersion
import com.inkyandroid.core.project.ProjectPaths
import com.inkyandroid.session.ProjectSession
import desktopkit.components.DeskButton
import desktopkit.components.DeskDialog
import desktopkit.components.DeskIconButton
import desktopkit.components.DeskIcons
import desktopkit.components.DeskListRow
import desktopkit.theme.DeskTheme

/**
 * The versions of the project's files the app kept before replacing them (a conflict answered either
 * way): the writer's own unsaved text, or what storage held. Newest first; only [path]'s when given.
 * One is brought back into its file as an edit the writer can undo, or forgotten.
 */
@Composable
fun KeptVersionsDialog(
    session: ProjectSession,
    path: String?,
    onRestore: (KeptVersion) -> Unit,
    onForget: (KeptVersion) -> Unit,
    onDismiss: () -> Unit,
) {
    val kept by session.kept.collectAsState()
    val shown = kept.filter { path == null || it.path == path }.sortedByDescending { it.keptAt }
    val context = LocalContext.current
    val colors = DeskTheme.colors
    DeskDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.kept_title),
        actions = { DeskButton(stringResource(R.string.action_close), onDismiss, primary = true) },
    ) {
        if (shown.isEmpty()) {
            Text(stringResource(R.string.kept_empty), style = DeskTheme.type.body, color = colors.textSecondary)
            return@DeskDialog
        }
        Text(stringResource(R.string.kept_text), style = DeskTheme.type.body, color = colors.textSecondary)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * LIST_SHARE).dp)) {
            items(shown, key = { it.id }) { version ->
                val origin = stringResource(if (version.origin == KeptVersion.Origin.Mine) R.string.kept_mine else R.string.kept_theirs)
                val time = DateUtils.formatDateTime(context, version.keptAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
                DeskListRow(
                    title = if (path == null) version.path else ProjectPaths.name(version.path),
                    subtitle = "$origin · $time",
                    preview = version.text.lineSequence().filter { it.isNotBlank() }.take(PREVIEW_LINES).joinToString("\n"),
                    previewMaxLines = PREVIEW_LINES,
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(DeskTheme.dimens.gapXs), verticalAlignment = Alignment.CenterVertically) {
                            DeskButton(stringResource(R.string.kept_restore), { onRestore(version) })
                            DeskIconButton(DeskIcons.Delete, stringResource(R.string.kept_forget), { onForget(version) })
                        }
                    },
                    onClick = { onRestore(version) },
                )
            }
        }
    }
}

/** How much of the window's height the list may take. */
private const val LIST_SHARE = 0.5f

/** The first lines of a kept text shown under its row. */
private const val PREVIEW_LINES = 2
