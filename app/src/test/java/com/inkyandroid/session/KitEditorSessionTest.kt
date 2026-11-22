package com.inkyandroid.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The project session with the kit's real editor in it: the same rules hold for what a person types. */
@RunWith(AndroidJUnit4::class)
class KitEditorSessionTest {

    private val story = "Once upon a time\n"

    @Test
    fun `what is typed into the kit's editor is unsaved, journaled, and back in a kit editor after the process died`() = sessionTest {
        settings.updateAutosave(false)
        onStorage("main.ink", story)
        val first = open(kit = true)
        val editor = kitEditor(first, "main.ink")

        editor.state.select(editor.state.length)
        editor.state.insert("Bir varmış, bir yokmuş.\n")
        applied()
        assertTrue(first.project.state.value.file("main.ink")!!.hasUnsavedChanges)
        pass(ProjectSession.DRAFT_DELAY_MILLIS)
        assertEquals(mapOf("main.ink" to story + "Bir varmış, bir yokmuş.\n"), drafts())

        kill()
        val second = open(settings = settingsAfterRestart(), kit = true)

        assertEquals(story + "Bir varmış, bir yokmuş.\n", kitEditor(second, "main.ink").state.text)
        assertTrue(second.project.state.value.file("main.ink")!!.hasUnsavedChanges)
        assertEquals(story, file("main.ink").readText())
    }

    @Test
    fun `the screens find the kit's state of each document`() = sessionTest {
        onStorage("main.ink", story)
        val session = open(kit = true)

        val state = session.document("main.ink")?.editorState

        assertNotNull(state)
        assertSame(kitEditor(session, "main.ink").state, state)
        assertEquals(story, state!!.text)
    }

    @Test
    fun `a file with CRLF line breaks is saved with CRLF line breaks after typing`() = sessionTest {
        onStorage("main.ink", "Once upon a time\r\nThere were two choices.\r\n")
        val session = open(kit = true)
        val editor = kitEditor(session, "main.ink")

        editor.state.select(editor.state.length)
        editor.state.insert("* [One]\n* [The other]\n")
        applied()
        pass(ProjectSession.AUTOSAVE_DELAY_MILLIS)

        assertArrayEquals(
            "Once upon a time\r\nThere were two choices.\r\n* [One]\r\n* [The other]\r\n".toByteArray(),
            file("main.ink").readBytes(),
        )
        assertFalse(session.project.state.value.hasUnsavedChanges)
    }

    @Test
    fun `an untouched CRLF file is not unsaved and is never written`() = sessionTest {
        onStorage("main.ink", "Once upon a time\r\n")
        val session = open(kit = true)
        kitEditor(session, "main.ink")
        applied()

        pass(60_000)
        session.flush()

        assertFalse(session.project.state.value.hasUnsavedChanges)
        assertEquals(emptyList<String>(), store.writes)
    }

    @Test
    fun `a reload from storage reaches the kit's editor as a load, not as an edit`() = sessionTest {
        onStorage("main.ink", story)
        val session = open(kit = true)
        val editor = kitEditor(session, "main.ink")

        onStorage("main.ink", "Rewritten on the computer\n")
        session.syncWithStorage()
        applied()

        assertEquals("Rewritten on the computer\n", editor.state.text)
        assertFalse("upstream starts a new undo history", editor.state.canUndo)
        assertFalse(session.project.state.value.hasUnsavedChanges)
        pass(ProjectSession.AUTOSAVE_DELAY_MILLIS)
        assertEquals(emptyList<String>(), store.writes)
    }

    @Test
    fun `an INCLUDE line the session adds to the main file is an edit the writer can undo`() = sessionTest {
        settings.updateAutosave(false)
        onStorage("main.ink", story)
        val session = open(kit = true)
        val main = kitEditor(session, "main.ink")

        session.addInclude("chapter", addToMain = true)
        applied()

        assertEquals("INCLUDE chapter.ink\n$story", main.state.text)
        assertTrue(main.state.canUndo)
        assertTrue(main.state.undo())
        applied()
        assertEquals(story, session.project.text("main.ink").toString())
    }

    @Test
    fun `typing that the editor has not reported yet is never replaced by a reload`() = sessionTest {
        onStorage("main.ink", story)
        val session = open(kit = true)
        val editor = kitEditor(session, "main.ink")

        // a keystroke, and in the same frame the window gains focus while the file changed outside
        editor.state.select(editor.state.length)
        editor.state.insert("The last word")
        onStorage("main.ink", "Rewritten on the computer\n")
        session.syncWithStorage()
        applied()

        assertEquals(story + "The last word", editor.state.text)
        assertTrue(session.project.state.value.file("main.ink")!!.hasConflict)
    }
}
