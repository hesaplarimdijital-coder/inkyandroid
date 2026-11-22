package com.inkyandroid.ui.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.inkyandroid.core.live.LiveSession
import com.inkyandroid.session.ProjectSession
import com.inkyandroid.settings.InspectorTab
import com.inkyandroid.ui.Commands
import com.inkyandroid.ui.FolderPurpose
import com.inkyandroid.ui.WindowActions
import com.inkyandroid.ui.WindowUi
import kotlinx.coroutines.launch

/** The player's requests, answered by the live session and the window's commands. */
@Composable
fun rememberPlayerActions(live: LiveSession?, actions: WindowActions): PlayerActions = remember(live, actions) {
    PlayerActions(
        onChoose = { choice -> live?.choose(choice) },
        onOpenSource = actions::openSource,
        onOpenIssue = actions::openIssue,
        onShowIssues = { actions.showTab(InspectorTab.Issues) },
        onRestart = { actions.perform(Commands.RESTART) },
    )
}

/** The banners' requests, answered by the session and the window's commands. */
@Composable
fun rememberBannerActions(session: ProjectSession, ui: WindowUi, actions: WindowActions): BannerActions {
    val scope = rememberCoroutineScope()
    return remember(session, ui, actions) {
        BannerActions(
            onRetrySave = { actions.save() },
            onRetryStorage = { actions.reload() },
            onResolveConflict = { path, keepMine -> scope.launch { session.resolveConflict(path, keepMine) } },
            onDismissRestored = session::dismissRestoredNotice,
            onGrantFolder = { ui.singleFile?.let { actions.pickFolder(FolderPurpose.Grant(it.ref)) } },
            onDismissSingleFile = { ui.singleFile = ui.singleFile?.copy(dismissed = true) },
        )
    }
}
