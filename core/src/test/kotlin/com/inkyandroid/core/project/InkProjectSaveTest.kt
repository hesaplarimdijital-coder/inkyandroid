package com.inkyandroid.core.project

import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Saving: what is written, what a failure leaves behind, and that nothing made outside is overwritten unasked. */
class InkProjectSaveTest {

    @Test
    fun `save all writes the main file first and only the files that differ from storage`() = projectTest {
        // upstream saves the main file first; it rewrites every file, which would undo outside changes
        store.put("main.ink", "INCLUDE a.ink\nINCLUDE b.ink\n")
        store.put("a.ink", "A")
        store.put("b.ink", "B")
        store.put("spare.ink", "S")
        val project = open()
        project.edit("spare.ink").type("S2")
        project.edit("main.ink").type("INCLUDE a.ink\nINCLUDE b.ink\nMore\n")
        store.log.clear()

        val report = project.saveAll()

        assertEquals(listOf("main.ink", "spare.ink"), store.writes)
        assertEquals(mapOf("main.ink" to SaveResult.Saved, "spare.ink" to SaveResult.Saved), report.results)
        assertTrue(report.isComplete)
        assertEquals("S2", store.textOf("spare.ink"))
        assertFalse(project.state.value.hasUnsavedChanges)
    }

    @Test
    fun `saving with nothing unsaved writes nothing`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        store.log.clear()

        assertEquals(emptyMap<String, SaveResult>(), project.saveAll().results)
        assertEquals(SaveResult.NothingToSave, project.save("main.ink"))
        assertEquals(emptyList<String>(), store.log)
    }

    @Test
    fun `the text is written exactly, line endings and all`() = projectTest {
        store.put("main.ink", "one\r\ntwo\r\n")
        val project = open()
        project.edit("main.ink").type("one\r\ntwo\r\nüç\n\n")

        project.saveAll()

        assertEquals("one\r\ntwo\r\nüç\n\n", store.textOf("main.ink"))
    }

    @Test
    fun `text typed while a save is under way is kept and leaves the file unsaved`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        val editor = project.edit("main.ink")
        editor.type("first\n")
        store.writeDelayMillis = 100

        val saving = scope.async { project.saveAll() }
        pass(50)
        editor.type("first\nsecond\n")
        pass(50)

        assertTrue(saving.await().isComplete)
        assertEquals("the save wrote what the file was when it began", "first\n", store.textOf("main.ink"))
        assertEquals("first\nsecond\n", editor.text)
        assertTrue(project.file("main.ink").hasUnsavedChanges)

        project.saveAll()
        assertEquals("first\nsecond\n", store.textOf("main.ink"))
        assertFalse(project.file("main.ink").hasUnsavedChanges)
        assertFalse("its own earlier write is not an outside change", project.file("main.ink").hasConflict)
    }

    @Test
    fun `a failed write leaves the file unsaved, the text untouched and storage as it was`() = projectTest {
        store.put("main.ink", "Hello\n")
        store.put("a.ink", "A")
        val project = open()
        val editor = project.edit("main.ink")
        editor.type("Precious words\n")
        project.edit("a.ink").type("A2")
        store.failure = { operation, path -> if (operation == "write" && path == "main.ink") IOException("disk full") else null }

        val report = project.saveAll()

        assertFalse(report.isComplete)
        assertEquals("disk full", report.failed.getValue("main.ink").message)
        assertEquals("one bad file does not keep the others unsaved", SaveResult.Saved, report.results["a.ink"])
        assertEquals("Hello\n", store.textOf("main.ink"))
        assertEquals("Precious words\n", editor.text)
        assertEquals(listOf("main.ink"), project.state.value.unsavedFiles.map { it.path })

        store.failure = { _, _ -> null }
        assertTrue("the retry", project.saveAll().isComplete)
        assertEquals("Precious words\n", store.textOf("main.ink"))
        assertFalse(project.state.value.hasUnsavedChanges)
    }

    @Test
    fun `text typed and taken back needs no write`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        val editor = project.edit("main.ink")
        editor.type("Hello\nx")
        editor.type("Hello\n")
        assertTrue(project.file("main.ink").hasUnsavedChanges)
        store.log.clear()

        assertEquals(SaveResult.NothingToSave, project.save("main.ink"))

        assertEquals(emptyList<String>(), store.writes)
        assertFalse(project.file("main.ink").hasUnsavedChanges)
    }

    @Test
    fun `a save never overwrites a version it has not seen, it reports a conflict`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        val editor = project.edit("main.ink")
        editor.type("Mine\n")
        // another app saves the file; the project has not looked at storage since
        store.put("main.ink", "Theirs\n")

        val report = project.saveAll()

        assertEquals(listOf("main.ink"), report.conflicts)
        assertFalse(report.isComplete)
        assertEquals("Theirs\n", store.textOf("main.ink"))
        assertEquals("Mine\n", editor.text)
        assertTrue(project.file("main.ink").hasConflict)
        assertTrue(project.file("main.ink").hasUnsavedChanges)
        assertEquals(listOf("main.ink"), project.state.value.conflicts.map { it.path })

        store.log.clear()
        assertEquals("still nothing is written until the user has chosen", SaveResult.Conflict, project.save("main.ink"))
        assertEquals(emptyList<String>(), store.writes)
    }

    @Test
    fun `keep mine writes this text over storage's version`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        project.edit("main.ink").type("Mine\n")
        store.put("main.ink", "Theirs\n")
        project.saveAll()

        assertEquals(SaveResult.Saved, project.resolveConflict("main.ink", keepMine = true))

        assertEquals("Mine\n", store.textOf("main.ink"))
        assertFalse(project.file("main.ink").hasConflict)
        assertFalse(project.file("main.ink").hasUnsavedChanges)
    }

    @Test
    fun `load theirs replaces this text by storage's version, as a load and not as an edit`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        val editor = project.edit("main.ink")
        editor.echoesLoads = true
        editor.type("Mine\n")
        store.put("main.ink", "INCLUDE a.ink\nTheirs\n")
        store.put("a.ink", "")
        project.saveAll()

        project.resolveConflict("main.ink", keepMine = false)

        assertEquals("INCLUDE a.ink\nTheirs\n", editor.text)
        assertEquals(listOf("INCLUDE a.ink\nTheirs\n"), editor.loads)
        assertEquals("INCLUDE a.ink\nTheirs\n", store.textOf("main.ink"))
        assertFalse(project.file("main.ink").hasConflict)
        assertFalse(project.file("main.ink").hasUnsavedChanges)
        assertEquals("parsed at once after a load", listOf("a.ink"), project.file("main.ink").includes)
    }

    @Test
    fun `an outside change to the very text the user typed is no conflict`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        project.edit("main.ink").type("Same\n")
        store.put("main.ink", "Same\n")
        store.log.clear()

        assertEquals(SaveResult.NothingToSave, project.save("main.ink"))

        assertEquals(emptyList<String>(), store.writes)
        assertFalse(project.file("main.ink").hasUnsavedChanges)
        assertFalse(project.file("main.ink").hasConflict)
    }

    @Test
    fun `a file whose time stamp changed but not its content is saved over without a conflict`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        project.edit("main.ink").type("Mine\n")
        store.touch("main.ink")

        assertEquals(SaveResult.Saved, project.save("main.ink"))
        assertEquals("Mine\n", store.textOf("main.ink"))
    }

    @Test
    fun `a file deleted outside while it has unsaved changes is created again by the save`() = projectTest {
        store.put("main.ink", "INCLUDE a.ink\n")
        store.put("a.ink", "A")
        val project = open()
        project.edit("a.ink").type("A, rewritten")
        store.remove("a.ink")

        assertEquals(SaveResult.Saved, project.save("a.ink"))
        assertEquals("A, rewritten", store.textOf("a.ink"))
    }

    @Test
    fun `a new file is not written over a file that appeared at its path`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        project.addInclude("chapter", addToMain = false)
        project.edit("chapter.ink").type("My new chapter")
        store.put("chapter.ink", "Somebody else's chapter")

        assertEquals(SaveResult.Conflict, project.save("chapter.ink"))
        assertEquals("Somebody else's chapter", store.textOf("chapter.ink"))
        assertEquals("My new chapter", project.text("chapter.ink").toString())
    }

    @Test
    fun `a save that cannot ask storage fails without writing`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        project.edit("main.ink").type("Mine\n")
        store.failure = { operation, _ -> if (operation == "stat") IOException("permission lost") else null }

        val result = project.save("main.ink")

        assertEquals("permission lost", (result as SaveResult.Failed).error.message)
        assertEquals(emptyList<String>(), store.writes)
        assertTrue(project.file("main.ink").hasUnsavedChanges)
    }

    @Test
    fun `revert discards the unsaved changes and shows storage's version`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        val editor = project.edit("main.ink")
        editor.type("Scribbles\n")

        assertTrue(project.revert("main.ink"))

        assertEquals("Hello\n", editor.text)
        assertEquals(listOf("Hello\n"), editor.loads)
        assertFalse(project.file("main.ink").hasUnsavedChanges)
        assertEquals(emptyList<String>(), store.writes)
    }

    @Test
    fun `a file that is not on storage has nothing to revert to`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        project.addInclude("new", addToMain = false)
        project.edit("new.ink").type("text")

        assertFalse(project.revert("new.ink"))
        assertEquals("text", project.text("new.ink").toString())
    }

    @Test
    fun `saves run one at a time`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        project.edit("main.ink").type("Mine\n")
        store.writeDelayMillis = 100

        val first = scope.async { project.saveAll() }
        val second = scope.async { project.saveAll() }
        pass(300)

        assertTrue(first.await().isComplete)
        assertTrue(second.await().isComplete)
        assertEquals("the second save found nothing left to write", listOf("main.ink"), store.writes)
    }
}
