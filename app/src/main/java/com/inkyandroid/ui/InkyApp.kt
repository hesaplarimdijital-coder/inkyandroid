package com.inkyandroid.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.inkyandroid.R
import desktopkit.DesktopKitRoot
import desktopkit.appResources
import desktopkit.components.DeskIcons
import desktopkit.screens.MessageScreen
import desktopkit.settings.DeskPreferences
import desktopkit.settings.rememberDeskPreferences
import desktopkit.shell.DesktopShell
import desktopkit.shell.NavItem
import desktopkit.shell.NavSection
import desktopkit.shell.ShellSpec
import desktopkit.shell.ShellTransitions

/**
 * The whole app inside one window: the kit's root (theme, density, insets) and its shell (bottom
 * bar, rail or sidebar by the window's width) around the screens.
 *
 * @param prefs the kit's preferences (theme, density, the sidebar); tests pin their own.
 * @param transitions how the shell moves between screens; tests turn it off.
 */
@Composable
fun InkyApp(
    prefs: DeskPreferences = rememberDeskPreferences(),
    transitions: ShellTransitions = ShellTransitions.Default,
) {
    val resources = appResources()
    val locales = LocalConfiguration.current.locales
    // the labels are the window's language: a new spec when that changes
    val spec = remember(locales) { shellSpec(resources) }
    DesktopKitRoot(prefs) { widthClass ->
        DesktopShell(
            spec = spec,
            widthClass = widthClass,
            sidebarCollapsed = prefs.sidebarCollapsed,
            onSidebarCollapsedChange = prefs::updateSidebarCollapsed,
            transitions = transitions,
        ) { route, _ ->
            when (route) {
                Routes.STORY -> MessageScreen(
                    title = stringResource(R.string.setup_title),
                    icon = DeskIcons.Info,
                    description = stringResource(R.string.setup_description),
                )
            }
        }
    }
}

private fun shellSpec(resources: Resources) = ShellSpec(
    appTitle = resources.getString(R.string.app_name),
    sections = listOf(
        NavSection(null, listOf(NavItem(Routes.STORY, resources.getString(R.string.nav_story), DeskIcons.Lyrics))),
    ),
)
