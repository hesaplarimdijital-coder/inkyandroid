package com.inkyandroid.ui.extras.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.inkyandroid.R
import desktopkit.components.DeskButton
import desktopkit.components.DeskIcons
import desktopkit.components.DeskListRow
import desktopkit.screens.PageHeader
import desktopkit.shell.CompactTopBar
import desktopkit.shell.LocalNavKind
import desktopkit.shell.NavKind
import desktopkit.shell.rememberShellContentPadding
import desktopkit.theme.DeskTheme

/**
 * A licence the app ships other people's work under: its name, and its text as a raw resource
 * ([text] null: the work's notice, which comes with the work, holds the licence's text).
 */
enum class Licence(val label: String, @RawRes val text: Int?) {
    MitInky("MIT", R.raw.licence_mit_inky),
    MitInk("MIT", R.raw.licence_mit_ink),
    // the web export's own NOTICE (in :core, next to ink.js) carries the text
    MitInkjs("MIT", null),
    // blade-ink's Maven artifacts declare Apache 2.0, its source repository's LICENSE is MIT: both are named
    MitBladeInk("MIT · Apache License 2.0", R.raw.licence_mit_blade_ink),
    Apache2("Apache License 2.0", R.raw.licence_apache2),
    Bsd2("BSD 2-Clause", R.raw.licence_bsd2_commonmark),
    Ofl("SIL Open Font License 1.1", R.raw.licence_ofl_plex),
}

/**
 * Other people's work the app is made with and ships: [what], whose it is ([owner]), and under
 * which licence. [notice] is a NOTICE that is to be shown with it (the Apache licence asks for that);
 * [bundledNotice] one that comes with the work inside `:core`, read as a Java resource by that path.
 */
data class ThirdParty(
    val what: String,
    val owner: String,
    val licence: Licence,
    @RawRes val notice: Int? = null,
    val bundledNotice: String? = null,
)

/** The NOTICE of the web export's files (the template, inkjs), next to them in `:core`. */
const val WEB_EXPORT_NOTICE = "/com/inkyandroid/core/engine/web/NOTICE.txt"

/** What the APK holds of other people's work. The test libraries are not in it, and are not listed. */
val THIRD_PARTIES = listOf(
    // the design, the manual "Writing with ink", the snippets and the web player's template
    ThirdParty("Inky", "inkle Ltd.", Licence.MitInky),
    ThirdParty("ink", "inkle Ltd.", Licence.MitInk),
    ThirdParty("blade-ink, blade-ink-compiler", "Rafael Garcia", Licence.MitBladeInk),
    // the runtime "Export for web" writes next to the story
    ThirdParty("inkjs 2.4.0", "inkle Ltd., inkjs contributors", Licence.MitInkjs, bundledNotice = WEB_EXPORT_NOTICE),
    ThirdParty("desktop-kit", "desktop-kit contributors", Licence.Apache2, notice = R.raw.notice_desktop_kit),
    ThirdParty("IBM Plex Sans, IBM Plex Mono", "IBM Corp.", Licence.Ofl),
    ThirdParty("Haze", "Christopher Banes", Licence.Apache2),
    ThirdParty("commonmark-java", "Atlassian Pty Ltd", Licence.Bsd2),
    ThirdParty("Jetpack Compose, AndroidX (Activity, Core, Lifecycle, Navigation)", "The Android Open Source Project", Licence.Apache2),
    ThirdParty("Kotlin, kotlinx.coroutines", "JetBrains s.r.o.", Licence.Apache2),
)

/** Names the tests find the licences page by. */
object LicenceTags {
    const val LIST = "licences-list"
    const val TEXT = "licence-text"
}

/**
 * "Open-source licences": the work of others the app is made with, whose it is and under which
 * licence; one tapped shows its licence's text (with its NOTICE, where it has one), to read and to
 * select. Back goes from the text to the list, and from the list to [onClose].
 */
@Composable
fun LicencesScreen(onClose: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = THIRD_PARTIES.firstOrNull { it.what == open }
    if (shown != null) {
        BackHandler { open = null }
        LicenceText(shown, onBack = { open = null })
        return
    }
    BackHandler(onBack = onClose)
    val title = stringResource(R.string.licences_title)
    val sidebar = LocalNavKind.current == NavKind.Sidebar
    CompactTopBar(title = title, onBack = onClose, actions = emptyList(), showSearch = false, showOnRail = true)
    val shell = rememberShellContentPadding()
    Column(Modifier.fillMaxSize().padding(top = shell.calculateTopPadding())) {
        PageHeader(title, subtitle = stringResource(R.string.licences_note)) {
            if (sidebar) DeskButton(stringResource(desktopkit.R.string.dk_back), onClose, icon = DeskIcons.ArrowLeft)
        }
        LazyColumn(
            Modifier.fillMaxSize().testTag(LicenceTags.LIST),
            contentPadding = PaddingValues(bottom = shell.calculateBottomPadding() + DeskTheme.dimens.gapS),
        ) {
            items(THIRD_PARTIES, key = { it.what }) { party ->
                DeskListRow(
                    title = party.what,
                    subtitle = party.owner + " · " + party.licence.label,
                    onClick = { open = party.what },
                )
            }
        }
    }
}

/** One licence's text, as it was written, with the NOTICE of the work before it where there is one. */
@Composable
private fun LicenceText(party: ThirdParty, onBack: () -> Unit) {
    val context = LocalContext.current
    val text = remember(party) {
        val read = { id: Int -> context.resources.openRawResource(id).bufferedReader().use { it.readText() }.replace("\r\n", "\n").trim() }
        listOfNotNull(party.notice?.let(read), party.bundledNotice?.let(::readBundled), party.licence.text?.let(read)).joinToString("\n\n\n")
    }
    val sidebar = LocalNavKind.current == NavKind.Sidebar
    CompactTopBar(title = party.what, onBack = onBack, actions = emptyList(), showSearch = false, showOnRail = true)
    val shell = rememberShellContentPadding()
    Column(Modifier.fillMaxSize().padding(top = shell.calculateTopPadding())) {
        PageHeader(party.what, subtitle = party.licence.label) {
            if (sidebar) DeskButton(stringResource(desktopkit.R.string.dk_back), onBack, icon = DeskIcons.ArrowLeft)
        }
        // the text in pieces, a paragraph each: a long licence is drawn as it is scrolled to
        val paragraphs = remember(text) { text.split(Regex("\n\\s*\n")).map { it.trimEnd() }.filter { it.isNotBlank() } }
        SelectionContainer(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().testTag(LicenceTags.TEXT),
                contentPadding = PaddingValues(bottom = shell.calculateBottomPadding() + DeskTheme.dimens.gapS),
            ) {
                items(paragraphs) { paragraph ->
                    Text(
                        paragraph,
                        style = DeskTheme.type.mono,
                        color = DeskTheme.colors.textSecondary,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = DeskTheme.dimens.pagePadding, vertical = DeskTheme.dimens.gapXs),
                    )
                }
            }
        }
    }
}

/** A text that comes inside `:core` (a Java resource at [path]), or an empty text if it is missing. */
internal fun readBundled(path: String): String =
    ThirdParty::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }?.replace("\r\n", "\n")?.trim().orEmpty()
