package com.inkyandroid.core.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text flowing in from an editor: the unsaved flag, the delayed parse, includes discovered while typing, add include, rename, delete. */
class InkProjectEditingTest {

    @Test
    fun `an edit makes the file unsaved at once`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        val revision = project.contentRevision.value
        val editor = project.edit("main.ink")

        editor.type("Hello, world\n")

        // no time has passed
        assertTrue(project.file("main.ink").hasUnsavedChanges)
        assertTrue(project.state.value.hasUnsavedChanges)
        assertEquals(listOf("main.ink"), project.state.value.unsavedFiles.map { it.path })
        assertTrue(project.contentRevision.value > revision)
        assertEquals("Hello, world\n", project.text("main.ink").toString())
    }

    @Test
    fun `typing copies nothing, the text is pulled when the parse is due`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        val editor = project.edit("main.ink")
        val revision = project.revision("main.ink")
        editor.type("Hello\n!")
        val afterFirstKeystroke = project.state.value

        repeat(500) { editor.type("Hello $it\n") }

        assertEquals("the editor was never asked for its text while the user typed", 0, editor.snapshots)
        assertEquals(501L, project.revision("main.ink") - revision)
        assertSame("the snapshot changed for the unsaved flag, not for every keystroke", afterFirstKeystroke, project.state.value)

        pass(InkProject.PARSE_DELAY_MILLIS)
        assertEquals(1, editor.snapshots)
    }

    @Test
    fun `symbols are parsed two hundred milliseconds after the last change`() = projectTest {
        store.put("main.ink", "Hello\n")
        store.put("chapter.ink", "")
        val project = open()
        val editor = project.edit("main.ink")
        assertTrue(project.file("chapter.ink").isSpare)

        editor.type("INCLUDE chapter.ink\nHello\n")
        pass(199)
        assertTrue("not parsed yet", project.file("chapter.ink").isSpare)

        editor.type("INCLUDE chapter.ink\nHello!\n")
        pass(199)
        assertTrue("the delay starts again with every change", project.file("chapter.ink").isSpare)

        pass(1)
        assertFalse(project.file("chapter.ink").isSpare)
        assertEquals(listOf("chapter.ink"), project.file("main.ink").includes)
    }

    @Test
    fun `typing an INCLUDE line of a file that is on storage but not in the project loads it`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        // appears after the project was opened: the project has not seen it
        store.put("late.txt", "LATE")
        val editor = project.edit("main.ink")

        editor.type("INCLUDE late.txt\nHello\n")
        pass(InkProject.PARSE_DELAY_MILLIS)

        assertEquals(listOf("main.ink", "late.txt"), project.paths)
        assertEquals("LATE", project.text("late.txt").toString())
        assertFalse(project.file("late.txt").hasUnsavedChanges)
        assertEquals(emptyList<String>(), project.state.value.missingIncludes)
    }

    @Test
    fun `typing an INCLUDE line of a file that does not exist creates nothing and asks storage only once`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        val editor = project.edit("main.ink")

        editor.type("INCLUDE ghost.ink\nHello\n")
        pass(InkProject.PARSE_DELAY_MILLIS)
        editor.type("INCLUDE ghost.ink\nHello there\n")
        pass(InkProject.PARSE_DELAY_MILLIS)

        assertEquals(listOf("main.ink"), project.paths)
        assertEquals(listOf("ghost.ink"), project.state.value.missingIncludes)
        assertEquals(1, store.log.count { it == "stat ghost.ink" })
        assertEquals(setOf("main.ink"), store.paths)
    }

    @Test
    fun `removing an INCLUDE line makes the file spare again, it stays in the project`() = projectTest {
        store.put("main.ink", "INCLUDE a.ink\nHello\n")
        store.put("a.ink", "A")
        val project = open()

        project.edit("main.ink").type("Hello\n")
        pass(InkProject.PARSE_DELAY_MILLIS)

        assertEquals(listOf("main.ink", "a.ink"), project.paths)
        assertTrue(project.file("a.ink").isSpare)
    }

    @Test
    fun `settle runs the pending parses without waiting for their delay`() = projectTest {
        store.put("main.ink", "Hello\n")
        store.put("a.ink", "")
        val project = open()

        project.edit("main.ink").type("INCLUDE a.ink\n")
        project.settle()

        assertFalse(project.file("a.ink").isSpare)
    }

    @Test
    fun `a parser that throws leaves the file with what it had`() = projectTest {
        store.put("main.ink", "INCLUDE a.ink\n")
        store.put("a.ink", "")
        val project = open(parser = { text -> if (text.contains("BOOM")) error("parser bug") else LineScanParser.parse(text) })

        project.edit("main.ink").type("INCLUDE a.ink\nBOOM\n")
        pass(InkProject.PARSE_DELAY_MILLIS)

        assertFalse(project.file("a.ink").isSpare)
        assertTrue(project.file("main.ink").hasUnsavedChanges)
    }

    @Test
    fun `a new parser parses every file again`() = projectTest {
        store.put("main.ink", "INCLUDE a.ink\n")
        store.put("a.ink", "")
        val project = open(parser = { ParsedInk(emptyList(), -1, null) })
        assertTrue(project.file("a.ink").isSpare)

        project.setParser(LineScanParser)

        assertFalse(project.file("a.ink").isSpare)
    }

    @Test
    fun `detaching the editor leaves its text with the project`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        project.edit("main.ink").type("Kept\n")

        project.detach("main.ink")

        assertEquals("Kept\n", project.text("main.ink").toString())
        assertTrue(project.file("main.ink").hasUnsavedChanges)
        project.saveAll()
        assertEquals("Kept\n", store.textOf("main.ink"))
    }

    // ------------------------------------------------------------------ add include (inkProject.js addNewInclude)

    @Test
    fun `add include creates an empty unsaved file and writes nothing`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        store.log.clear()

        val result = project.addInclude("chapter", addToMain = false)

        assertEquals(AddIncludeResult.Added("chapter.ink"), result)
        assertEquals("", project.text("chapter.ink").toString())
        assertTrue(project.file("chapter.ink").hasUnsavedChanges)
        assertFalse(project.file("chapter.ink").isOnStorage)
        assertTrue("not added to the main file: an unused file", project.file("chapter.ink").isSpare)
        assertEquals("Hello\n", project.text("main.ink").toString())
        assertEquals(emptyList<String>(), store.writes)
    }

    @Test
    fun `add include with a main file without includes puts the INCLUDE line at the very top`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()

        project.addInclude("Folder/inkFileName.ink", addToMain = true)

        assertEquals("INCLUDE Folder/inkFileName.ink\nHello\n", project.text("main.ink").toString())
        assertFalse(project.file("Folder/inkFileName.ink").isSpare)
        assertTrue(project.file("main.ink").hasUnsavedChanges)
    }

    @Test
    fun `add include puts the INCLUDE line directly after the last include line`() = projectTest {
        store.put("main.ink", "// title\nINCLUDE a.ink\nINCLUDE b.ink\n\nHello\n")
        store.put("a.ink", "")
        store.put("b.ink", "")
        val project = open()

        project.addInclude("c", addToMain = true)
        project.addInclude("d", addToMain = true)

        assertEquals("// title\nINCLUDE a.ink\nINCLUDE b.ink\nINCLUDE c.ink\nINCLUDE d.ink\n\nHello\n", project.text("main.ink").toString())
        assertEquals(listOf("main.ink", "a.ink", "b.ink", "c.ink", "d.ink"), project.paths)
    }

    @Test
    fun `add include keeps the main file's line endings`() = projectTest {
        store.put("main.ink", "INCLUDE a.ink\r\nHello\r\n")
        store.put("a.ink", "")
        val project = open()

        project.addInclude("b", addToMain = true)

        assertEquals("INCLUDE a.ink\r\nINCLUDE b.ink\r\nHello\r\n", project.text("main.ink").toString())
    }

    @Test
    fun `add include edits the main file through its editor, as an edit that can be undone`() = projectTest {
        store.put("main.ink", "INCLUDE a.ink\nHello\n")
        store.put("a.ink", "")
        val project = open()
        val editor = project.edit("main.ink")
        // typed a moment ago: the project has not parsed it yet
        editor.type("First line\nINCLUDE a.ink\nHello\n")

        project.addInclude("b", addToMain = true)

        assertEquals("First line\nINCLUDE a.ink\nINCLUDE b.ink\nHello\n", editor.text)
        assertEquals(listOf("\nINCLUDE b.ink"), editor.edits)
        assertEquals(emptyList<String>(), editor.loads)
    }

    @Test
    fun `add include refuses a path that is already in the project`() = projectTest {
        store.put("main.ink", "Hello\n")
        store.put("chapter.ink", "x")
        val project = open()

        assertEquals(AddIncludeResult.AlreadyExists("chapter.ink"), project.addInclude("chapter", addToMain = true))
        assertEquals(AddIncludeResult.AlreadyExists("main.ink"), project.addInclude("main.ink", addToMain = true))
        assertEquals("Hello\n", project.text("main.ink").toString())
    }

    @Test
    fun `add include refuses a path that exists on storage even if the project has not seen it`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        store.put("chapter.ink", "written elsewhere a second ago")

        assertEquals(AddIncludeResult.AlreadyExists("chapter.ink"), project.addInclude("chapter", addToMain = true))
        assertEquals("written elsewhere a second ago", store.textOf("chapter.ink"))
    }

    @Test
    fun `add include refuses a name that cannot be a file in the project`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()

        assertEquals(AddIncludeResult.InvalidName("../x"), project.addInclude("../x", addToMain = true))
        assertEquals(AddIncludeResult.InvalidName("   "), project.addInclude("   ", addToMain = true))
        assertEquals(listOf("main.ink"), project.paths)
    }

    @Test
    fun `saving after add include writes the main file first and creates the new file with its folder`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        project.addInclude("chapters/one", addToMain = true)
        store.log.clear()

        val report = project.saveAll()

        assertTrue(report.isComplete)
        assertEquals(listOf("main.ink", "chapters/one.ink"), store.writes)
        assertEquals("", store.textOf("chapters/one.ink"))
        assertEquals("INCLUDE chapters/one.ink\nHello\n", store.textOf("main.ink"))
        assertFalse(project.state.value.hasUnsavedChanges)
        assertTrue(project.file("chapters/one.ink").isOnStorage)
    }

    // ------------------------------------------------------------------ rename

    @Test
    fun `rename moves the file on storage and rewrites the INCLUDE lines that name it`() = projectTest {
        store.put("main.ink", "INCLUDE one.ink // the beginning\nINCLUDE two.ink\nHello\n")
        store.put("one.ink", "ONE")
        store.put("two.ink", "  INCLUDE one.ink  \nTwo\n")
        val project = open()

        val result = project.rename("one.ink", "chapters/first")

        assertEquals(RenameResult.Renamed("one.ink", "chapters/first.ink", listOf("main.ink", "two.ink")), result)
        assertEquals("ONE", store.textOf("chapters/first.ink"))
        assertFalse("one.ink" in store.paths)
        assertEquals("INCLUDE chapters/first.ink // the beginning\nINCLUDE two.ink\nHello\n", project.text("main.ink").toString())
        assertEquals("  INCLUDE chapters/first.ink  \nTwo\n", project.text("two.ink").toString())
        assertEquals(listOf("main.ink", "chapters/first.ink", "two.ink"), project.paths)
        assertFalse(project.file("chapters/first.ink").isSpare)
        assertFalse("the renamed file itself has nothing to save", project.file("chapters/first.ink").hasUnsavedChanges)
        assertEquals(listOf("main.ink", "two.ink"), project.state.value.unsavedFiles.map { it.path })
    }

    @Test
    fun `rename rewrites through the editor of an open file`() = projectTest {
        store.put("main.ink", "INCLUDE old.ink\n")
        store.put("old.ink", "")
        val project = open()
        val editor = project.edit("main.ink")

        project.rename("old.ink", "new.ink")

        assertEquals("INCLUDE new.ink\n", editor.text)
        assertEquals(listOf("new.ink"), editor.edits)
    }

    @Test
    fun `the text and the editor of a renamed file follow it`() = projectTest {
        store.put("main.ink", "INCLUDE old.ink\n")
        store.put("old.ink", "old text")
        val project = open()
        val editor = project.edit("old.ink")
        editor.type("unsaved text")

        project.rename("old.ink", "new.ink")
        editor.onChange = { project.textChanged("new.ink") }

        assertEquals("unsaved text", project.text("new.ink").toString())
        assertTrue(project.file("new.ink").hasUnsavedChanges)
        project.saveAll()
        assertEquals("unsaved text", store.textOf("new.ink"))
        assertFalse("old.ink" in store.paths)
    }

    @Test
    fun `renaming a file that was never saved touches no storage`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        project.addInclude("draft", addToMain = true)
        store.log.clear()

        val result = project.rename("draft.ink", "chapter.ink")

        assertEquals(RenameResult.Renamed("draft.ink", "chapter.ink", listOf("main.ink")), result)
        assertTrue(store.log.none { it.startsWith("rename") || it.startsWith("write") })
        assertEquals("INCLUDE chapter.ink\nHello\n", project.text("main.ink").toString())
    }

    @Test
    fun `rename refuses the main file, an existing path and a bad name`() = projectTest {
        store.put("main.ink", "INCLUDE a.ink\n")
        store.put("a.ink", "A")
        store.put("b.ink", "B")
        val project = open()
        store.put("unseen.ink", "appeared outside")

        assertEquals(RenameResult.IsMainFile, project.rename("main.ink", "story.ink"))
        assertEquals(RenameResult.AlreadyExists("b.ink"), project.rename("a.ink", "b"))
        assertEquals(RenameResult.AlreadyExists("unseen.ink"), project.rename("a.ink", "unseen.ink"))
        assertEquals(RenameResult.InvalidName("../a"), project.rename("a.ink", "../a"))
        assertEquals(RenameResult.NotInProject("zzz.ink"), project.rename("zzz.ink", "y"))
        assertEquals("A", store.textOf("a.ink"))
        assertEquals("B", store.textOf("b.ink"))
        assertEquals("INCLUDE a.ink\n", project.text("main.ink").toString())
    }

    @Test
    fun `a rename that storage refuses changes nothing`() = projectTest {
        store.put("main.ink", "INCLUDE a.ink\n")
        store.put("a.ink", "A")
        val project = open()
        store.failure = { operation, _ -> if (operation == "rename") java.io.IOException("read-only") else null }

        val result = project.rename("a.ink", "b.ink")

        assertTrue(result is RenameResult.Failed)
        assertEquals(listOf("main.ink", "a.ink"), project.paths)
        assertEquals("INCLUDE a.ink\n", project.text("main.ink").toString())
        assertFalse(project.state.value.hasUnsavedChanges)
    }

    // ------------------------------------------------------------------ delete

    @Test
    fun `delete removes the file from storage and from the project and leaves the INCLUDE line`() = projectTest {
        store.put("main.ink", "INCLUDE a.ink\nHello\n")
        store.put("a.ink", "A")
        val project = open()

        assertEquals(DeleteResult.Deleted("a.ink"), project.delete("a.ink"))

        assertEquals(listOf("main.ink"), project.paths)
        assertFalse("a.ink" in store.paths)
        assertEquals("INCLUDE a.ink\nHello\n", project.text("main.ink").toString())
        assertEquals(listOf("a.ink"), project.state.value.missingIncludes)
        assertFalse("a.ink" in project.sources().texts)
    }

    @Test
    fun `the main file is never deleted`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()

        assertEquals(DeleteResult.IsMainFile, project.delete("main.ink"))
        assertEquals("Hello\n", store.textOf("main.ink"))
        assertEquals(DeleteResult.NotInProject("x.ink"), project.delete("x.ink"))
    }

    @Test
    fun `deleting a file that was never saved touches no storage, one already gone is fine`() = projectTest {
        store.put("main.ink", "Hello\n")
        store.put("a.ink", "A")
        val project = open()
        project.addInclude("new", addToMain = false)
        store.log.clear()

        assertEquals(DeleteResult.Deleted("new.ink"), project.delete("new.ink"))
        assertTrue(store.log.none { it.startsWith("delete") })

        store.remove("a.ink")
        assertEquals(DeleteResult.Deleted("a.ink"), project.delete("a.ink"))
        assertEquals(listOf("main.ink"), project.paths)
    }

    @Test
    fun `a delete that storage refuses keeps the file`() = projectTest {
        store.put("main.ink", "Hello\n")
        store.put("a.ink", "A")
        val project = open()
        store.failure = { operation, _ -> if (operation == "delete") java.io.IOException("read-only") else null }

        assertTrue(project.delete("a.ink") is DeleteResult.Failed)
        assertEquals(listOf("main.ink", "a.ink"), project.paths)
    }
}
