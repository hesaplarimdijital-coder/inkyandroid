package com.inkyandroid.core.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The writer's answer to a conflict never throws a text away for good (review R4): "load theirs" is
 * an edit the writer can undo, and before either answer replaces a text, a copy of it is kept.
 */
class ConflictAnswerTest {

    private val drafts = MemoryDraftStore()
    private var now = 9_000L
    private val journal = DraftJournal(drafts) { now }

    private val mine = "My evening of writing\n"
    private val theirs = "Three pages written on the tablet\n"

    private suspend fun ProjectFixture.inConflict(): Pair<InkProject, FakeEditor> {
        store.put("main.ink", "Hello\n")
        val project = open(writeAhead = journal)
        val editor = project.edit("main.ink")
        editor.type(mine)
        store.put("main.ink", theirs)
        assertEquals(SaveResult.Conflict, project.save("main.ink"))
        return project to editor
    }

    @Test
    fun `load theirs keeps both texts before it replaces mine, because an undo may replace theirs in turn`() = projectTest {
        val (project, editor) = inConflict()

        project.resolveConflict("main.ink", keepMine = false)

        assertEquals(theirs, editor.text)
        val kept = journal.kept().associate { it.origin to it.text }
        assertEquals(mapOf(KeptVersion.Origin.Mine to mine, KeptVersion.Origin.Theirs to theirs), kept)
        assertTrue(journal.kept().all { it.path == "main.ink" && it.keptAt == 9_000L })
    }

    @Test
    fun `keep mine keeps the other version before it writes over it`() = projectTest {
        val (project, _) = inConflict()

        assertEquals(SaveResult.Saved, project.resolveConflict("main.ink", keepMine = true))

        assertEquals(mine, store.textOf("main.ink"))
        assertEquals(listOf(KeptVersion.Origin.Theirs to theirs), journal.kept().map { it.origin to it.text })
    }

    @Test
    fun `when the texts cannot be kept nothing is replaced`() = projectTest {
        val (project, editor) = inConflict()
        drafts.failure = { operation -> if (operation == "keep") IOException("no space left") else null }

        assertTrue(runCatching { project.resolveConflict("main.ink", keepMine = false) }.exceptionOrNull() is CopyNotKeptException)
        assertTrue(runCatching { project.resolveConflict("main.ink", keepMine = true) }.exceptionOrNull() is CopyNotKeptException)

        assertEquals(mine, editor.text)
        assertEquals(theirs, store.textOf("main.ink"))
        assertTrue(project.file("main.ink").hasConflict)
    }

    @Test
    fun `an editor that reports the loaded version a moment later leaves the file saved`() = projectTest {
        val (project, editor) = inConflict()
        editor.reportsLater = true

        project.resolveConflict("main.ink", keepMine = false)
        editor.report()

        assertFalse(project.file("main.ink").hasUnsavedChanges)
        assertEquals("nothing to write: storage has it", SaveResult.NothingToSave, project.save("main.ink"))
    }

    @Test
    fun `typing before that report leaves the file unsaved`() = projectTest {
        val (project, editor) = inConflict()
        editor.reportsLater = true

        project.resolveConflict("main.ink", keepMine = false)
        editor.typeUnreported("$theirs and one more line\n")
        editor.report()

        assertTrue(project.file("main.ink").hasUnsavedChanges)
    }

    @Test
    fun `a look at storage before the editor reported the loaded version takes a newer version as a reload`() = projectTest {
        val (project, editor) = inConflict()
        editor.reportsLater = true
        project.resolveConflict("main.ink", keepMine = false)

        store.put("main.ink", "$theirs, and a fourth page from the tablet\n")
        val report = project.syncWithStorage()

        assertEquals(listOf("main.ink"), report.reloaded)
        assertEquals("$theirs, and a fourth page from the tablet\n", editor.text)
    }

    @Test
    fun `undoing the loaded version is the writer's text again, unsaved`() = projectTest {
        val (project, editor) = inConflict()
        project.resolveConflict("main.ink", keepMine = false)

        // what the editor's undo does: the replaced text comes back as a change of the writer's
        editor.type(mine)

        assertTrue(project.file("main.ink").hasUnsavedChanges)
        assertEquals(SaveResult.Saved, project.save("main.ink"))
        assertEquals(mine, store.textOf("main.ink"))
        assertTrue("and theirs is kept", journal.kept().any { it.origin == KeptVersion.Origin.Theirs && it.text == theirs })
    }

    @Test
    fun `the kept versions of a project are capped, the oldest go first`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open(writeAhead = journal)
        val editor = project.edit("main.ink")
        repeat(DraftJournal.MAX_KEPT_VERSIONS) { round ->
            now += 1_000
            editor.type("mine $round\n")
            store.put("main.ink", "theirs $round\n")
            project.save("main.ink")
            project.resolveConflict("main.ink", keepMine = true)
        }

        val kept = journal.kept()

        assertEquals(DraftJournal.MAX_KEPT_VERSIONS, kept.size)
        assertEquals("theirs ${DraftJournal.MAX_KEPT_VERSIONS - 1}\n", kept.last().text)
        now += 1_000
        editor.type("mine last\n")
        store.put("main.ink", "theirs last\n")
        project.save("main.ink")
        project.resolveConflict("main.ink", keepMine = true)
        assertEquals(DraftJournal.MAX_KEPT_VERSIONS, journal.kept().size)
        assertFalse("the oldest went", journal.kept().any { it.text == "theirs 0\n" })
    }

    @Test
    fun `a kept version can be forgotten`() = projectTest {
        val (project, _) = inConflict()
        project.resolveConflict("main.ink", keepMine = true)

        journal.forgetKept(journal.kept().single().id)

        assertEquals(emptyList<KeptVersion>(), journal.kept())
    }
}
