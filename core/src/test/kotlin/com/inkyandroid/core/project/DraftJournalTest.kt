package com.inkyandroid.core.project

import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext

/** The drafts journal: what is written when, what is discarded, and what a restore brings back. */
class DraftJournalTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val drafts = MemoryDraftStore()
    private var now = 5_000L
    private val journal = DraftJournal(drafts) { now }

    // ------------------------------------------------------------------ written

    @Test
    fun `a flush writes a draft of every unsaved file, with the hash of the stored version it was edited from`() = projectTest {
        store.put("main.ink", "INCLUDE a.ink\n")
        store.put("a.ink", "A")
        store.put("b.ink", "B")
        val project = open()
        project.edit("a.ink").type("A, edited")

        assertEquals(emptyMap<String, IOException>(), journal.flush(project))

        assertEquals(setOf("a.ink"), drafts.drafts.keys)
        assertEquals(Draft("a.ink", "A, edited", baseHash = TextHash.of("A"), writtenAt = 5_000L), drafts.drafts["a.ink"])
    }

    @Test
    fun `a draft is written once per change, not once per flush`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        val editor = project.edit("main.ink")
        editor.type("one")

        journal.flush(project)
        journal.flush(project)
        assertEquals(1, drafts.puts)

        editor.type("two")
        journal.flush(project)
        assertEquals(2, drafts.puts)
        assertEquals("two", drafts.drafts.getValue("main.ink").text)
    }

    @Test
    fun `a new file that was never saved has a draft without a base`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        project.addInclude("chapter", addToMain = true)

        journal.flush(project)

        assertEquals(Draft("chapter.ink", "", baseHash = null, writtenAt = 5_000L), drafts.drafts["chapter.ink"])
        assertEquals("INCLUDE chapter.ink\nHello\n", drafts.drafts.getValue("main.ink").text)
    }

    @Test
    fun `a draft that cannot be written is reported and tried again`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        project.edit("main.ink").type("text")
        drafts.failure = { operation -> if (operation == "put") IOException("no space left") else null }

        assertEquals("no space left", journal.flush(project).getValue("main.ink").message)

        drafts.failure = { null }
        assertEquals(emptyMap<String, IOException>(), journal.flush(project))
        assertEquals("text", drafts.drafts.getValue("main.ink").text)
    }

    // ------------------------------------------------------------------ discarded

    @Test
    fun `the draft of a file is removed once the file is saved`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        project.edit("main.ink").type("text")
        journal.flush(project)

        project.saveAll()
        journal.flush(project)

        assertEquals(emptySet<String>(), drafts.drafts.keys)
    }

    @Test
    fun `a failed save keeps the draft`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        project.edit("main.ink").type("Precious words\n")
        journal.flush(project)
        store.failure = { operation, _ -> if (operation == "write") IOException("disk full") else null }

        project.saveAll()
        journal.flush(project)

        assertEquals("Precious words\n", drafts.drafts.getValue("main.ink").text)
    }

    @Test
    fun `the draft of a reverted file is removed`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        project.edit("main.ink").type("text")
        journal.flush(project)

        project.revert("main.ink")
        journal.flush(project)

        assertEquals(emptySet<String>(), drafts.drafts.keys)
    }

    @Test
    fun `the draft of a deleted or renamed file does not stay behind`() = projectTest {
        store.put("main.ink", "Hello\n")
        store.put("a.ink", "A")
        store.put("b.ink", "B")
        val project = open()
        project.edit("a.ink").type("A2")
        val editor = project.edit("b.ink")
        editor.type("B2")
        journal.flush(project)

        project.delete("a.ink")
        project.rename("b.ink", "c.ink")
        journal.flush(project)

        assertEquals(setOf("c.ink"), drafts.drafts.keys)
        assertEquals("B2", drafts.drafts.getValue("c.ink").text)
    }

    @Test
    fun `discard all forgets every draft, also ones this journal did not write`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        project.edit("main.ink").type("text")
        journal.flush(project)
        drafts.drafts["left-over.ink"] = Draft("left-over.ink", "x", null, 1L)

        assertEquals(emptyList<String>(), journal.discardAll())
        assertEquals(emptySet<String>(), drafts.drafts.keys)
    }

    // ------------------------------------------------------------------ restored

    @Test
    fun `a draft over the version storage still holds comes back as unsaved changes`() = projectTest {
        store.put("main.ink", "INCLUDE a.ink\n")
        store.put("a.ink", "A")
        drafts.drafts["a.ink"] = Draft("a.ink", "A, and the paragraph that was never saved", TextHash.of("A"), 1L)
        val project = open()

        val report = journal.restore(project)

        assertEquals(DraftRestoreReport(restored = listOf("a.ink")), report)
        assertEquals("A, and the paragraph that was never saved", project.text("a.ink").toString())
        assertTrue(project.file("a.ink").hasUnsavedChanges)
        assertFalse(project.file("a.ink").hasConflict)
        assertEquals("storage is untouched by a restore", "A", store.textOf("a.ink"))
        assertEquals(emptyList<String>(), store.writes)
    }

    @Test
    fun `a restored draft is parsed, so the includes it adds are followed`() = projectTest {
        store.put("main.ink", "Hello\n")
        store.put("a.ink", "A")
        drafts.drafts["main.ink"] = Draft("main.ink", "INCLUDE a.ink\nHello\n", TextHash.of("Hello\n"), 1L)
        val project = open()
        assertTrue(project.file("a.ink").isSpare)

        journal.restore(project)

        assertFalse(project.file("a.ink").isSpare)
    }

    @Test
    fun `a draft over another version than storage holds now comes back as unsaved changes and as a conflict`() = projectTest {
        store.put("main.ink", "Changed elsewhere while the app was dead\n")
        drafts.drafts["main.ink"] = Draft("main.ink", "My unsaved words\n", TextHash.of("Hello\n"), 1L)
        val project = open()

        val report = journal.restore(project)

        assertEquals(DraftRestoreReport(conflicts = listOf("main.ink")), report)
        assertEquals("My unsaved words\n", project.text("main.ink").toString())
        assertTrue(project.file("main.ink").hasConflict)
        assertEquals("nothing is written until the user has chosen", SaveResult.Conflict, project.save("main.ink"))
        assertEquals("Changed elsewhere while the app was dead\n", store.textOf("main.ink"))
    }

    @Test
    fun `a draft whose text storage already holds is obsolete and dropped`() = projectTest {
        // the app died between the save and the removal of the draft
        store.put("main.ink", "Saved just in time\n")
        drafts.drafts["main.ink"] = Draft("main.ink", "Saved just in time\n", TextHash.of("Hello\n"), 1L)
        val project = open()

        val report = journal.restore(project)

        assertEquals(DraftRestoreReport(obsolete = listOf("main.ink")), report)
        assertTrue(report.isEmpty)
        assertFalse(project.state.value.hasUnsavedChanges)
        assertEquals(emptySet<String>(), drafts.drafts.keys)
    }

    @Test
    fun `the draft of a new file that was never saved brings the file back`() = projectTest {
        store.put("main.ink", "INCLUDE chapter.ink\n")
        drafts.drafts["chapter.ink"] = Draft("chapter.ink", "A whole new chapter", null, 1L)
        val project = open()
        assertEquals(listOf("chapter.ink"), project.state.value.missingIncludes)

        val report = journal.restore(project)

        assertEquals(listOf("chapter.ink"), report.restored)
        assertEquals("A whole new chapter", project.text("chapter.ink").toString())
        assertFalse(project.file("chapter.ink").isOnStorage)
        assertFalse(project.file("chapter.ink").isSpare)
        project.saveAll()
        assertEquals("A whole new chapter", store.textOf("chapter.ink"))
    }

    @Test
    fun `the draft of a file that was deleted outside brings the text back as a file that is not on storage`() = projectTest {
        store.put("main.ink", "Hello\n")
        drafts.drafts["gone.ink"] = Draft("gone.ink", "Text that exists nowhere else", TextHash.of("old"), 1L)
        val project = open()

        journal.restore(project)

        assertEquals("Text that exists nowhere else", project.text("gone.ink").toString())
        assertTrue(project.file("gone.ink").hasUnsavedChanges)
        assertFalse(project.file("gone.ink").isOnStorage)
    }

    @Test
    fun `a main file deleted outside while the app was dead opens with the text of its draft, as a file to create again`() = projectTest {
        store.put("chapter.ink", "Chapter one\n")
        drafts.drafts["main.ink"] = Draft("main.ink", "INCLUDE chapter.ink\nMy unsaved story\n", TextHash.of("Hello\n"), 1L)

        val project = open(writeAhead = journal)

        assertEquals("INCLUDE chapter.ink\nMy unsaved story\n", project.text("main.ink").toString())
        assertFalse(project.file("main.ink").isOnStorage)
        assertTrue(project.file("main.ink").hasUnsavedChanges)
        assertFalse(project.file("chapter.ink").isSpare)
        assertEquals(listOf("main.ink"), journal.restore(project).restored)
        assertTrue(project.saveAll().isComplete)
        assertEquals("INCLUDE chapter.ink\nMy unsaved story\n", store.textOf("main.ink"))
    }

    @Test
    fun `a restored draft is not written again until the text changes, and is removed once saved`() = projectTest {
        store.put("main.ink", "Hello\n")
        drafts.drafts["main.ink"] = Draft("main.ink", "Unsaved\n", TextHash.of("Hello\n"), 1L)
        val project = open()
        journal.restore(project)

        journal.flush(project)
        assertEquals(0, drafts.puts)

        project.saveAll()
        journal.flush(project)
        assertEquals(emptySet<String>(), drafts.drafts.keys)
        assertEquals("Unsaved\n", store.textOf("main.ink"))
    }

    @Test
    fun `a draft with a path that cannot be in a project is dropped`() = projectTest {
        store.put("main.ink", "Hello\n")
        drafts.drafts["../evil.ink"] = Draft("../evil.ink", "x", null, 1L)
        val project = open()

        assertTrue(journal.restore(project).isEmpty)
        assertEquals(listOf("main.ink"), project.paths)
        assertEquals(emptySet<String>(), drafts.drafts.keys)
    }

    // ------------------------------------------------------------------ the whole way, on real files

    @Test
    fun `text typed and journaled survives the death of the process`() = projectTest {
        val root = temporary.newFolder("project")
        val draftsDirectory = File(temporary.root, "drafts")
        File(root, "main.ink").writeText("INCLUDE chapter.ink\nOnce upon a time\n")
        File(root, "chapter.ink").writeText("Chapter one\n")
        val io = StandardTestDispatcher(scope.testScheduler)

        // --- the first life of the app
        val firstLife = kotlinx.coroutines.CoroutineScope(io)
        val firstProject = InkProject.open(firstLife, DirectoryProjectStore(root, io = io), "main.ink", LineScanParser, EmptyCoroutineContext)
        val firstJournal = DraftJournal(FileDraftStore(draftsDirectory, io))
        val editor = FakeEditor(firstProject.text("chapter.ink").toString())
        editor.onChange = { firstProject.textChanged("chapter.ink") }
        firstProject.attach("chapter.ink", editor)
        editor.type("Chapter one\nBir varmış, bir yokmuş: three hours of writing.\n")
        firstJournal.flush(firstProject)
        // the system kills the process: no save, no goodbye
        firstLife.cancel()
        assertEquals("Chapter one\n", File(root, "chapter.ink").readText())

        // --- the second life: new objects, the same directories
        val project = InkProject.open(scope.backgroundScope, DirectoryProjectStore(root, io = io), "main.ink", LineScanParser, EmptyCoroutineContext)
        val report = DraftJournal(FileDraftStore(draftsDirectory, io)).restore(project)

        assertEquals(listOf("chapter.ink"), report.restored)
        assertEquals("Chapter one\nBir varmış, bir yokmuş: three hours of writing.\n", project.text("chapter.ink").toString())
        assertTrue(project.file("chapter.ink").hasUnsavedChanges)
        assertNull("the other file has no draft and no change", project.state.value.unsavedFiles.firstOrNull { it.path == "main.ink" })

        assertTrue(project.saveAll().isComplete)
        assertEquals("Chapter one\nBir varmış, bir yokmuş: three hours of writing.\n", File(root, "chapter.ink").readText())
    }

    @Test
    fun `a process that dies in the middle of writing a draft leaves the previous draft readable`() = projectTest {
        val directory = File(temporary.root, "drafts")
        val io = StandardTestDispatcher(scope.testScheduler)
        val store = FileDraftStore(directory, io)
        store.put(Draft("main.ink", "the previous draft", null, 1L))
        // what a kill during the next put leaves: a half-written temporary file beside the draft
        val draftFile = directory.listFiles()!!.single()
        File(directory, draftFile.name + ".tmp").writeText("inky-draft 1\npath: main.ink\nbase: -\nwritten: 2\nsha256: 00\n\nhalf of the new dra")

        assertEquals(listOf("the previous draft"), FileDraftStore(directory, io).load().map { it.text })
    }
}
