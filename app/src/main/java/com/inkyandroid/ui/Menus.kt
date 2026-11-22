package com.inkyandroid.ui

import android.content.res.Resources
import com.inkyandroid.R
import com.inkyandroid.settings.AppSettings
import desktopkit.components.ActionGroup
import desktopkit.components.MenuEntry
import desktopkit.components.MenuSpec
import desktopkit.input.CommonShortcuts
import desktopkit.input.ShortcutRegistry

/**
 * The window's menus, written once for the menu bar of a large window (and a rail's top bar) and
 * for the phone's "⋯" sheet. They mirror desktop Inky's File, Edit, View, Story, Ink and Help
 * menus, with what this app adds (a folder is a project here; reload; the panes of the workspace)
 * in the places a user of the desktop app would look for it. Window and the developer's items have
 * no meaning here.
 *
 * Labels and keys come from the shortcut table, so a menu, the palette and the lists of shortcuts
 * never disagree.
 *
 * @param perform runs a command by its id.
 * @param enabled whether a command has something to do right now.
 * @param available whether this build has the command at all (the exports and the statistics come
 *   with their screens); what is not available is not listed.
 * @param recent desktop Inky's "Open Recent" submenu, null until the kit's menus can nest.
 * @param ink the Ink menu's entries (the snippets), empty until they exist.
 */
class WindowMenus(
    private val resources: Resources,
    private val registry: ShortcutRegistry,
    private val settings: AppSettings,
    private val perform: (String) -> Unit,
    private val enabled: (String) -> Boolean,
    private val available: (String) -> Boolean,
    private val showKeys: Boolean,
    private val recent: MenuEntry? = null,
    private val ink: List<MenuEntry> = emptyList(),
) {
    private fun item(id: String): MenuEntry.Item? {
        if (!available(id)) return null
        val shortcut = registry.getOrNull(id) ?: return null
        return MenuEntry.Item(
            label = resources.getString(shortcut.label),
            onClick = { perform(id) },
            shortcut = if (showKeys) shortcut.chord.label(resources) else null,
            enabled = enabled(id),
        )
    }

    /** An entry without a key: desktop Inky has none for "Export for web…" and "Add watch expression…". */
    private fun plain(id: String, label: Int): MenuEntry.Item? =
        if (available(id)) MenuEntry.Item(resources.getString(label), { perform(id) }, enabled = enabled(id)) else null

    private fun check(label: Int, checked: Boolean, toggle: () -> Unit) = MenuEntry.Item(resources.getString(label), toggle, checked = checked)

    private val autoComplete get() = check(R.string.action_autocomplete, settings.autoComplete) { settings.updateAutoComplete(!settings.autoComplete) }
    private val playAnimation get() = check(R.string.action_play_animation, settings.playAnimation) { settings.updatePlayAnimation(!settings.playAnimation) }
    private val tagsVisible get() = check(R.string.action_tags_visible, settings.tagsVisible) { settings.updateTagsVisible(!settings.tagsVisible) }

    /** [groups] as one menu: a rule between the groups, none around a group that came out empty. */
    private fun menu(vararg groups: List<MenuEntry?>): List<MenuEntry> =
        groups.map { it.filterNotNull() }.filter { it.isNotEmpty() }.flatMapIndexed { index, group ->
            if (index == 0) group else listOf(MenuEntry.Separator) + group
        }

    private fun file(): List<MenuEntry> = menu(
        listOf(item(Commands.NEW_PROJECT), item(Commands.NEW_INCLUDE)),
        listOf(item(Commands.OPEN_FOLDER), item(Commands.OPEN_FILE), recent),
        listOf(item(Commands.SAVE), item(Commands.RELOAD)),
        listOf(item(Commands.EXPORT_JSON), plain(Commands.EXPORT_WEB, R.string.action_export_web), item(Commands.EXPORT_JS)),
        listOf(item(CommonShortcuts.NEW_WINDOW), item(Commands.CLOSE_PROJECT)),
    )

    private fun edit(): List<MenuEntry> = menu(
        listOf(item(Commands.UNDO), item(Commands.REDO)),
        listOf(item(Commands.CUT), item(Commands.COPY), item(Commands.PASTE), item(Commands.SELECT_ALL)),
        listOf(item(Commands.FIND), item(Commands.REPLACE), item(Commands.TOGGLE_COMMENT)),
    )

    private fun view(): List<MenuEntry> = menu(
        listOf(item(Commands.ZOOM_IN), item(Commands.ZOOM_OUT), item(Commands.ZOOM_RESET)),
        listOf(autoComplete, playAnimation),
        listOf(
            item(Commands.TOGGLE_INSPECTOR),
            item(Commands.SHOW_PLAYER),
            item(Commands.SHOW_ISSUES),
            item(Commands.SHOW_OUTLINE),
            item(Commands.SHOW_WATCHES),
        ),
        listOf(item(CommonShortcuts.SEARCH), item(CommonShortcuts.TOGGLE_SIDEBAR), item(CommonShortcuts.SETTINGS)),
    )

    private fun story(): List<MenuEntry> = menu(
        listOf(item(Commands.GOTO_ANYTHING), item(Commands.GOTO_LINE), item(Commands.GOTO_DEFINITION), item(Commands.BACK), item(Commands.FORWARD)),
        listOf(item(Commands.NEXT_ISSUE)),
        listOf(item(Commands.STEP_BACK), item(Commands.RESTART)),
        listOf(plain(Commands.ADD_WATCH, R.string.action_add_watch), tagsVisible),
        listOf(item(Commands.STATISTICS)),
    )

    private fun help(): List<MenuEntry> = menu(listOf(item(Commands.DOCUMENTATION), item(CommonShortcuts.SHORTCUT_LIST)))

    /** The menu bar. The Ink menu is there once it has entries. */
    fun menuBar(): List<MenuSpec> = listOfNotNull(
        MenuSpec(resources.getString(R.string.menu_file)) { file() },
        MenuSpec(resources.getString(R.string.menu_edit)) { edit() },
        MenuSpec(resources.getString(desktopkit.R.string.dk_menu_view)) { view() },
        MenuSpec(resources.getString(R.string.menu_story)) { story() },
        if (ink.isEmpty()) null else MenuSpec(resources.getString(R.string.menu_ink)) { ink },
        MenuSpec(resources.getString(desktopkit.R.string.dk_menu_help)) { help() },
    )

    /**
     * The phone's "⋯" sheet, under the screens it lists itself: the commands of the menus that a
     * phone has no other place for. What the editor's and the player's own bars carry (undo, find,
     * step back) and what needs a keyboard or a second pane is left out.
     */
    fun moreSheet(): List<ActionGroup> = listOf(
        ActionGroup(
            resources.getString(R.string.menu_story),
            listOfNotNull(
                item(Commands.GOTO_ANYTHING),
                item(Commands.NEXT_ISSUE),
                plain(Commands.ADD_WATCH, R.string.action_add_watch),
                tagsVisible,
                item(Commands.STATISTICS),
            ),
        ),
        ActionGroup(
            resources.getString(R.string.menu_file),
            listOfNotNull(
                item(Commands.NEW_PROJECT),
                item(Commands.NEW_INCLUDE),
                item(Commands.OPEN_FOLDER),
                item(Commands.OPEN_FILE),
                item(Commands.SAVE),
                item(Commands.RELOAD),
                item(Commands.EXPORT_JSON),
                plain(Commands.EXPORT_WEB, R.string.action_export_web),
                item(Commands.EXPORT_JS),
                item(Commands.CLOSE_PROJECT),
            ),
        ),
        ActionGroup(resources.getString(desktopkit.R.string.dk_menu_view), listOf(autoComplete, playAnimation)),
    ).filter { it.actions.isNotEmpty() }
}
