package com.inkyandroid.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import desktopkit.input.CommonShortcuts
import desktopkit.input.KeyChord
import desktopkit.shell.NavKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What the shell shows in each layout, the routes of the files, and the shortcut table. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class ShellSpecTest {

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    private val files = NavFiles(
        projectName = "Story",
        main = "story.ink",
        groups = listOf("" to listOf("intro.ink"), "chapters" to listOf("chapters/one.ink", "chapters/two.ink")),
        spare = listOf("notes/old.ink"),
    )

    @Test
    fun aPhoneHasWritePlayAndFilesAndTheRestUnderTheSheet() {
        val spec = buildShellSpec(resources, files, playOnRail = true, docs = true, settings = true)
        assertEquals(listOf(Routes.WRITE, Routes.PLAY, Routes.FILES), spec.bottomBarRoutes)
        assertEquals(Routes.WRITE, spec.startRoute)
    }

    @Test
    fun playIsOnTheRailOnlyWhereTheEditorAndThePlayerDoNotFitSideBySide() {
        assertTrue(Routes.PLAY in buildShellSpec(resources, files, playOnRail = true, docs = true, settings = true).railRoutes)
        assertFalse(Routes.PLAY in buildShellSpec(resources, files, playOnRail = false, docs = true, settings = true).railRoutes)
    }

    @Test
    fun aLargeWindowsSidebarListsTheFilesAsUpstreamsFilesViewGroupsThem() {
        val spec = buildShellSpec(resources, files, playOnRail = false, docs = true, settings = true)
        val sidebar = spec.sections.map { section -> section.title to section.items.filter { NavKind.Sidebar in it.showIn }.map { it.label } }
        assertEquals("Story" to listOf("story.ink", "intro.ink"), sidebar[1])
        assertEquals("chapters" to listOf("one.ink", "two.ink"), sidebar[2])
        // what nothing includes is listed by its path, under upstream's "Unused files"
        assertEquals("Unused files" to listOf("notes/old.ink"), sidebar[3])
        // the screens of the sidebar: Write and Files are the phone's and the rail's
        assertEquals(listOf("Issues", "Projects", "Documentation"), sidebar[0].second)
        // a file's entry is a page of Write: where files are not listed, Write stays marked
        assertTrue(spec.sections.drop(1).flatMap { it.items }.all { it.parent == Routes.WRITE })
        assertEquals(Routes.SETTINGS, spec.footer?.route)
    }

    @Test
    fun withoutAProjectTheSidebarOffersWriteAsTheWayToOne() {
        val spec = buildShellSpec(resources, null, playOnRail = false, docs = true, settings = true)
        assertTrue(spec.sections.single().items.first { it.route == Routes.WRITE }.showIn.contains(NavKind.Sidebar))
    }

    @Test
    fun aFilesRouteCarriesItsPathWhateverItContains() {
        listOf("story.ink", "chapters/one.ink", "Hikâye klasörü/bölüm 1.ink", "a?b#c&d=e.ink").forEach { path ->
            val route = Routes.file(path)
            assertTrue("a plain word for the navigation graph: $route", route.removePrefix("file/").all { it.isLetterOrDigit() || it == '-' || it == '_' })
            assertEquals(path, Routes.filePath(route))
            assertTrue(Routes.isWorkspace(route))
        }
        assertNull(Routes.filePath(Routes.WRITE))
        assertTrue(Routes.isWorkspace(Routes.WRITE))
        assertFalse(Routes.isWorkspace(Routes.PLAY))
    }

    @Test
    fun theBadgesCountIssuesAndMarkUnsavedFiles() {
        val badges = navBadges(3, mapOf("story.ink" to com.inkyandroid.ui.files.FileIssues(2, com.inkyandroid.core.engine.IssueKind.Error)), setOf("story.ink", "intro.ink"))
        assertEquals("3", badges[Routes.ISSUES])
        assertEquals("2 •", badges[Routes.file("story.ink")])
        assertEquals("•", badges[Routes.file("intro.ink")])
    }

    @Test
    fun noTwoCommandsShareAKey() {
        assertTrue(shortcuts.conflicts().toString(), shortcuts.conflicts().isEmpty())
    }

    @Test
    fun upstreamsKeysAreKeptWhereTheKitLeavesThemFree() {
        fun chord(id: String): KeyChord = shortcuts[id].chord
        assertEquals(KeyChord(androidx.compose.ui.input.key.Key.S, ctrl = true), chord(Commands.SAVE))
        assertEquals(KeyChord(androidx.compose.ui.input.key.Key.P, ctrl = true), chord(Commands.GOTO_ANYTHING))
        assertEquals(KeyChord(androidx.compose.ui.input.key.Key.Period, ctrl = true), chord(Commands.NEXT_ISSUE))
        assertEquals(KeyChord(androidx.compose.ui.input.key.Key.C, ctrl = true, shift = true), chord(Commands.STATISTICS))
        assertEquals(KeyChord(androidx.compose.ui.input.key.Key.S, ctrl = true, alt = true), chord(Commands.EXPORT_JS))
        assertEquals(KeyChord(androidx.compose.ui.input.key.Key.F1), chord(Commands.DOCUMENTATION))
        // the kit's own keys are there, and win where upstream had another use for one
        listOf(CommonShortcuts.SEARCH, CommonShortcuts.SETTINGS, CommonShortcuts.TOGGLE_SIDEBAR, CommonShortcuts.SHORTCUT_LIST).forEach { id ->
            assertTrue(id, shortcuts.getOrNull(id) != null)
        }
    }

    @Test
    fun theEditorsOwnKeysAreListedButNeverRoutedFromTheActivity() {
        listOf(Commands.UNDO, Commands.REDO, Commands.CUT, Commands.COPY, Commands.PASTE, Commands.SELECT_ALL).forEach { id ->
            assertEquals(id, EditorScope, shortcuts[id].scope)
        }
    }
}
