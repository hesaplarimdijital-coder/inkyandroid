package com.inkyandroid.ui

import android.content.res.Resources
import com.inkyandroid.R
import com.inkyandroid.core.project.ProjectTree
import com.inkyandroid.ui.files.FileIssues
import desktopkit.components.DeskIcons
import desktopkit.shell.MoreStyle
import desktopkit.shell.NavIconBadge
import desktopkit.shell.NavItem
import desktopkit.shell.NavKind
import desktopkit.shell.NavSection
import desktopkit.shell.ShellSpec

/**
 * The names and places of the project's files, as far as the navigation shows them: the main file,
 * the included files by folder, the files nothing includes. A value of its own (and not the
 * project's snapshot), so the shell's graph is rebuilt only when a file comes, goes or moves, not
 * with every keystroke that flips an unsaved flag.
 */
data class NavFiles(val projectName: String, val main: String, val groups: List<Pair<String, List<String>>>, val spare: List<String>) {
    val paths: List<String> get() = listOf(main) + groups.flatMap { it.second } + spare

    companion object {
        fun of(projectName: String, tree: ProjectTree): NavFiles =
            NavFiles(projectName, tree.main.path, tree.groups.map { group -> group.folder to group.files.map { it.path } }, tree.spare.map { it.path })
    }
}

/**
 * What the shell shows.
 *
 * - A phone has Write, Play and Files in its bottom bar; Issues, Projects, Documentation and
 *   Settings are under "⋯".
 * - A rail lists them all. Play is on it only while the editor and the player do not fit side by
 *   side ([playOnRail]); where they do, the player is a tab of the workspace's inspector.
 * - A large window's sidebar lists the project's files themselves, under the project's name and
 *   grouped as desktop Inky's files view groups them. Each is a route of its own that shows the
 *   workspace with that file ([Routes.file]) and a page of Write ([NavItem.parent]): where the
 *   chrome does not list files (a window made narrow), Write stays marked while one is open.
 *   Write and Files themselves are the bottom bar's and the rail's; without a project the sidebar
 *   shows Write too, as the way to the screen that offers to open one.
 *
 * @param files the open project's files, null when no project is open.
 * @param docs whether the documentation screen exists in this build; likewise [settings].
 */
fun buildShellSpec(
    resources: Resources,
    files: NavFiles?,
    playOnRail: Boolean,
    docs: Boolean,
    settings: Boolean,
    startRoute: String? = null,
): ShellSpec {
    val write = NavItem(
        Routes.WRITE,
        resources.getString(R.string.nav_write),
        InkIcons.Write,
        showIn = if (files == null) EVERYWHERE else PHONE_AND_RAIL,
    )
    val play = NavItem(Routes.PLAY, resources.getString(R.string.nav_play), DeskIcons.Play, showIn = if (playOnRail) PHONE_AND_RAIL else PHONE_ONLY)
    val filesScreen = NavItem(Routes.FILES, resources.getString(R.string.nav_files), InkIcons.Files, showIn = PHONE_AND_RAIL)
    val screens = buildList {
        add(NavItem(Routes.ISSUES, resources.getString(R.string.nav_issues), DeskIcons.Warning))
        add(NavItem(Routes.PROJECTS, resources.getString(R.string.nav_projects), DeskIcons.Folder))
        if (docs) add(NavItem(Routes.DOCS, resources.getString(R.string.nav_docs), InkIcons.Book))
    }
    fun fileItem(path: String, label: String, main: Boolean = false) = NavItem(
        route = Routes.file(path),
        label = label,
        icon = if (main) InkIcons.Book else InkIcons.File,
        parent = Routes.WRITE,
        showIn = SIDEBAR_ONLY,
    )
    val fileSections = if (files == null) emptyList() else buildList {
        val root = files.groups.firstOrNull { it.first.isEmpty() }?.second.orEmpty()
        add(
            NavSection(
                title = files.projectName,
                items = listOf(fileItem(files.main, name(files.main), main = true)) + root.map { fileItem(it, name(it)) },
                collapsible = true,
            ),
        )
        files.groups.filter { it.first.isNotEmpty() }.forEach { (folder, paths) ->
            add(NavSection(title = folder, items = paths.map { fileItem(it, name(it)) }, collapsible = true))
        }
        if (files.spare.isNotEmpty()) {
            add(NavSection(title = resources.getString(R.string.nav_unused_files), items = files.spare.map { fileItem(it, it) }, collapsible = true))
        }
    }
    val rail = listOfNotNull(Routes.WRITE, Routes.PLAY.takeIf { playOnRail }, Routes.FILES) + screens.map { it.route }
    return ShellSpec(
        appTitle = resources.getString(R.string.app_name),
        // the screens first, then the files under their project's name: the order of the owner's other apps
        sections = listOf(NavSection(null, listOf(write, play, filesScreen) + screens)) + fileSections,
        footer = if (settings) NavItem(Routes.SETTINGS, resources.getString(desktopkit.R.string.dk_settings), DeskIcons.Settings) else null,
        bottomBar = listOf(Routes.WRITE, Routes.PLAY, Routes.FILES),
        rail = rail,
        startRoute = startRoute ?: Routes.WRITE,
        moreStyle = MoreStyle.Sheet,
    )
}

private fun name(path: String): String = path.substringAfterLast('/')

private val EVERYWHERE = NavKind.entries.toSet()
private val PHONE_AND_RAIL = setOf(NavKind.BottomBar, NavKind.Rail)
private val PHONE_ONLY = setOf(NavKind.BottomBar)
private val SIDEBAR_ONLY = setOf(NavKind.Sidebar)

/**
 * The text beside the sidebar's entries: how many issues the project has, and for each file its
 * issues and a dot while it has unsaved changes (desktop Inky sets such a file's name in bold; the
 * sidebar's labels are the navigation's and stay as they are).
 */
fun navBadges(issueCount: Int, fileIssues: Map<String, FileIssues>, unsaved: Set<String>): Map<String, String> = buildMap {
    if (issueCount > 0) put(Routes.ISSUES, issueCount.toString())
    (fileIssues.keys + unsaved).forEach { path ->
        val count = fileIssues[path]?.count
        val text = listOfNotNull(count?.toString(), UNSAVED_DOT.takeIf { path in unsaved }).joinToString(" ")
        if (text.isNotEmpty()) put(Routes.file(path), text)
    }
}

/** The count on the Issues icon of the rail and a collapsed sidebar; TalkBack reads [described] after the entry's name. */
fun navIconBadges(issueCount: Int, described: String): Map<String, NavIconBadge> =
    if (issueCount > 0) mapOf(Routes.ISSUES to NavIconBadge(issueCount, described)) else emptyMap()

private const val UNSAVED_DOT = "•"
