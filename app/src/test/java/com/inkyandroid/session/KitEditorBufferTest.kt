package com.inkyandroid.session

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkyandroid.core.project.TextHash
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The project session's view of the kit's editor: changes, loads, edits, places, and every file's own line breaks. */
@RunWith(AndroidJUnit4::class)
class KitEditorBufferTest {

    private val buffers = ArrayList<KitEditorBuffer>()

    @After
    fun closeBuffers() = buffers.forEach(KitEditorBuffer::close)

    private fun buffer(text: String): Pair<KitEditorBuffer, IntArray> {
        val changes = IntArray(1)
        val buffer = KitEditorBuffer(text).apply { onTextChanged = { changes[0]++ } }
        buffers.add(buffer)
        return buffer to changes
    }

    /** What Compose does within the frame after state changed: the change reaches the buffer's watcher. */
    private fun applied() {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
    }

    // ------------------------------------------------------------------ changes

    @Test
    fun `a change by the user is reported once Compose has applied it`() {
        val (buffer, changes) = buffer("Once upon a time\n")

        buffer.state.select(buffer.state.length)
        buffer.state.insert("There were two choices.\n")
        applied()

        assertEquals(1, changes[0])
        assertEquals("Once upon a time\nThere were two choices.\n", buffer.text.toString())
    }

    @Test
    fun `the text is right at once, also before the change is reported`() {
        val (buffer, changes) = buffer("A")

        buffer.state.replace(1, 1, "B")

        assertEquals(0, changes[0])
        assertEquals("AB", buffer.text.toString())
    }

    @Test
    fun `a change is unreported until Compose has applied it, and a load leaves nothing unreported`() {
        val (buffer, _) = buffer("A")
        assertFalse(buffer.hasUnreportedChanges)

        buffer.state.replace(1, 1, "B")
        assertTrue(buffer.hasUnreportedChanges)
        applied()
        assertFalse(buffer.hasUnreportedChanges)

        buffer.state.replace(2, 2, "C")
        buffer.load("storage's version")
        assertFalse(buffer.hasUnreportedChanges)
    }

    @Test
    fun `the text is a value that does not change afterwards`() {
        val (buffer, _) = buffer("before")
        val taken = buffer.text

        buffer.state.replace(0, 6, "after")

        assertEquals("before", taken.toString())
        assertEquals("after", buffer.text.toString())
    }

    @Test
    fun `a load replaces the text, forgets the undo history, is not reported, and keeps the cursor`() {
        val (buffer, changes) = buffer("one\ntwo\nthree\n")
        buffer.state.replace(0, 3, "ONE")
        applied()
        buffer.cursor = TextPosition(2, 3)
        assertTrue(buffer.state.canUndo)

        buffer.load("one\ntwo\nthree, changed on the computer\n")
        applied()

        assertEquals(1, changes[0])
        assertEquals("one\ntwo\nthree, changed on the computer\n", buffer.text.toString())
        assertFalse(buffer.state.canUndo)
        assertEquals(TextPosition(2, 3), buffer.cursor)
    }

    @Test
    fun `a load into a shorter text puts the cursor where the text ends`() {
        val (buffer, _) = buffer("one\ntwo\nthree\nfour\n")
        buffer.cursor = TextPosition(3, 2)

        buffer.load("one\n")

        assertEquals(TextPosition(1, 0), buffer.cursor)
    }

    @Test
    fun `typing after a load is reported again`() {
        val (buffer, changes) = buffer("A")
        buffer.load("B")
        applied()

        buffer.state.replace(1, 1, "C")
        applied()

        assertEquals(1, changes[0])
    }

    @Test
    fun `the app's own edit is one step the user can undo, and is reported`() {
        val (buffer, changes) = buffer("Hello\n")

        buffer.edit(0, 0, "INCLUDE chapter.ink\n")
        applied()

        assertEquals("INCLUDE chapter.ink\nHello\n", buffer.text.toString())
        assertEquals(1, changes[0])
        assertTrue(buffer.state.undo())
        assertEquals("Hello\n", buffer.text.toString())
    }

    @Test
    fun `a closed buffer reports nothing`() {
        val (buffer, changes) = buffer("A")
        buffer.close()

        buffer.state.replace(1, 1, "B")
        applied()

        assertEquals(0, changes[0])
    }

    @Test
    fun `the line at the top of the view is the kit's, and setting it is where the view opens`() {
        val (buffer, _) = buffer("line\n".repeat(50))

        buffer.firstVisibleRow = 20

        assertEquals(20, buffer.state.firstVisibleLine)
        assertEquals(20, buffer.firstVisibleRow)
    }

    @Test
    fun `a load keeps the line at the top of the view`() {
        val (buffer, _) = buffer("line\n".repeat(50))
        buffer.firstVisibleRow = 20

        buffer.load("changed line\n".repeat(50))

        assertEquals(20, buffer.firstVisibleRow)
    }

    @Test
    fun `the cursor is in rows and columns`() {
        val (buffer, _) = buffer("one\ntwo\nthree\n")

        buffer.cursor = TextPosition(1, 2)

        assertEquals(TextPosition(1, 2), buffer.cursor)
        assertEquals(6, buffer.state.cursor)
    }

    // ------------------------------------------------------------------ line breaks

    @Test
    fun `a file written with CRLF keeps CRLF, also in what the user types`() {
        val stored = "Once upon a time\r\nThere were two choices.\r\n"
        val (buffer, _) = buffer(stored)
        assertEquals("the kit's editor has plain line breaks", "Once upon a time\nThere were two choices.\n", buffer.state.text)
        assertEquals(stored, buffer.text.toString())
        assertEquals("an untouched file is the stored file to the byte", TextHash.of(stored), TextHash.of(buffer.text))

        buffer.state.select(buffer.state.length)
        buffer.state.insert("* [One]\n* [The other]\n")

        assertEquals("$stored* [One]\r\n* [The other]\r\n", buffer.text.toString())
    }

    @Test
    fun `the app's edits count a CRLF as the two characters it is on storage`() {
        val (buffer, _) = buffer("INCLUDE a.ink\r\nHello\r\n")

        // after "INCLUDE a.ink\r\n", as the project computes it on the stored text
        buffer.edit(15, 15, "INCLUDE b.ink\r\n")
        assertEquals("INCLUDE a.ink\r\nINCLUDE b.ink\r\nHello\r\n", buffer.text.toString())

        // the include path of the second line, rewritten after a rename
        buffer.edit(23, 28, "chapters/b.ink")
        assertEquals("INCLUDE a.ink\r\nINCLUDE chapters/b.ink\r\nHello\r\n", buffer.text.toString())
    }

    @Test
    fun `a file written with CR alone keeps CR`() {
        val (buffer, _) = buffer("one\rtwo\r")

        buffer.state.select(buffer.state.length)
        buffer.state.insert("three\n")

        assertEquals("one\rtwo\rthree\r", buffer.text.toString())
    }

    @Test
    fun `the first line break decides, as in upstream's editor`() {
        val (buffer, _) = buffer("one\r\ntwo\nthree\r\n")

        buffer.state.replace(0, 0, "zero\n")

        assertEquals("zero\r\none\r\ntwo\r\nthree\r\n", buffer.text.toString())
    }

    @Test
    fun `an edit of the whole text takes the line breaks of the text put in`() {
        val (buffer, _) = buffer("one\ntwo\n")

        buffer.edit(0, buffer.text.length, "one\r\ntwo, from the other device\r\n")

        assertEquals("one\r\ntwo, from the other device\r\n", buffer.text.toString())
        assertTrue("still an edit Undo takes out", buffer.state.canUndo)
    }

    @Test
    fun `an edit of part of the text keeps the file's line breaks`() {
        val (buffer, _) = buffer("one\r\ntwo\r\n")

        buffer.edit(0, 3, "ONE\n")

        assertEquals("ONE\r\n\r\ntwo\r\n", buffer.text.toString())
    }

    @Test
    fun `a load takes the line breaks of the new text`() {
        val (buffer, _) = buffer("one\ntwo\n")

        buffer.load("one\r\ntwo\r\n")

        assertEquals("one\r\ntwo\r\n", buffer.text.toString())
    }

    @Test
    fun `an offset inside a CRLF lands before the line break`() {
        assertEquals(1, LineBreak.CrLf.toEditor("a\r\nb", 2))
        assertEquals(2, LineBreak.CrLf.toEditor("a\r\nb", 3))
        assertEquals(1, LineBreak.CrLf.toEditor("a\r\nb", 1))
        assertEquals(LineBreak.Lf, LineBreak.of("no line break"))
        assertEquals(LineBreak.Cr, LineBreak.of("ends with\r"))
        assertEquals(LineBreak.CrLf, LineBreak.of("\r\n"))
    }
}
