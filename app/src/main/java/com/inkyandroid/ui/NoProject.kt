package com.inkyandroid.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.inkyandroid.R
import com.inkyandroid.session.WindowProject
import com.inkyandroid.storage.ProjectRef
import com.inkyandroid.storage.ProjectStorage
import com.inkyandroid.ui.projects.ProjectsScreen
import desktopkit.components.DeskIcons
import desktopkit.components.DeskProgressBar
import desktopkit.screens.MessageScreen
import desktopkit.shell.shellContentPadding
import desktopkit.theme.DeskTheme
import kotlinx.coroutines.delay

/**
 * What a route that needs a project shows while the window has none: the editor's place offers the
 * ways to one (the Projects screen's start and recent projects), the other screens say what is
 * missing and lead there. A project that is being opened shows that it is, once it takes long
 * enough to notice; one that could not be opened says why and offers to try again.
 */
@Composable
internal fun NoProject(route: String, window: WindowProject, storage: ProjectStorage, ui: WindowUi, actions: WindowActions) {
    when (window) {
        is WindowProject.Opening -> OpeningProject(window.reference)
        is WindowProject.Failed -> MessageScreen(
            title = stringResource(R.string.projects_failed_title),
            icon = DeskIcons.Error,
            modifier = Modifier.shellContentPadding(),
            description = window.message,
            actionLabel = stringResource(R.string.action_retry),
            onAction = { actions.holder.open(window.reference) },
        )
        // the editor's place is where one starts: the ways to a project, right there
        else -> if (Routes.isWorkspace(route)) {
            ProjectsScreen(storage, null, ui, actions)
        } else {
            MessageScreen(
                title = stringResource(R.string.no_project_title),
                icon = DeskIcons.Folder,
                modifier = Modifier.shellContentPadding(),
                description = stringResource(R.string.no_project_description),
                actionLabel = stringResource(R.string.projects_choose),
                onAction = { actions.navigate(Routes.PROJECTS) },
                actionPrimary = true,
            )
        }
    }
}

/**
 * A project on its way: nothing for a moment (most open at once, and a flash of "Opening" would
 * only flicker), then its name with a running bar.
 */
@Composable
private fun OpeningProject(reference: String) {
    var shown by remember(reference) { mutableStateOf(false) }
    LaunchedEffect(reference) {
        delay(OPENING_NOTICE_AFTER_MS)
        shown = true
    }
    if (!shown) {
        Box(Modifier.fillMaxSize())
        return
    }
    val name = ProjectRef.decode(reference)?.displayName.orEmpty()
    val dimens = DeskTheme.dimens
    val label = stringResource(R.string.projects_opening, name)
    Box(Modifier.fillMaxSize().shellContentPadding().padding(dimens.pagePadding), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = OPENING_WIDTH), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = DeskTheme.type.title, color = DeskTheme.colors.text, textAlign = TextAlign.Center)
            DeskProgressBar(fraction = null, modifier = Modifier.fillMaxWidth().padding(top = dimens.gapL), contentDescription = label)
        }
    }
}

/** How long a project may take to open before the window says that it is opening. */
private const val OPENING_NOTICE_AFTER_MS = 300L

/** The opening notice is no wider than a short line. */
private val OPENING_WIDTH = 320.dp
