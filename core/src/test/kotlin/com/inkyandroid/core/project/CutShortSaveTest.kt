package com.inkyandroid.core.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.charset.MalformedInputException
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The write-ahead rule: storage may write a file in place (the Storage Access Framework has no
 * atomic replace), so before a file is written its draft holds the text being written, and that
 * draft stays until a write has returned successfully. A write that fails or is cut short by the
 * death of the process never loses text, and its remains are never taken for a change from outside.
 */
class CutShortSaveTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val drafts = MemoryDraftStore()
    private var now = 5_000L
    private val journal = DraftJournal(drafts) { now }

    private val story = "Once upon a time\n"
    private val written = "Once upon a time\nBir varmış, bir yokmuş: three hours of writing.\n"

    // ------------------------------------------------------------------ the draft comes first and stays

    @Test
    fun `before a file is written its draft holds the text being written, marked as a save that has begun`() = projectTest {
        store.put("main.ink", story)
        val project = open(writeAhead = journal)
        project.edit("main.ink").type(written)
        store.writeDelayMillis = 1_000

        val save = scope.launch { project.saveAll() }
        pass(500)

        assertEquals("storage is being written", story, store.textOf("main.ink"))
        assertEquals(Draft("main.ink", written, TextHash.of(story), 5_000L, saving = true), drafts.drafts["main.ink"])
        journal.flush(project)
        assertNotNull("a flush while the write is under way keeps the draft", drafts.drafts["main.ink"])

        pass(500)
        save.join()
        assertEquals(written, store.textOf("main.ink"))
        journal.flush(project)
        assertEquals("the write has returned: the draft has done its work", emptySet<String>(), drafts.drafts.keys)
    }

    @Test
    fun `a save made before the draft timer fired still writes the draft first`() = projectTest {
        store.put("main.ink", story)
        val project = open(writeAhead = journal)
        // typed, and Ctrl+S within the same second: no flush has run
        project.edit("main.ink").type(written)
        store.failure = { operation, _ -> if (operation == "write") IOException("Permission lost") else null }

        project.saveAll()

        assertEquals(written, drafts.drafts["main.ink"]?.text)
    }

    @Test
    fun `a failed write leaves the draft in place, still marked as a save that did not finish`() = projectTest {
        store.put("main.ink", story)
        val project = open(writeAhead = journal)
        project.edit("main.ink").type(written)
        journal.flush(project)
        store.failure = { operation, _ -> if (operation == "write") IOException("Permission lost") else null }

        assertTrue(project.saveAll().results["main.ink"] is SaveResult.Failed)
        journal.flush(project)

        assertEquals(Draft("main.ink", written, TextHash.of(story), 5_000L, saving = true), drafts.drafts["main.ink"])
        assertTrue(project.file("main.ink").hasUnsavedChanges)
    }

    @Test
    fun `text typed after a failed write is journaled as unfinished too, until a write succeeds`() = projectTest {
        store.put("main.ink", story)
        val project = open(writeAhead = journal)
        val editor = project.edit("main.ink")
        editor.type(written)
        store.failure = { operation, _ -> if (operation == "write") IOException("Permission lost") else null }
        project.saveAll()

        editor.type("$written More.\n")
        journal.flush(project)
        assertEquals(true, drafts.drafts["main.ink"]?.saving)

        store.failure = { _, _ -> null }
        project.saveAll()
        editor.type("$written More, and more.\n")
        journal.flush(project)
        assertEquals("after a successful write the remains are gone", false, drafts.drafts["main.ink"]?.saving)
    }

    @Test
    fun `a draft that cannot be written does not stop the save`() = projectTest {
        store.put("main.ink", story)
        val project = open(writeAhead = journal)
        project.edit("main.ink").type(written)
        drafts.failure = { operation -> if (operation == "put") IOException("no space left") else null }

        assertTrue(project.saveAll().isComplete)

        assertEquals(written, store.textOf("main.ink"))
    }

    // ------------------------------------------------------------------ the remains of a failed write, while the app lives

    @Test
    fun `after a failed write left part of the text behind, the next save writes over it without calling it a change from outside`() = projectTest {
        store.put("main.ink", story)
        val project = open(writeAhead = journal)
        project.edit("main.ink").type(written)
        store.leavesBehind = { it.take(10) }
        store.failure = { operation, _ -> if (operation == "write") IOException("Provider died") else null }
        project.saveAll()
        assertEquals("Once upon ", store.textOf("main.ink"))

        store.failure = { _, _ -> null }
        val retry = project.saveAll()

        assertEquals(SaveResult.Saved, retry.results["main.ink"])
        assertEquals(written, store.textOf("main.ink"))
        assertFalse(project.file("main.ink").hasConflict)
    }

    @Test
    fun `a look at storage after a failed write neither reloads the remains nor calls them a conflict`() = projectTest {
        store.put("main.ink", story)
        val project = open(writeAhead = journal)
        val editor = project.edit("main.ink")
        editor.type(written)
        store.leavesBehind = { it.take(10) }
        store.failure = { operation, _ -> if (operation == "write") IOException("Provider died") else null }
        project.saveAll()

        val report = project.syncWithStorage()

        assertEquals(emptyList<String>(), report.reloaded)
        assertEquals(emptyList<String>(), report.conflicts)
        assertEquals(written, editor.text)
        assertTrue(project.file("main.ink").hasUnsavedChanges)
    }

    @Test
    fun `undoing back to the old text after a failed write still writes, because storage holds the remains`() = projectTest {
        store.put("main.ink", story)
        val project = open(writeAhead = journal)
        val editor = project.edit("main.ink")
        editor.type(written)
        store.leavesBehind = { it.take(10) }
        store.failure = { operation, _ -> if (operation == "write") IOException("Provider died") else null }
        project.saveAll()
        store.failure = { _, _ -> null }

        editor.type(story)
        project.saveAll()

        assertEquals(story, store.textOf("main.ink"))
    }

    @Test
    fun `a change made outside after a failed write is still a conflict`() = projectTest {
        store.put("main.ink", story)
        val project = open(writeAhead = journal)
        project.edit("main.ink").type(written)
        store.leavesBehind = { it.take(10) }
        store.failure = { operation, _ -> if (operation == "write") IOException("Provider died") else null }
        project.saveAll()
        store.failure = { _, _ -> null }

        store.put("main.ink", "Rewritten on the computer\n")

        assertEquals(SaveResult.Conflict, project.saveAll().results["main.ink"])
        assertEquals("Rewritten on the computer\n", store.textOf("main.ink"))
    }

    // ------------------------------------------------------------------ the process dies during the write

    @Test
    fun `after a write cut short by the death of the process the next life has the whole text over the remains, without a conflict`() = projectTest {
        store.put("main.ink", story)
        val dying = CoroutineScope(StandardTestDispatcher(scope.testScheduler))
        val first = InkProject.open(dying, store, "main.ink", LineScanParser, EmptyCoroutineContext, writeAhead = journal)
        val editor = FakeEditor(story)
        editor.onChange = { first.textChanged("main.ink") }
        first.attach("main.ink", editor)
        editor.type(written)
        store.leavesBehind = { it.take(25) }
        store.writeHangs = true

        dying.launch { first.saveAll() }
        pass(0)
        assertEquals("half of the new text is on storage, and nothing else happens", written.take(25), store.textOf("main.ink"))

        // the next life: new objects, the same storage and drafts
        store.writeHangs = false
        val secondJournal = DraftJournal(drafts) { now }
        val second = open(writeAhead = secondJournal)
        val report = secondJournal.restore(second)

        assertEquals(listOf("main.ink"), report.restored)
        assertEquals(emptyList<String>(), report.conflicts)
        assertEquals(written, second.text("main.ink").toString())
        assertTrue(second.file("main.ink").hasUnsavedChanges)
        assertFalse(second.file("main.ink").hasConflict)
        assertEquals(SaveResult.Saved, second.saveAll().results["main.ink"])
        assertEquals(written, store.textOf("main.ink"))
    }

    // ------------------------------------------------------------------ a "saving" draft whose save had finished (review R2)

    @Test
    fun `a saving draft left behind by a save that finished does not silently beat a later outside change`() = projectTest {
        store.put("main.ink", "Version A.\n")
        val first = open(writeAhead = journal)
        first.edit("main.ink").type("Version B, written on the phone.\n")
        assertEquals(SaveResult.Saved, first.save("main.ink"))
        // the process dies before the flush that removes the draft of the saved file
        assertTrue(drafts.drafts.getValue("main.ink").saving)
        // the other device changes the file afterwards, through the synced folder
        store.put("main.ink", "Version C, three pages written on the tablet.\n")

        val secondJournal = DraftJournal(drafts) { now }
        val second = open(writeAhead = secondJournal)
        val report = secondJournal.restore(second)
        second.saveAll()

        assertEquals(listOf("main.ink"), report.conflicts)
        assertEquals("both texts are there", "Version B, written on the phone.\n", second.text("main.ink").toString())
        assertEquals("the tablet's text is not overwritten without asking", "Version C, three pages written on the tablet.\n", store.textOf("main.ink"))
    }

    @Test
    fun `a saving draft over the version its save started from is the newer text, without a conflict`() = projectTest {
        store.put("main.ink", story)
        // the process died after the draft and before the write touched storage
        drafts.drafts["main.ink"] = Draft("main.ink", written, TextHash.of(story), 1L, saving = true)
        val project = open(writeAhead = journal)

        val report = journal.restore(project)

        assertEquals(listOf("main.ink"), report.restored)
        assertFalse(project.file("main.ink").hasConflict)
    }

    /**
     * Known and left as it is (review-final F5, a note): what storage holds is a beginning of the
     * "saving" draft's text, as a truncating write cut short leaves it, so the draft comes back without
     * a conflict, also when it was in fact the other device that cut the end after the save had
     * finished. No text is lost (the draft holds more than storage), but that cut is undone. Asking
     * instead would cost a question after every torn write; this test keeps the choice visible.
     */
    @Test
    fun `a saving draft over a beginning of its own text is taken for its torn write, also when another device cut the end`() = projectTest {
        store.put("main.ink", "Chapter one.\n")
        val first = open(writeAhead = journal)
        first.edit("main.ink").type("Chapter one.\nChapter two.\nChapter three, which the tablet later cuts.\n")
        assertEquals(SaveResult.Saved, first.save("main.ink"))
        // the process dies before the flush that removes the saving draft; then the tablet cuts chapter three
        store.put("main.ink", "Chapter one.\nChapter two.\n")

        val secondJournal = DraftJournal(drafts) { now }
        val second = open(writeAhead = secondJournal)
        val report = secondJournal.restore(second)

        assertEquals(listOf("main.ink"), report.restored)
        assertEquals(emptyList<String>(), report.conflicts)
        assertEquals("Chapter one.\nChapter two.\nChapter three, which the tablet later cuts.\n", second.text("main.ink").toString())
    }

    @Test
    fun `remains that are not a beginning of the draft's text, as an overwrite in place leaves them, are a conflict`() = projectTest {
        // a provider without truncation writes over the old text: the new beginning, then the old end
        store.put("main.ink", written.take(20) + story.drop(20).ifEmpty { "the old end\n" })
        drafts.drafts["main.ink"] = Draft("main.ink", written, TextHash.of(story), 1L, saving = true)
        val project = open(writeAhead = journal)

        val report = journal.restore(project)

        assertEquals("neither text is replaced without the writer's word", listOf("main.ink"), report.conflicts)
    }

    @Test
    fun `a main file cut in the middle of a character opens with the text its save was writing`() {
        val root = temporary.newFolder("project")
        File(root, "main.ink").writeText("INCLUDE chapter.ink\n$story")
        File(root, "chapter.ink").writeText("Chapter one\n")
        val text = "INCLUDE chapter.ink\n${story}Şu an, şimdi.\n"
        val directory = File(temporary.root, "drafts")
        dieDuringTheWriteOf("main.ink", text, root, directory, cutAfterBytes = "INCLUDE chapter.ink\n${story}Ş".toByteArray().size - 1)
        assertTrue("storage holds no text any more", runCatching { ProjectText.decode(File(root, "main.ink").readBytes()) }.isFailure)

        projectTest {
            val io = StandardTestDispatcher(scope.testScheduler)
            val journal = DraftJournal(FileDraftStore(directory, io))
            val project = InkProject.open(scope.backgroundScope, DirectoryProjectStore(root, io = io), "main.ink", LineScanParser, EmptyCoroutineContext, writeAhead = journal)
            val report = journal.restore(project)

            assertEquals(listOf("main.ink"), report.restored)
            assertEquals(text, project.text("main.ink").toString())
            assertFalse(project.file("chapter.ink").isSpare)
            assertTrue(project.file("main.ink").hasUnsavedChanges)
            assertTrue(project.file("main.ink").isOnStorage)

            assertTrue(project.saveAll().isComplete)
            assertArrayEquals(text.toByteArray(), File(root, "main.ink").readBytes())
            journal.flush(project)
            assertEquals(emptyList<Draft>(), FileDraftStore(directory, io).load())
        }
    }

    @Test
    fun `an included file cut in the middle of a character comes back from its draft`() {
        val root = temporary.newFolder("project")
        File(root, "main.ink").writeText("INCLUDE chapter.ink\n$story")
        File(root, "chapter.ink").writeText("Chapter one\n")
        val text = "Chapter one\nÇok güzel bir gün.\n"
        val directory = File(temporary.root, "drafts")
        dieDuringTheWriteOf("chapter.ink", text, root, directory, cutAfterBytes = "Chapter one\nÇ".toByteArray().size - 1)

        projectTest {
            val io = StandardTestDispatcher(scope.testScheduler)
            val journal = DraftJournal(FileDraftStore(directory, io))
            val project = InkProject.open(scope.backgroundScope, DirectoryProjectStore(root, io = io), "main.ink", LineScanParser, EmptyCoroutineContext, writeAhead = journal)
            assertTrue("before the restore the file cannot be read", "chapter.ink" in project.state.value.loadErrors)

            journal.restore(project)

            assertEquals(text, project.text("chapter.ink").toString())
            assertFalse(project.file("chapter.ink").hasConflict)
            assertTrue("chapter.ink" !in project.state.value.loadErrors)
            assertTrue(project.saveAll().isComplete)
            assertEquals(text, File(root, "chapter.ink").readText())
        }
    }

    @Test
    fun `without the draft of an unfinished save a main file that cannot be read does not open`() = projectTest {
        store.put("main.ink", story)
        drafts.drafts["main.ink"] = Draft("main.ink", written, TextHash.of(story), 1L, saving = false)
        store.failure = { operation, path -> if (operation == "read" && path == "main.ink") MalformedInputException(1) else null }

        val failure = runCatching { open(writeAhead = journal) }.exceptionOrNull()

        assertTrue(failure is MalformedInputException)
        assertEquals("the draft stays for a later open", written, drafts.drafts["main.ink"]?.text)
    }

    @Test
    fun `a draft over a file that cannot be read, from no save of ours, is a conflict the writer settles`() = projectTest {
        store.put("main.ink", "INCLUDE chapter.ink\n")
        store.put("chapter.ink", "Chapter one\n")
        drafts.drafts["chapter.ink"] = Draft("chapter.ink", "Chapter one, rewritten\n", TextHash.of("Chapter one\n"), 1L)
        store.failure = { operation, path -> if (operation == "read" && path == "chapter.ink") MalformedInputException(1) else null }
        val project = open(writeAhead = journal)

        val report = journal.restore(project)

        assertEquals(listOf("chapter.ink"), report.conflicts)
        assertEquals(SaveResult.Conflict, project.save("chapter.ink"))
        assertTrue("load theirs cannot read theirs: nothing changes", runCatching { project.resolveConflict("chapter.ink", keepMine = false) }.isFailure)
        assertEquals("Chapter one, rewritten\n", project.text("chapter.ink").toString())
        assertEquals(SaveResult.Saved, project.resolveConflict("chapter.ink", keepMine = true))
        assertEquals("Chapter one, rewritten\n", store.textOf("chapter.ink"))
    }

    // ------------------------------------------------------------------ handing the drafts on

    @Test
    fun `moving the drafts to another place puts every unsaved text there before forgetting its own`() = projectTest {
        store.put("main.ink", "INCLUDE a.ink\n")
        store.put("a.ink", "A")
        val project = open(writeAhead = journal)
        project.edit("a.ink").type("A, edited")
        project.edit("main.ink").type("INCLUDE a.ink\nMore\n")
        journal.flush(project)
        val elsewhere = MemoryDraftStore()

        journal.moveTo(project, elsewhere)

        assertEquals(setOf("a.ink", "main.ink"), elsewhere.drafts.keys)
        assertEquals("A, edited", elsewhere.drafts.getValue("a.ink").text)
        assertEquals(emptySet<String>(), drafts.drafts.keys)
    }

    @Test
    fun `when a draft cannot be moved nothing is forgotten`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open(writeAhead = journal)
        project.edit("main.ink").type("Unsaved\n")
        journal.flush(project)
        val full = MemoryDraftStore().apply { failure = { operation -> if (operation == "put") IOException("no space left") else null } }

        assertTrue(runCatching { journal.moveTo(project, full) }.isFailure)

        assertEquals("Unsaved\n", drafts.drafts["main.ink"]?.text)
    }

    // ------------------------------------------------------------------ an editor that reports a moment later

    @Test
    fun `a change the editor has not reported yet is never replaced by a reload`() = projectTest {
        store.put("main.ink", story)
        val project = open(writeAhead = journal)
        val editor = project.edit("main.ink")
        editor.typeUnreported(written)

        store.put("main.ink", "Rewritten on the computer\n")
        val report = project.syncWithStorage()

        assertEquals(emptyList<String>(), report.reloaded)
        assertEquals(listOf("main.ink"), report.conflicts)
        assertEquals(written, editor.text)
        assertTrue(project.file("main.ink").hasUnsavedChanges)
    }

    /**
     * The first life of the app on real files: [path] is edited to [text] and saved through a store
     * that writes in place; the process dies after [cutAfterBytes] bytes of the file were written.
     */
    private fun dieDuringTheWriteOf(path: String, text: String, root: File, draftsDirectory: File, cutAfterBytes: Int) = projectTest {
        val io = StandardTestDispatcher(scope.testScheduler)
        val inPlace = object : ProjectStore by DirectoryProjectStore(root, io = io) {
            override suspend fun write(path: String, text: String): StoredFile {
                File(root, path).writeBytes(text.toByteArray().copyOf(cutAfterBytes))
                awaitCancellation()
            }
        }
        val dying = CoroutineScope(io)
        val project = InkProject.open(dying, inPlace, "main.ink", LineScanParser, EmptyCoroutineContext, writeAhead = DraftJournal(FileDraftStore(draftsDirectory, io)))
        val editor = FakeEditor(project.text(path).toString())
        editor.onChange = { project.textChanged(path) }
        project.attach(path, editor)
        editor.type(text)
        dying.launch { project.saveAll() }
        pass(0)
        val draft = FileDraftStore(draftsDirectory, io).load().single()
        assertEquals("the draft of the save that never returns", Draft(path, text, draft.baseHash, draft.writtenAt, saving = true), draft)
    }
}
