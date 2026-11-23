package com.inkyandroid.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkyandroid.core.engine.InkIssue
import com.inkyandroid.core.engine.IssueKind
import com.inkyandroid.core.project.AddIncludeResult
import com.inkyandroid.core.project.DeleteResult
import com.inkyandroid.core.project.RenameResult
import com.inkyandroid.settings.FilePlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** What a window's session does besides keeping the text safe: autosave, the live session, places, issues, files, closing. */
@RunWith(AndroidJUnit4::class)
class ProjectSessionTest {

    // ------------------------------------------------------------------ autosave

    @Test
    fun `with autosave a file is saved two seconds after the last edit`() = sessionTest {
        onStorage("main.ink", "Hello\n")
        val session = open()
        val editor = editor(session, "main.ink")

        editor.type("Hello, w\n")
        pass(1_500)
        editor.type("Hello, world\n")
        pass(1_999)
        assertEquals("every edit restarts the two seconds", "Hello\n", file("main.ink").readText())

        pass(1)
        assertEquals("Hello, world\n", file("main.ink").readText())
        assertFalse(session.project.state.value.hasUnsavedChanges)
        assertEquals(listOf("main.ink"), store.writes)
    }

    @Test
    fun `without autosave nothing is written until the user saves`() = sessionTest {
        settings.updateAutosave(false)
        onStorage("main.ink", "Hello\n")
        val session = open()
        editor(session, "main.ink").type("Hello, world\n")

        pass(60_000)
        assertEquals("Hello\n", file("main.ink").readText())
        assertTrue(session.project.state.value.hasUnsavedChanges)

        session.flush()
        assertEquals("not when the app goes to the background either", "Hello\n", file("main.ink").readText())

        assertTrue(session.saveAll().isComplete)
        assertEquals("Hello, world\n", file("main.ink").readText())
    }

    @Test
    fun `switching autosave on takes effect with the next edit`() = sessionTest {
        settings.updateAutosave(false)
        onStorage("main.ink", "Hello\n")
        val session = open()
        val editor = editor(session, "main.ink")
        editor.type("one\n")
        pass(5_000)

        settings.updateAutosave(true)
        editor.type("two\n")
        pass(ProjectSession.AUTOSAVE_DELAY_MILLIS)

        assertEquals("two\n", file("main.ink").readText())
    }

    // ------------------------------------------------------------------ the live session

    @Test
    fun `the live session compiles at once when the project is open and hears of every change`() = sessionTest {
        onStorage("main.ink", "Hello\n")
        val session = open()
        assertEquals(1, live.reloads)
        assertEquals(0, live.edits)

        editor(session, "main.ink").type("Hello, world\n")
        pass(0)

        assertEquals(1, live.edits)
        assertEquals("Hello, world\n", live.lastSources!!().texts["main.ink"])
    }

    @Test
    fun `an INCLUDE line typed into the main file makes the file included and reaches the compiler`() = sessionTest {
        onStorage("main.ink", "Hello\n")
        onStorage("chapter.ink", "=== chapter ===\nText\n")
        val session = open()
        assertTrue(session.project.state.value.file("chapter.ink")!!.isSpare)

        editor(session, "main.ink").type("INCLUDE chapter.ink\nHello\n-> chapter\n")
        pass(200)

        val chapter = session.project.state.value.file("chapter.ink")!!
        assertFalse(chapter.isSpare)
        assertEquals("the real symbol parser ran", listOf("chapter"), chapter.symbols!!.root.map { it.name })
        assertEquals(setOf("main.ink", "chapter.ink"), live.lastSources!!().texts.keys)
        assertTrue(live.edits >= 1)
    }

    @Test
    fun `an INCLUDE inside a comment includes nothing`() = sessionTest {
        onStorage("main.ink", "// INCLUDE chapter.ink\n/* INCLUDE chapter.ink */\nHello\n")
        onStorage("chapter.ink", "Text\n")

        val session = open()

        assertTrue(session.project.state.value.file("chapter.ink")!!.isSpare)
    }

    @Test
    fun `issues arrive in upstream's order and are found by file`() = sessionTest {
        onStorage("main.ink", "INCLUDE a.ink\nHello\n")
        onStorage("a.ink", "A\n")
        val session = open()
        val todo = InkIssue(IssueKind.Todo, "main.ink", 2, "finish this")
        val warning = InkIssue(IssueKind.Warning, "a.ink", 9, "odd")
        val lateError = InkIssue(IssueKind.Error, "a.ink", 7, "broken")
        val earlyError = InkIssue(IssueKind.Error, "main.ink", 3, "also broken")

        live.issues.value = listOf(todo, warning, lateError, earlyError)
        pass(0)

        val state = session.state.value
        assertEquals(listOf(earlyError, lateError, warning, todo), state.issues)
        assertEquals(listOf(lateError, warning), state.issuesOf("a.ink"))
        assertEquals(mapOf("main.ink" to 2, "a.ink" to 2), state.issueCounts)
    }

    @Test
    fun `next issue walks the issues, wraps around, and starts over after a recompile`() = sessionTest {
        onStorage("main.ink", "INCLUDE a.ink\nHello\n")
        onStorage("a.ink", "A\n")
        val session = open()
        assertFalse("no issues, nowhere to go", session.nextIssue())
        live.issues.value = listOf(InkIssue(IssueKind.Error, "a.ink", 7, "one"), InkIssue(IssueKind.Warning, "main.ink", 2, "two"))
        pass(0)

        assertTrue(session.nextIssue())
        assertEquals(ShowRequestSeen("a.ink", TextPosition(6)), session.state.value.showRequest.seen())
        session.nextIssue()
        assertEquals(ShowRequestSeen("main.ink", TextPosition(1)), session.state.value.showRequest.seen())
        session.nextIssue()
        assertEquals("wrapped", ShowRequestSeen("a.ink", TextPosition(6)), session.state.value.showRequest.seen())

        live.issues.value = listOf(InkIssue(IssueKind.Error, "main.ink", 1, "new"))
        pass(0)
        session.nextIssue()
        assertEquals(ShowRequestSeen("main.ink", TextPosition(0)), session.state.value.showRequest.seen())
    }

    @Test
    fun `an issue without a file of the project goes nowhere`() = sessionTest {
        onStorage("main.ink", "Hello\n")
        val session = open()

        assertFalse(session.showIssue(InkIssue(IssueKind.Error, null, null, "the compiler crashed")))
        assertFalse(session.showIssue(InkIssue(IssueKind.Error, "ghost.ink", 3, "x")))
        assertNull(session.state.value.showRequest)
        assertEquals("main.ink", session.state.value.activePath)
    }

    @Test
    fun `watches go to the live session and into the state`() = sessionTest {
        onStorage("main.ink", "VAR x = 1\n")
        val session = open()

        session.setWatches(listOf("x", "x + 1"))

        assertEquals(listOf("x", "x + 1"), live.watched)
        assertEquals(listOf("x", "x + 1"), session.state.value.watches)
    }

    // ------------------------------------------------------------------ documents and places

    @Test
    fun `each opened file keeps its own editor for the life of the session`() = sessionTest {
        onStorage("main.ink", "INCLUDE a.ink\n")
        onStorage("a.ink", "A\n")
        val session = open()

        val main = session.document("main.ink")
        val a = session.document("a.ink")
        session.show("a.ink")
        session.show("main.ink")

        assertSame(main, session.document("main.ink"))
        assertSame(a, session.document("a.ink"))
        assertNull(session.document("ghost.ink"))
        assertEquals(2, editors.size)
        assertSame(main, session.activeDocument)
    }

    @Test
    fun `show makes the file active and asks the screen to bring the place into view until it says it has`() = sessionTest {
        onStorage("main.ink", "INCLUDE a.ink\n")
        onStorage("a.ink", "one\ntwo\nthree\n")
        val session = open()
        assertEquals("main.ink", session.state.value.activePath)
        assertNull(session.state.value.showRequest)

        assertTrue(session.show("a.ink", TextPosition(2, 1)))

        val request = session.state.value.showRequest!!
        assertEquals("a.ink", session.state.value.activePath)
        assertEquals(ShowRequestSeen("a.ink", TextPosition(2, 1)), request.seen())
        assertEquals(TextPosition(2, 1), editor(session, "a.ink").cursor)

        // a newer request is not cleared by the acknowledgement of an older one
        session.show("main.ink")
        session.showHandled(request)
        assertEquals(ShowRequestSeen("main.ink", null), session.state.value.showRequest.seen())
        session.showHandled(session.state.value.showRequest!!)
        assertNull(session.state.value.showRequest)
    }

    @Test
    fun `showing a file the project does not have changes nothing`() = sessionTest {
        onStorage("main.ink", "Hello\n")
        val session = open()

        assertFalse(session.show("ghost.ink", TextPosition(3)))
        assertEquals("main.ink", session.state.value.activePath)
        assertNull(session.state.value.showRequest)
    }

    @Test
    fun `back and forward retrace file switches, jumps and clicks`() = sessionTest {
        onStorage("main.ink", "INCLUDE a.ink\n" + "line\n".repeat(30))
        onStorage("a.ink", "line\n".repeat(30))
        val session = open()
        assertFalse(session.state.value.canGoBack)

        session.show("a.ink", TextPosition(10, 3))
        session.navigated(TextPosition(20, 5))
        session.navigated(TextPosition(20, 9))
        assertTrue(session.state.value.canGoBack)
        assertFalse(session.state.value.canGoForward)

        session.back()
        assertEquals("a click on the same row was no new step; the column is not restored", ShowRequestSeen("a.ink", TextPosition(10)), session.state.value.showRequest.seen())
        session.back()
        assertEquals(ShowRequestSeen("main.ink", TextPosition(0)), session.state.value.showRequest.seen())
        assertEquals("main.ink", session.state.value.activePath)
        assertFalse(session.state.value.canGoBack)
        assertTrue(session.state.value.canGoForward)

        session.back()
        assertEquals("at the oldest step back does nothing", "main.ink", session.state.value.activePath)

        session.forward()
        session.forward()
        assertEquals(ShowRequestSeen("a.ink", TextPosition(20)), session.state.value.showRequest.seen())
        assertFalse(session.state.value.canGoForward)
    }

    @Test
    fun `the window comes back to the file and the places it was at`() = sessionTest {
        onStorage("main.ink", "INCLUDE a.ink\n" + "line\n".repeat(30))
        onStorage("a.ink", "line\n".repeat(30))
        val first = open()
        editor(first, "main.ink").apply { cursor = TextPosition(4, 2); firstVisibleRow = 3 }
        first.show("a.ink")
        editor(first, "a.ink").apply { cursor = TextPosition(12, 1); firstVisibleRow = 9 }
        first.flush()
        kill()

        val restarted = settingsAfterRestart()
        assertEquals("the-project", restarted.lastSession?.project)
        assertEquals(FilePlace(12, 1, 9), restarted.lastSession?.places?.get("a.ink"))
        val second = open(settings = restarted)

        assertEquals("a.ink", second.state.value.activePath)
        assertEquals(TextPosition(12, 1), editor(second, "a.ink").cursor)
        assertEquals(9, editor(second, "a.ink").firstVisibleRow)
        assertEquals(TextPosition(4, 2), editor(second, "main.ink").cursor)
        assertEquals(3, editor(second, "main.ink").firstVisibleRow)
    }

    @Test
    fun `the places of another project are not applied`() = sessionTest {
        onStorage("main.ink", "line\n".repeat(30))
        val first = open(reference = "project-one")
        editor(first, "main.ink").cursor = TextPosition(9, 0)
        first.flush()
        kill()

        val second = open(reference = "project-two", settings = settingsAfterRestart())

        assertEquals(TextPosition(0, 0), editor(second, "main.ink").cursor)
    }

    // ------------------------------------------------------------------ files

    @Test
    fun `add include shows the new file and adds a history step`() = sessionTest {
        onStorage("main.ink", "Hello\n")
        val session = open()

        val result = session.addInclude("chapter", addToMain = true)

        assertEquals(AddIncludeResult.Added("chapter.ink"), result)
        assertEquals("chapter.ink", session.state.value.activePath)
        assertEquals(ShowRequestSeen("chapter.ink", null), session.state.value.showRequest.seen())
        assertTrue(session.state.value.canGoBack)
        assertEquals("INCLUDE chapter.ink\nHello\n", session.project.text("main.ink").toString())

        assertEquals(AddIncludeResult.AlreadyExists("chapter.ink"), session.addInclude("chapter.ink", addToMain = true))
    }

    @Test
    fun `in a single file opened without its folder add include is refused until the folder is granted`() = sessionTest {
        onStorage("main.ink", "Hello\n")
        val session = open(singleFile = true)

        val result = session.addInclude("chapter", addToMain = true)

        assertTrue(session.isSingleFile)
        assertEquals(AddIncludeResult.InvalidName("chapter", needsFolder = true), result)
        assertEquals(listOf("main.ink"), session.project.state.value.files.map { it.path })
        assertEquals("Hello\n", session.project.text("main.ink").toString())
        assertFalse(session.project.state.value.hasUnsavedChanges)
    }

    @Test
    fun `with autosave a new include is created on storage two seconds later`() = sessionTest {
        onStorage("main.ink", "Hello\n")
        val session = open()

        session.addInclude("chapters/one", addToMain = true)
        pass(ProjectSession.AUTOSAVE_DELAY_MILLIS)

        assertEquals("", file("chapters/one.ink").readText())
        assertEquals("INCLUDE chapters/one.ink\nHello\n", file("main.ink").readText())
        assertFalse(session.project.state.value.hasUnsavedChanges)
    }

    @Test
    fun `the editor, the place and the history of a renamed file follow it`() = sessionTest {
        settings.updateAutosave(false)
        onStorage("main.ink", "INCLUDE old.ink\n")
        onStorage("old.ink", "text\n")
        val session = open()
        session.show("old.ink")
        val document = session.activeDocument
        val buffer = editor(session, "old.ink")
        buffer.type("unsaved text\n")
        pass(ProjectSession.DRAFT_DELAY_MILLIS)

        val result = session.rename("old.ink", "new")

        assertEquals(RenameResult.Renamed("old.ink", "new.ink", listOf("main.ink")), result)
        assertEquals("new.ink", session.state.value.activePath)
        assertSame("the same document, undo history and all", document, session.document("new.ink"))
        assertEquals("new.ink", document.path)
        assertNull(session.document("old.ink"))
        assertEquals("INCLUDE new.ink\n", session.project.text("main.ink").toString())
        assertEquals("the drafts follow at once", mapOf("new.ink" to "unsaved text\n", "main.ink" to "INCLUDE new.ink\n"), drafts())

        // typing goes on under the new name
        buffer.type("more unsaved text\n")
        assertEquals("more unsaved text\n", session.project.text("new.ink").toString())
        session.saveAll()
        assertEquals("more unsaved text\n", file("new.ink").readText())
        assertFalse(file("old.ink").exists())
    }

    @Test
    fun `deleting the file that is shown shows the main file, and its steps leave the history`() = sessionTest {
        onStorage("main.ink", "INCLUDE a.ink\n")
        onStorage("a.ink", "A\n")
        val session = open()
        session.show("a.ink")

        assertEquals(DeleteResult.Deleted("a.ink"), session.delete("a.ink"))

        assertEquals("main.ink", session.state.value.activePath)
        assertNull(session.document("a.ink"))
        assertFalse(file("a.ink").exists())
        assertFalse("back never leads to a file that is gone", session.state.value.canGoBack)
        assertEquals(DeleteResult.IsMainFile, session.delete("main.ink"))
    }

    @Test
    fun `a file removed outside leaves the window the same way`() = sessionTest {
        onStorage("main.ink", "INCLUDE a.ink\n")
        onStorage("a.ink", "A\n")
        val session = open()
        session.show("a.ink")

        file("a.ink").delete()
        session.syncWithStorage()
        pass(0)

        assertEquals("main.ink", session.state.value.activePath)
        assertNull(session.document("a.ink"))
    }

    @Test
    fun `revert shows storage's version again and drops the draft`() = sessionTest {
        settings.updateAutosave(false)
        onStorage("main.ink", "Hello\n")
        val session = open()
        val editor = editor(session, "main.ink")
        editor.type("Scribbles\n")
        pass(ProjectSession.DRAFT_DELAY_MILLIS)

        assertTrue(session.revert("main.ink"))

        assertEquals("Hello\n", editor.text.toString())
        assertFalse(session.project.state.value.hasUnsavedChanges)
        assertEquals(emptyMap<String, String>(), drafts())
    }

    // ------------------------------------------------------------------ the project's settings file

    @Test
    fun `the project settings file is read when the project opens`() = sessionTest {
        onStorage("story.ink", "Hello\n")
        onStorage("story.settings.json", """{ "instructionPrefix": ">>>", "customInkSnippets": [ { "name": "Camera", "ink": ">>> CAMERA: " } ] }""")

        val session = open(mainPath = "story.ink")

        assertEquals(">>>", session.state.value.projectSettings.instructionPrefix)
        assertEquals(1, session.state.value.projectSettings.customInkSnippets.size)
        assertEquals(">>>", session.state.value.tokenizer.instructionPrefix)
        assertEquals(">>>", live.prefix)
    }

    @Test
    fun `a project without a settings file has no settings`() = sessionTest {
        onStorage("main.ink", "Hello\n")
        val session = open()
        assertNull(session.state.value.projectSettings.instructionPrefix)
        assertNull(session.state.value.tokenizer.instructionPrefix)
    }

    @Test
    fun `a settings file that changes on storage is read again and gives a new tokenizer`() = sessionTest {
        onStorage("main.ink", "Hello\n")
        val session = open()
        val tokenizer = session.state.value.tokenizer

        onStorage("main.settings.json", """{ "instructionPrefix": "@" }""")
        session.syncWithStorage()

        assertEquals("@", session.state.value.projectSettings.instructionPrefix)
        assertEquals("@", session.state.value.tokenizer.instructionPrefix)
        assertTrue(session.state.value.tokenizer !== tokenizer)
        assertEquals("@", live.prefix)

        file("main.settings.json").delete()
        session.syncWithStorage()
        assertNull(session.state.value.projectSettings.instructionPrefix)
    }

    // ------------------------------------------------------------------ closing

    @Test
    fun `a project without unsaved changes closes at once`() = sessionTest {
        onStorage("main.ink", "Hello\n")
        val session = open()

        assertTrue(session.requestClose())

        assertTrue(session.state.value.closed)
        assertTrue(live.closed)
        assertNull("closed on purpose: the next start does not reopen it", settings.lastSession)
    }

    @Test
    fun `with autosave closing saves and closes without a question`() = sessionTest {
        onStorage("main.ink", "Hello\n")
        val session = open()
        editor(session, "main.ink").type("Hello, world\n")

        assertTrue(session.requestClose())

        assertEquals("Hello, world\n", file("main.ink").readText())
        assertFalse(session.state.value.closeQuestion)
        assertEquals(emptyMap<String, String>(), drafts())
    }

    @Test
    fun `without autosave closing a project with unsaved changes asks upstream's question`() = sessionTest {
        settings.updateAutosave(false)
        onStorage("main.ink", "Hello\n")
        val session = open()
        editor(session, "main.ink").type("Hello, world\n")

        assertFalse(session.requestClose())

        assertTrue(session.state.value.closeQuestion)
        assertFalse(session.state.value.closed)
        assertEquals("Hello\n", file("main.ink").readText())
    }

    @Test
    fun `cancel keeps the project open and the changes unsaved`() = sessionTest {
        settings.updateAutosave(false)
        onStorage("main.ink", "Hello\n")
        val session = open()
        val editor = editor(session, "main.ink")
        editor.type("Hello, world\n")
        session.requestClose()

        assertFalse(session.answerClose(CloseAnswer.Cancel))

        assertFalse(session.state.value.closeQuestion)
        assertFalse(session.state.value.closed)
        assertTrue(session.project.state.value.hasUnsavedChanges)
        // and the session still works
        editor.type("Hello, world, again\n")
        pass(ProjectSession.DRAFT_DELAY_MILLIS)
        assertEquals(mapOf("main.ink" to "Hello, world, again\n"), drafts())
    }

    @Test
    fun `save saves everything and closes`() = sessionTest {
        settings.updateAutosave(false)
        onStorage("main.ink", "INCLUDE a.ink\n")
        onStorage("a.ink", "A\n")
        val session = open()
        editor(session, "a.ink").type("A, rewritten\n")
        editor(session, "main.ink").type("INCLUDE a.ink\nMore\n")
        pass(ProjectSession.DRAFT_DELAY_MILLIS)
        session.requestClose()

        assertTrue(session.answerClose(CloseAnswer.Save))

        assertEquals("A, rewritten\n", file("a.ink").readText())
        assertEquals("INCLUDE a.ink\nMore\n", file("main.ink").readText())
        assertEquals("upstream saves the main file first", listOf("main.ink", "a.ink"), store.writes)
        assertTrue(session.state.value.closed)
        assertEquals(emptyMap<String, String>(), drafts())
    }

    @Test
    fun `do not save closes, leaves storage alone and forgets the drafts`() = sessionTest {
        settings.updateAutosave(false)
        onStorage("main.ink", "Hello\n")
        val session = open()
        editor(session, "main.ink").type("Thrown away on purpose\n")
        pass(ProjectSession.DRAFT_DELAY_MILLIS)
        assertEquals(1, drafts().size)
        session.requestClose()

        assertTrue(session.answerClose(CloseAnswer.DontSave))

        assertEquals("Hello\n", file("main.ink").readText())
        assertEquals(emptyMap<String, String>(), drafts())
        val reopened = open()
        assertEquals("Hello\n", reopened.project.text("main.ink").toString())
        assertNull(reopened.state.value.restoredDrafts)
    }

    @Test
    fun `when the save fails on closing the project stays open, with the question and the reason`() = sessionTest {
        onStorage("main.ink", "Hello\n")
        val session = open()
        editor(session, "main.ink").type("Hello, world\n")
        store.writeFailure = IOException("Permission lost")

        assertFalse("autosave could not save: ask", session.requestClose())
        assertTrue(session.state.value.closeQuestion)
        assertEquals(mapOf("main.ink" to "Permission lost"), session.state.value.saveProblem?.failed)

        assertFalse(session.answerClose(CloseAnswer.Save))
        assertFalse(session.state.value.closed)
        assertTrue(session.project.state.value.hasUnsavedChanges)

        store.writeFailure = null
        session.requestClose()
        assertTrue(session.state.value.closed)
        assertEquals("Hello, world\n", file("main.ink").readText())
    }

    @Test
    fun `an abandoned session saves once more with autosave and keeps its drafts without`() = sessionTest {
        onStorage("main.ink", "Hello\n")
        val saving = open()
        editor(saving, "main.ink").type("Saved on the way out\n")
        saving.abandon()
        pass(0)
        assertEquals("Saved on the way out\n", file("main.ink").readText())
        assertTrue(saving.state.value.closed)

        settings.updateAutosave(false)
        val keeping = open()
        editor(keeping, "main.ink").type("Kept as a draft\n")
        keeping.abandon()
        pass(0)
        assertEquals("Saved on the way out\n", file("main.ink").readText())
        assertEquals(mapOf("main.ink" to "Kept as a draft\n"), drafts())
        assertEquals("the window was destroyed, not closed: the next start reopens the project", "the-project", settings.lastSession?.project)
    }

    @Test
    fun `the notice about restored drafts can be dismissed`() = sessionTest {
        settings.updateAutosave(false)
        onStorage("main.ink", "Hello\n")
        val first = open()
        editor(first, "main.ink").type("Unsaved\n")
        first.flush()
        kill()

        val second = open(settings = settingsAfterRestart())
        assertNotNull(second.state.value.restoredDrafts)
        second.dismissRestoredNotice()
        assertNull(second.state.value.restoredDrafts)
    }

    @Test
    fun `opening a project whose main file is gone fails and leaves nothing running`() = sessionTest {
        var failure: IOException? = null
        try {
            open()
        } catch (expected: IOException) {
            failure = expected
        }
        assertNotNull(failure)
        assertEquals(0, lives.size)
    }

    private data class ShowRequestSeen(val path: String, val position: TextPosition?)

    private fun ShowRequest?.seen(): ShowRequestSeen? = this?.let { ShowRequestSeen(it.path, it.position) }
}
