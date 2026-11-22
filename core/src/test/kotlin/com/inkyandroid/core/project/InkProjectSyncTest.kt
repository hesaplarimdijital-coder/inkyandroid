package com.inkyandroid.core.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Changes on storage: upstream's file watcher rules (`inkProject.js: startFileWatching`), applied when the app looks. */
class InkProjectSyncTest {

    @Test
    fun `nothing changed, nothing happens and nothing is read`() = projectTest {
        store.put("main.ink", "INCLUDE a.ink\n")
        store.put("a.ink", "A")
        val project = open()
        val before = project.state.value
        store.log.clear()

        val report = project.syncWithStorage()

        assertTrue(report.isEmpty)
        assertEquals(listOf("list"), store.log)
        assertEquals(before, project.state.value)
    }

    @Test
    fun `a file changed outside without unsaved changes is reloaded silently`() = projectTest {
        store.put("main.ink", "Hello\n")
        store.put("a.ink", "A")
        val project = open()
        val editor = project.edit("main.ink")
        editor.echoesLoads = true
        val revision = project.contentRevision.value

        store.put("main.ink", "INCLUDE a.ink\nHello from outside\n")
        val report = project.syncWithStorage()

        assertEquals(listOf("main.ink"), report.reloaded)
        assertEquals(emptyList<String>(), report.conflicts)
        assertEquals("INCLUDE a.ink\nHello from outside\n", editor.text)
        assertEquals("through the editor's load, which resets undo", 1, editor.loads.size)
        assertFalse("a load is not an edit", project.file("main.ink").hasUnsavedChanges)
        assertFalse("symbols are parsed at once after a load", project.file("a.ink").isSpare)
        assertTrue("the compiler has to see it", project.contentRevision.value > revision)
    }

    @Test
    fun `a file changed outside while it has unsaved changes is left alone and reported as a conflict`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        val editor = project.edit("main.ink")
        editor.type("Mine\n")

        store.put("main.ink", "Theirs\n")
        val report = project.syncWithStorage()

        assertEquals(listOf("main.ink"), report.conflicts)
        assertEquals(emptyList<String>(), report.reloaded)
        assertEquals("Mine\n", editor.text)
        assertEquals(emptyList<String>(), editor.loads)
        assertEquals("Theirs\n", store.textOf("main.ink"))
        assertTrue(project.file("main.ink").hasConflict)
        assertTrue(project.file("main.ink").hasUnsavedChanges)
    }

    @Test
    fun `a conflict is reported once and stays until it is resolved`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        project.edit("main.ink").type("Mine\n")
        store.put("main.ink", "Theirs\n")
        project.syncWithStorage()

        val again = project.syncWithStorage()

        assertEquals(emptyList<String>(), again.conflicts)
        assertTrue(project.file("main.ink").hasConflict)
        assertEquals(SaveResult.Conflict, project.save("main.ink"))
        assertEquals("Theirs\n", store.textOf("main.ink"))
    }

    @Test
    fun `the app's own save is not an outside change`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        val editor = project.edit("main.ink")
        editor.type("Saved by us\n")
        project.saveAll()

        val report = project.syncWithStorage()

        assertTrue(report.isEmpty)
        assertEquals(emptyList<String>(), editor.loads)
    }

    @Test
    fun `the app's own save is not an outside change even when storage reports another time for it later`() = projectTest {
        // a document provider may round or rewrite the time stamp after the write returned
        store.put("main.ink", "Hello\n")
        val project = open()
        val editor = project.edit("main.ink")
        editor.type("Saved by us\n")
        project.saveAll()
        store.touch("main.ink")
        editor.type("Saved by us\nand typed on\n")

        val report = project.syncWithStorage()

        assertTrue(report.isEmpty)
        assertFalse(project.file("main.ink").hasConflict)
        assertEquals("Saved by us\nand typed on\n", editor.text)
        assertEquals(SaveResult.Saved, project.save("main.ink"))
    }

    @Test
    fun `storage that tells no modification times is compared by content`() = projectTest {
        store.reportsTimes = false
        store.put("main.ink", "Hello\n")
        val project = open()

        // same length, other text: size and time say nothing
        store.put("main.ink", "World\n")
        val report = project.syncWithStorage()

        assertEquals(listOf("main.ink"), report.reloaded)
        assertEquals("World\n", project.text("main.ink").toString())
    }

    @Test
    fun `a new ink file found on storage joins the project as an unused file`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()

        store.put("chapters/new.ink", "NEW")
        store.put("notes.txt", "not ink")
        val report = project.syncWithStorage()

        assertEquals(listOf("chapters/new.ink"), report.added)
        assertEquals(listOf("main.ink", "chapters/new.ink"), project.paths)
        assertTrue(project.file("chapters/new.ink").isSpare)
        assertFalse(project.file("chapters/new.ink").hasUnsavedChanges)
    }

    @Test
    fun `a new file that an INCLUDE was already waiting for is included`() = projectTest {
        store.put("main.ink", "INCLUDE later.ink\n")
        val project = open()
        assertEquals(listOf("later.ink"), project.state.value.missingIncludes)

        store.put("later.ink", "LATER")
        project.syncWithStorage()

        assertFalse(project.file("later.ink").isSpare)
        assertEquals(emptyList<String>(), project.state.value.missingIncludes)
    }

    @Test
    fun `a file removed outside without unsaved changes leaves the project`() = projectTest {
        store.put("main.ink", "INCLUDE a.ink\n")
        store.put("a.ink", "A")
        val project = open()
        project.edit("a.ink")

        store.remove("a.ink")
        val report = project.syncWithStorage()

        assertEquals(listOf("a.ink"), report.removed)
        assertEquals(listOf("main.ink"), project.paths)
        assertEquals(listOf("a.ink"), project.state.value.missingIncludes)
    }

    @Test
    fun `a file removed outside while it has unsaved changes stays, and saving creates it again`() = projectTest {
        store.put("main.ink", "INCLUDE a.ink\n")
        store.put("a.ink", "A")
        val project = open()
        project.edit("a.ink").type("A, with work in it")

        store.remove("a.ink")
        val report = project.syncWithStorage()

        assertEquals(listOf("a.ink"), report.keptMissing)
        assertEquals(emptyList<String>(), report.removed)
        assertFalse(project.file("a.ink").isOnStorage)
        assertTrue(project.file("a.ink").hasUnsavedChanges)
        assertEquals("A, with work in it", project.text("a.ink").toString())

        project.saveAll()
        assertEquals("A, with work in it", store.textOf("a.ink"))
        assertTrue(project.file("a.ink").isOnStorage)
    }

    @Test
    fun `the main file stays when it is removed outside`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()

        store.remove("main.ink")
        val report = project.syncWithStorage()

        assertEquals(listOf("main.ink"), report.keptMissing)
        assertEquals("Hello\n", project.text("main.ink").toString())
        assertTrue("it is unsaved now: the next save puts it back", project.file("main.ink").hasUnsavedChanges)
        project.saveAll()
        assertEquals("Hello\n", store.textOf("main.ink"))
    }

    @Test
    fun `renaming a file outside is its removal and the arrival of an unused file`() = projectTest {
        // upstream: unlink + add
        store.put("main.ink", "INCLUDE old.ink\n")
        store.put("old.ink", "TEXT")
        val project = open()

        store.remove("old.ink")
        store.put("new.ink", "TEXT")
        val report = project.syncWithStorage()

        assertEquals(listOf("old.ink"), report.removed)
        assertEquals(listOf("new.ink"), report.added)
        assertTrue(project.file("new.ink").isSpare)
    }

    @Test
    fun `a file that was not on storage and appears there untouched by the user takes storage's text`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        project.addInclude("chapter", addToMain = false)

        store.put("chapter.ink", "written elsewhere")
        val report = project.syncWithStorage()

        assertEquals(emptyList<String>(), report.conflicts)
        assertEquals("written elsewhere", project.text("chapter.ink").toString())
        assertFalse(project.file("chapter.ink").hasUnsavedChanges)
    }

    @Test
    fun `the settings file being added, changed or removed is reported`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        assertFalse(project.syncWithStorage().settingsChanged)

        store.put("main.settings.json", "{}")
        assertTrue(project.syncWithStorage().settingsChanged)
        assertFalse(project.syncWithStorage().settingsChanged)

        store.put("main.settings.json", """{"instructionPrefix": ">>>"}""")
        assertTrue(project.syncWithStorage().settingsChanged)

        store.remove("main.settings.json")
        assertTrue(project.syncWithStorage().settingsChanged)
    }

    @Test
    fun `when storage cannot be listed nothing changes and the failure is thrown`() = projectTest {
        store.put("main.ink", "INCLUDE a.ink\n")
        store.put("a.ink", "A")
        val project = open()
        val before = project.state.value
        store.failure = { operation, _ -> if (operation == "list") IOException("permission lost") else null }

        var failure: IOException? = null
        try {
            project.syncWithStorage()
        } catch (expected: IOException) {
            failure = expected
        }

        assertEquals("permission lost", failure?.message)
        assertEquals("no file is taken for deleted", before, project.state.value)
    }

    @Test
    fun `a changed file that cannot be read is reported and keeps its text`() = projectTest {
        store.put("main.ink", "Hello\n")
        val project = open()
        store.put("main.ink", "Changed\n")
        store.failure = { operation, _ -> if (operation == "read") IOException("provider failed") else null }

        val report = project.syncWithStorage()

        assertEquals(mapOf("main.ink" to "provider failed"), report.errors)
        assertEquals("Hello\n", project.text("main.ink").toString())

        store.failure = { _, _ -> null }
        assertEquals("the next look takes it", listOf("main.ink"), project.syncWithStorage().reloaded)
    }

    @Test
    fun `a file loaded through an INCLUDE that the listing does not show is not taken for deleted`() = projectTest {
        // the listing skips dot-folders; an INCLUDE can still name a file there
        val hiding = object : ProjectStore by store {
            override suspend fun list() = store.list().filter { !it.path.startsWith(".lib/") }
        }
        store.put("main.ink", "INCLUDE .lib/util.ink\n")
        store.put(".lib/util.ink", "UTIL")
        val project = InkProject.open(scope.backgroundScope, hiding, "main.ink", LineScanParser, parseContext = kotlin.coroutines.EmptyCoroutineContext)
        assertEquals(listOf("main.ink", ".lib/util.ink"), project.paths)

        val report = project.syncWithStorage()

        assertTrue(report.isEmpty)
        assertEquals(listOf("main.ink", ".lib/util.ink"), project.paths)
    }
}
