package com.inkyandroid.ui.extras

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.inkyandroid.R
import com.inkyandroid.settings.AppSettings
import com.inkyandroid.storage.PickedFolder
import com.inkyandroid.storage.ProjectStorage
import com.inkyandroid.storage.StorageContracts
import com.inkyandroid.storage.StorageProblem
import com.inkyandroid.ui.extras.settings.LicencesScreen
import desktopkit.components.DeskButton
import desktopkit.components.DeskIcons
import desktopkit.components.KeyValueRow
import desktopkit.components.SettingRow
import desktopkit.components.SwitchRow
import desktopkit.input.ShortcutRegistry
import desktopkit.input.showShortcutHints
import desktopkit.screens.PageHeader
import desktopkit.settings.AppearanceSection
import desktopkit.settings.DeskPreferences
import desktopkit.settings.InputSection
import desktopkit.settings.SettingsSection
import desktopkit.settings.ShortcutListSection
import desktopkit.shell.DetailTransition
import desktopkit.shell.rememberShellContentPadding
import desktopkit.theme.DeskTheme
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Names the tests find the settings by. */
object SettingsTags {
    const val LIST = "settings-list"
    const val FOLDER_PROBLEM = "settings-folder-problem"
}

/**
 * The route `settings`: the kit's appearance and input sections and the list of shortcuts (only with
 * a mouse or a keyboard), then the app's own: the editor, the player, files (autosave, the folder new
 * projects go to) and about, from which the licences page opens over the list.
 *
 * @param prefs the kit's preferences.
 * @param settings the app's settings, changed here as the switches are used.
 * @param shortcuts the app's shortcuts, listed for reference.
 */
@Composable
fun SettingsScreen(
    prefs: DeskPreferences,
    settings: AppSettings,
    shortcuts: ShortcutRegistry,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val storage = remember(context) { ProjectStorage.from(context) }
    SettingsScreen(prefs, settings, shortcuts, ProjectsFolderControl.of(storage), modifier)
}

/** The projects folder as the settings show and change it (the storage's, a fake in tests). */
internal class ProjectsFolderControl(
    val folder: StateFlow<PickedFolder?>,
    /** Takes the picked tree as the projects folder; what went wrong, or null. */
    val set: suspend (Uri) -> StorageProblem?,
    val clear: () -> Unit,
) {
    companion object {
        fun of(storage: ProjectStorage) = ProjectsFolderControl(
            folder = storage.projectsFolder,
            set = storage::setProjectsFolder,
            clear = storage::clearProjectsFolder,
        )
    }
}

@Composable
internal fun SettingsScreen(
    prefs: DeskPreferences,
    settings: AppSettings,
    shortcuts: ShortcutRegistry,
    projectsFolder: ProjectsFolderControl,
    modifier: Modifier = Modifier,
) {
    var licences by rememberSaveable { mutableStateOf(false) }
    DetailTransition(
        detail = if (licences) Unit else null,
        onBack = { licences = false },
        modifier = modifier,
        list = { SettingsList(prefs, settings, shortcuts, projectsFolder, onOpenLicences = { licences = true }) },
        sharedElements = false,
    ) {
        LicencesScreen(onClose = { licences = false })
    }
}

@Composable
private fun SettingsList(
    prefs: DeskPreferences,
    settings: AppSettings,
    shortcuts: ShortcutRegistry,
    projectsFolder: ProjectsFolderControl,
    onOpenLicences: () -> Unit,
) {
    val keyboard = showShortcutHints()
    val state = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().testTag(SettingsTags.LIST), state = state, contentPadding = rememberShellContentPadding()) {
        item(key = "header") { PageHeader(stringResource(desktopkit.R.string.dk_settings)) }
        item(key = "appearance") { AppearanceSection(prefs) }
        item(key = "input") { InputSection(prefs) }
        // the list of shortcuts only where there is a keyboard to use them with
        if (keyboard) item(key = "shortcuts") { ShortcutListSection(shortcuts) }
        item(key = "editor") { EditorSection(settings) }
        item(key = "player") { PlayerSection(settings) }
        item(key = "files") { FilesSection(settings, projectsFolder) }
        item(key = "about") { AboutSection(onOpenLicences) }
    }
}

/** How the editor writes: wrapping, line numbers, completion, the keyboard's suggestions. */
@Composable
private fun EditorSection(settings: AppSettings) {
    SettingsSection(stringResource(R.string.settings_editor)) {
        SwitchRow(stringResource(R.string.settings_soft_wrap), settings.softWrap, settings::updateSoftWrap,
            subtitle = stringResource(R.string.settings_soft_wrap_note))
        SwitchRow(stringResource(R.string.settings_line_numbers), settings.lineNumbers, settings::updateLineNumbers)
        SwitchRow(stringResource(R.string.settings_auto_complete), settings.autoComplete, settings::updateAutoComplete,
            subtitle = stringResource(R.string.settings_auto_complete_note))
        SwitchRow(stringResource(R.string.settings_keyboard_suggestions), settings.keyboardSuggestions, settings::updateKeyboardSuggestions,
            subtitle = stringResource(R.string.settings_keyboard_suggestions_note))
    }
}

/** How the story plays: the new text's fade, and the tags. */
@Composable
private fun PlayerSection(settings: AppSettings) {
    SettingsSection(stringResource(R.string.settings_player)) {
        SwitchRow(stringResource(R.string.settings_play_animation), settings.playAnimation, settings::updatePlayAnimation,
            subtitle = stringResource(R.string.settings_play_animation_note))
        SwitchRow(stringResource(R.string.settings_tags_visible), settings.tagsVisible, settings::updateTagsVisible,
            subtitle = stringResource(R.string.settings_tags_visible_note))
    }
}

/** Saving, and the folder new projects are made in. */
@Composable
private fun FilesSection(settings: AppSettings, projectsFolder: ProjectsFolderControl) {
    val folder by projectsFolder.folder.collectAsState()
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(StorageContracts.PickFolder()) { tree ->
        if (tree != null) scope.launch { problem = projectsFolder.set(tree)?.message(resources) }
    }
    SettingsSection(stringResource(R.string.settings_files)) {
        SwitchRow(stringResource(R.string.settings_autosave), settings.autosave, settings::updateAutosave,
            subtitle = stringResource(if (settings.autosave) R.string.settings_autosave_on_note else R.string.settings_autosave_off_note))
        val shown = folder
        SettingRow(
            stringResource(R.string.settings_projects_folder),
            subtitle = if (shown == null) stringResource(R.string.settings_projects_folder_none) else shown.displayName,
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(DeskTheme.dimens.gapS), verticalArrangement = Arrangement.spacedBy(DeskTheme.dimens.gapS)) {
                if (shown != null) {
                    DeskButton(stringResource(R.string.settings_projects_folder_clear), {
                        problem = null
                        projectsFolder.clear()
                    })
                }
                DeskButton(
                    stringResource(if (shown == null) R.string.settings_projects_folder_choose else R.string.settings_projects_folder_change),
                    { pick.launch(StorageContracts.DOCUMENTS_HINT) },
                    icon = DeskIcons.Folder,
                )
            }
        }
        problem?.let {
            Text(it, style = DeskTheme.type.labelSmall, color = DeskTheme.colors.danger, modifier = Modifier.testTag(SettingsTags.FOLDER_PROBLEM))
        }
    }
}

/** The version, what this app is and whose work it ports, and the licences. */
@Composable
private fun AboutSection(onOpenLicences: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val version = remember(context) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }
    SettingsSection(stringResource(R.string.settings_about)) {
        KeyValueRow(stringResource(R.string.settings_version), version, selectable = true)
        KeyValueRow(stringResource(R.string.settings_ink_version), stringResource(R.string.settings_ink_version_value), selectable = true)
        Text(stringResource(R.string.settings_about_note), style = DeskTheme.type.labelSmall, color = DeskTheme.colors.textTertiary,
            modifier = Modifier.padding(top = DeskTheme.dimens.gapXs))
        // buttons that do not fit side by side (a phone at a large font size) go under each other
        FlowRow(
            Modifier.padding(top = DeskTheme.dimens.gapS),
            horizontalArrangement = Arrangement.spacedBy(DeskTheme.dimens.gapS),
            verticalArrangement = Arrangement.spacedBy(DeskTheme.dimens.gapS),
        ) {
            DeskButton(stringResource(R.string.licences_title), onOpenLicences)
            DeskButton(stringResource(R.string.settings_upstream), { runCatching { uriHandler.openUri(UPSTREAM) } }, icon = DeskIcons.OpenWindow)
        }
    }
}

/** inkle's Inky, which this app ports. */
private const val UPSTREAM = "https://github.com/inkle/inky"
