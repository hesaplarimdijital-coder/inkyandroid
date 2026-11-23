package com.inkyandroid.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.inkyandroid.R
import com.inkyandroid.core.live.CompileStatus
import com.inkyandroid.core.live.LiveState
import com.inkyandroid.ui.InkIcons
import desktopkit.components.DeskIconButton
import desktopkit.theme.DeskTheme

/** Where the story stands, in a few words: for the player's header and its screen's subtitle. */
@Composable
fun playerStatus(state: LiveState): String = when {
    state.compile == CompileStatus.Errors || state.compile == CompileStatus.Crashed -> stringResource(R.string.player_status_errors)
    state.transcript.liveTurn > 0 -> stringResource(R.string.player_status_turn, state.transcript.liveTurn + 1)
    else -> stringResource(R.string.player_status_start)
}

/**
 * The player's own row of controls where it is a pane beside the editor: where the story stands,
 * and desktop Inky's two toolbar buttons, "Rewind a single choice" and "Restart story".
 */
@Composable
fun PlayerHeader(state: LiveState, onStepBack: () -> Unit, onRestart: () -> Unit, modifier: Modifier = Modifier, stepBackShortcut: String? = null, restartShortcut: String? = null) {
    val dimens = DeskTheme.dimens
    Row(
        modifier.fillMaxWidth().padding(start = dimens.pagePadding, end = dimens.gapS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.gapXs),
    ) {
        Text(
            playerStatus(state),
            style = DeskTheme.type.label,
            color = DeskTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        DeskIconButton(InkIcons.StepBack, stringResource(R.string.action_step_back), onStepBack, enabled = state.canStepBack, shortcut = stepBackShortcut)
        DeskIconButton(InkIcons.Restart, stringResource(R.string.action_restart), onRestart, shortcut = restartShortcut)
    }
}
