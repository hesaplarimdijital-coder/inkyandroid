package com.inkyandroid.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkyandroid.core.project.ProjectText
import com.inkyandroid.core.project.SaveResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * The first requirement of the app: text is never lost. Each test types into a session, lets
 * something go wrong (the process dies, a save fails, the file changes underneath), and checks that
 * the words are still there.
 */
@RunWith(AndroidJUnit4::class)
class NoTextLostTest {

    private val story = "Once upon a time\n"
    private val written = "Once upon a time\nBir varmış, bir yokmuş: three hours of writing.\n"

    // ------------------------------------------------------------------ the process dies

    @Test
    fun `typed text is in the drafts a second after the last keystroke and comes back after the process died`() = sessionTest {
        settings.updateAutosave(false)
        onStorage("main.ink", story)
        val first = open()
        editor(first, "main.ink").type(written)
        assertTrue("unsaved at once", first.project.state.value.hasUnsavedChanges)

        pass(999)
        assertEquals("not yet", emptyMap<String, String>(), drafts())
        pass(1)
        assertEquals(mapOf("main.ink" to written), drafts())

        kill()
        assertEquals("nothing was saved", story, file("main.ink").readText())

        val second = open(settings = settingsAfterRestart())

        assertEquals(written, second.project.text("main.ink").toString())
        assertEquals(written, editor(second, "main.ink").text.toString())
        assertTrue(second.project.state.value.file("main.ink")!!.hasUnsavedChanges)
        assertEquals(listOf("main.ink"), second.state.value.restoredDrafts?.restored)
        assertEquals("storage is untouched until the user (or autosave) saves", story, file("main.ink").readText())

        assertTrue(second.saveAll().isComplete)
        assertEquals(written, file("main.ink").readText())
        assertEquals("the draft has done its work", emptyMap<String, String>(), drafts())
    }

    @Test
    fun `with autosave a process killed between the draft and the save loses nothing, and the next start saves`() = sessionTest {
        onStorage("main.ink", story)
        val first = open()
        editor(first, "main.ink").type(written)
        pass(1_500)
        assertEquals(story, file("main.ink").readText())

        kill()
        val second = open(settings = settingsAfterRestart())

        assertEquals(written, second.project.text("main.ink").toString())
        pass(ProjectSession.AUTOSAVE_DELAY_MILLIS)
        assertEquals("a restored draft is autosaved like any other unsaved change", written, file("main.ink").readText())
        assertFalse(second.project.state.value.hasUnsavedChanges)
        assertEquals(emptyMap<String, String>(), drafts())
    }

    @Test
    fun `typing that never pauses is still journaled every five seconds`() = sessionTest {
        settings.updateAutosave(false)
        onStorage("main.ink", story)
        val session = open()
        val editor = editor(session, "main.ink")

        // a keystroke every 300 ms for six seconds: the one-second pause never comes
        var text = story
        repeat(20) {
            text += "word "
            editor.type(text)
            pass(300)
        }

        val journaled = drafts()["main.ink"]
        assertNotNull("a draft exists although the typing never paused", journaled)
        assertTrue(journaled!!.startsWith(story + "word word "))

        kill()
        val restored = open(settings = settingsAfterRestart()).project.text("main.ink").toString()
        assertTrue("at most the last five seconds can be missing", restored.length >= story.length + "word ".length * 3)
    }

    @Test
    fun `going to the background writes the drafts at once`() = sessionTest {
        settings.updateAutosave(false)
        onStorage("main.ink", story)
        val first = open()
        editor(first, "main.ink").type(written)

        // the user leaves the app within the same second
        first.flush()
        kill()

        assertEquals(mapOf("main.ink" to written), drafts())
        assertEquals(written, open(settings = settingsAfterRestart()).project.text("main.ink").toString())
    }

    @Test
    fun `with autosave going to the background saves`() = sessionTest {
        onStorage("main.ink", story)
        val session = open()
        editor(session, "main.ink").type(written)

        session.flushInBackground()
        pass(0)

        assertEquals(written, file("main.ink").readText())
        assertFalse(session.project.state.value.hasUnsavedChanges)
        assertEquals(emptyMap<String, String>(), drafts())
    }

    @Test
    fun `a new include that was never saved comes back with its text after the process died`() = sessionTest {
        settings.updateAutosave(false)
        onStorage("main.ink", story)
        val first = open()
        first.addInclude("chapters/one", addToMain = true)
        editor(first, "chapters/one.ink").type("A whole new chapter\n")
        pass(ProjectSession.DRAFT_DELAY_MILLIS)

        kill()
        val second = open(settings = settingsAfterRestart())

        assertEquals("A whole new chapter\n", second.project.text("chapters/one.ink").toString())
        assertEquals("INCLUDE chapters/one.ink\n$story", second.project.text("main.ink").toString())
        assertFalse(second.project.state.value.file("chapters/one.ink")!!.isSpare)
        assertFalse(file("chapters/one.ink").exists())

        second.saveAll()
        assertEquals("A whole new chapter\n", file("chapters/one.ink").readText())
    }

    @Test
    fun `a process killed in the middle of a save leaves the old file or the new one, and the draft`() = sessionTest {
        onStorage("main.ink", story)
        val first = open()
        editor(first, "main.ink").type(written)
        store.writeDelayMillis = 10_000
        pass(ProjectSession.AUTOSAVE_DELAY_MILLIS + 100)

        // the save is under way and will never finish
        kill()
        store.writeDelayMillis = 0

        assertEquals(story, file("main.ink").readText())
        val second = open(settings = settingsAfterRestart())
        assertEquals(written, second.project.text("main.ink").toString())
    }

    // ------------------------------------------------------------------ storage that writes in place (SAF)

    @Test
    fun `an autosave cut short in the middle of a character by the death of the process loses nothing`() = sessionTest {
        onStorage("main.ink", story)
        val first = open()
        editor(first, "main.ink").type(written)
        // the provider writes in place, and the process dies after the first byte of "ı"
        store.tornAfterBytes = "Once upon a time\nBir varm".toByteArray().size + 1
        pass(ProjectSession.AUTOSAVE_DELAY_MILLIS)
        kill()
        store.tornAfterBytes = null
        assertFalse("storage holds no text any more", runCatching { ProjectText.decode(file("main.ink").readBytes()) }.isSuccess)

        val second = open(settings = settingsAfterRestart())

        assertEquals(written, editor(second, "main.ink").text.toString())
        assertEquals(listOf("main.ink"), second.state.value.restoredDrafts?.restored)
        assertTrue("the remains are no outside change", second.project.state.value.conflicts.isEmpty())
        pass(ProjectSession.AUTOSAVE_DELAY_MILLIS)
        assertEquals("autosave writes the whole text over the remains", written, file("main.ink").readText())
        assertEquals(emptyMap<String, String>(), drafts())
    }

    @Test
    fun `a save typed with Ctrl+S right after typing has its draft before storage is touched`() = sessionTest {
        settings.updateAutosave(false)
        onStorage("main.ink", story)
        val first = open()
        editor(first, "main.ink").type(written)
        store.tornAfterBytes = 20

        // within the second: no draft timer has fired; the save never returns (the process dies in it)
        CoroutineScope(dispatcher).launch { first.saveAll() }
        pass(0)
        kill()
        store.tornAfterBytes = null

        assertEquals(mapOf("main.ink" to written), drafts())
        assertEquals(written, open(settings = settingsAfterRestart()).project.text("main.ink").toString())
    }

    @Test
    fun `a write that fails half way keeps the draft, and the retry writes over the remains without a conflict`() = sessionTest {
        onStorage("main.ink", story)
        val session = open()
        editor(session, "main.ink").type(written)
        store.tornAfterBytes = 12
        store.writeFailure = IOException("Provider died")
        pass(ProjectSession.AUTOSAVE_DELAY_MILLIS)

        assertEquals(written.take(12), file("main.ink").readText())
        assertEquals(mapOf("main.ink" to written), drafts())
        assertEquals(mapOf("main.ink" to "Provider died"), session.state.value.saveProblem?.failed)
        session.syncWithStorage()
        assertTrue("its own remains are not an outside change", session.project.state.value.conflicts.isEmpty())

        store.tornAfterBytes = null
        store.writeFailure = null
        assertTrue(session.saveAll().isComplete)

        assertEquals(written, file("main.ink").readText())
        assertEquals(emptyMap<String, String>(), drafts())
        assertNull(session.state.value.saveProblem)
    }

    @Test
    fun `a failed save gives the screens the failure itself, for a sentence in the user's language`() = sessionTest {
        onStorage("main.ink", story)
        val session = open()
        val failure = IOException("Permission lost")
        store.writeFailure = failure
        editor(session, "main.ink").type(written)

        pass(ProjectSession.AUTOSAVE_DELAY_MILLIS)

        assertSame(failure, session.state.value.saveProblem?.errors?.get("main.ink"))
    }

    // ------------------------------------------------------------------ a save fails

    @Test
    fun `a failed autosave keeps the text, the unsaved state and the draft, and says so`() = sessionTest {
        onStorage("main.ink", story)
        val session = open()
        store.writeFailure = IOException("Permission lost")
        editor(session, "main.ink").type(written)

        pass(ProjectSession.AUTOSAVE_DELAY_MILLIS)

        assertEquals(mapOf("main.ink" to "Permission lost"), session.state.value.saveProblem?.failed)
        assertTrue(session.project.state.value.file("main.ink")!!.hasUnsavedChanges)
        assertEquals(written, editor(session, "main.ink").text.toString())
        assertEquals("the draft is never cleared by a failed save", mapOf("main.ink" to written), drafts())
        assertEquals(story, file("main.ink").readText())
        assertFalse(session.state.value.saving)
    }

    @Test
    fun `retry after a failed save saves and clears the banner and the draft`() = sessionTest {
        onStorage("main.ink", story)
        val session = open()
        store.writeFailure = IOException("Permission lost")
        editor(session, "main.ink").type(written)
        pass(ProjectSession.AUTOSAVE_DELAY_MILLIS)

        store.writeFailure = null
        val report = session.saveAll()

        assertTrue(report.isComplete)
        assertNull(session.state.value.saveProblem)
        assertEquals(written, file("main.ink").readText())
        assertEquals(emptyMap<String, String>(), drafts())
    }

    @Test
    fun `text whose save failed survives the death of the process as well`() = sessionTest {
        onStorage("main.ink", story)
        val first = open()
        store.writeFailure = IOException("Permission lost")
        editor(first, "main.ink").type(written)
        pass(ProjectSession.AUTOSAVE_DELAY_MILLIS)

        kill()
        store.writeFailure = null
        val second = open(settings = settingsAfterRestart())

        assertEquals(written, second.project.text("main.ink").toString())
    }

    // ------------------------------------------------------------------ the file changes underneath

    @Test
    fun `a file changed outside without unsaved changes is reloaded into its editor when the window gains focus`() = sessionTest {
        onStorage("main.ink", story)
        val session = open()
        val editor = editor(session, "main.ink")
        editor.cursor = TextPosition(0, 4)

        onStorage("main.ink", "Rewritten on the computer\n")
        val report = session.syncWithStorage()

        assertEquals(listOf("main.ink"), report?.reloaded)
        assertEquals("Rewritten on the computer\n", editor.text.toString())
        assertEquals("as a load: undo is reset and it is not an edit", listOf("Rewritten on the computer\n"), editor.loads)
        assertFalse(session.project.state.value.hasUnsavedChanges)
        pass(ProjectSession.AUTOSAVE_DELAY_MILLIS)
        assertEquals("a reload is not written back", emptyList<String>(), store.writes)
    }

    @Test
    fun `a file changed outside while it has unsaved changes is a conflict, and autosave does not overwrite either side`() = sessionTest {
        onStorage("main.ink", story)
        val session = open()
        val editor = editor(session, "main.ink")
        editor.type(written)

        // within the autosave delay the same file is saved by another app
        onStorage("main.ink", "Rewritten on the computer\n")
        session.syncWithStorage()
        pass(10_000)

        assertEquals(listOf("main.ink"), session.project.state.value.conflicts.map { it.path })
        assertEquals(written, editor.text.toString())
        assertEquals(emptyList<String>(), editor.loads)
        assertEquals("Rewritten on the computer\n", file("main.ink").readText())
        assertEquals("my side is safe in the drafts meanwhile", mapOf("main.ink" to written), drafts())
    }

    @Test
    fun `autosave notices an outside change by itself, without a look at storage first`() = sessionTest {
        onStorage("main.ink", story)
        val session = open()
        onStorage("main.ink", "Rewritten on the computer\n")
        editor(session, "main.ink").type(written)

        pass(ProjectSession.AUTOSAVE_DELAY_MILLIS)

        assertEquals("Rewritten on the computer\n", file("main.ink").readText())
        assertTrue(session.project.state.value.file("main.ink")!!.hasConflict)
        assertNull("a conflict is not a failed save", session.state.value.saveProblem)
    }

    @Test
    fun `keep mine writes my text over the outside version`() = sessionTest {
        onStorage("main.ink", story)
        val session = open()
        editor(session, "main.ink").type(written)
        onStorage("main.ink", "Rewritten on the computer\n")
        session.syncWithStorage()

        session.resolveConflict("main.ink", keepMine = true)

        assertEquals(written, file("main.ink").readText())
        assertEquals(emptyList<String>(), session.project.state.value.conflicts.map { it.path })
        assertFalse(session.project.state.value.hasUnsavedChanges)
        assertEquals(emptyMap<String, String>(), drafts())
    }

    @Test
    fun `load theirs shows the outside version and drops my changes and their draft`() = sessionTest {
        onStorage("main.ink", story)
        val session = open()
        val editor = editor(session, "main.ink")
        editor.type(written)
        pass(ProjectSession.DRAFT_DELAY_MILLIS)
        onStorage("main.ink", "Rewritten on the computer\n")
        session.syncWithStorage()

        session.resolveConflict("main.ink", keepMine = false)

        assertEquals("Rewritten on the computer\n", editor.text.toString())
        assertEquals("Rewritten on the computer\n", file("main.ink").readText())
        assertFalse(session.project.state.value.hasUnsavedChanges)
        assertEquals(emptyMap<String, String>(), drafts())
    }

    @Test
    fun `a draft written over a version that changed while the app was dead comes back as a conflict, both sides intact`() = sessionTest {
        settings.updateAutosave(false)
        onStorage("main.ink", story)
        val first = open()
        editor(first, "main.ink").type(written)
        pass(ProjectSession.DRAFT_DELAY_MILLIS)
        kill()

        onStorage("main.ink", "Rewritten on the computer\n")
        val second = open(settings = settingsAfterRestart())

        assertEquals(written, second.project.text("main.ink").toString())
        assertEquals(listOf("main.ink"), second.state.value.restoredDrafts?.conflicts)
        assertTrue(second.project.state.value.file("main.ink")!!.hasConflict)
        assertEquals(SaveResult.Conflict, second.saveAll().results["main.ink"])
        assertEquals("Rewritten on the computer\n", file("main.ink").readText())
    }

    @Test
    fun `storage that cannot be reached is reported and changes nothing`() = sessionTest {
        onStorage("main.ink", "INCLUDE a.ink\n$story")
        onStorage("a.ink", "A\n")
        val session = open()
        val before = session.project.state.value
        store.listFailure = IOException("Permission lost")

        assertNull(session.syncWithStorage())

        assertEquals("Permission lost", session.state.value.storageProblem)
        assertEquals("no file is taken for deleted", before, session.project.state.value)

        store.listFailure = null
        assertNotNull(session.syncWithStorage())
        assertNull(session.state.value.storageProblem)
    }

    // ------------------------------------------------------------------ the keyboard never waits

    @Test
    fun `typing during a slow save is kept and saved by the next autosave`() = sessionTest {
        onStorage("main.ink", story)
        val session = open()
        val editor = editor(session, "main.ink")
        editor.type(written)
        store.writeDelayMillis = 3_000
        pass(ProjectSession.AUTOSAVE_DELAY_MILLIS + 500)
        assertTrue(session.state.value.saving)

        editor.type(written + "And one more line.\n")
        pass(20_000)

        assertEquals(written + "And one more line.\n", file("main.ink").readText())
        assertFalse(session.project.state.value.hasUnsavedChanges)
        assertFalse("its own slow save is not an outside change", session.project.state.value.file("main.ink")!!.hasConflict)
        assertFalse(session.state.value.saving)
    }
}
