package com.inkyandroid.core.project

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileDraftStoreTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val scope = TestScope()
    private val directory: File by lazy { File(temporary.root, "drafts/project") }
    private val store by lazy { FileDraftStore(directory, io = StandardTestDispatcher(scope.testScheduler)) }

    @Test
    fun `a store whose directory does not exist yet has no drafts`() = scope.runTest {
        assertEquals(emptyList<Draft>(), store.load())
    }

    @Test
    fun `a draft comes back exactly as it was put`() = scope.runTest {
        val draft = Draft("chapters/one.ink", "Bir varmış, bir yokmuş…\r\n\n  * Seçim\n", baseHash = TextHash.of("old"), writtenAt = 1234L)
        store.put(draft)
        assertEquals(listOf(draft), store.load())
    }

    @Test
    fun `the draft of a save that has begun says so, and an older draft without the mark is no such draft`() = scope.runTest {
        val saving = Draft("main.ink", "the text being written", baseHash = TextHash.of("old"), writtenAt = 7L, saving = true)
        store.put(saving)
        assertEquals(listOf(saving), store.load())

        // a draft as the first version of the app wrote it: no "saving" line
        directory.listFiles()!!.single().writeText(
            "inky-draft 1\npath: main.ink\nbase: -\nwritten: 2\nsha256: ${TextHash.of("old text")}\n\nold text",
        )
        assertEquals(listOf(Draft("main.ink", "old text", baseHash = null, writtenAt = 2L, saving = false)), store.load())
    }

    @Test
    fun `a draft of a file that was never stored has no base`() = scope.runTest {
        val draft = Draft("new.ink", "", baseHash = null, writtenAt = 1L)
        store.put(draft)
        assertEquals(listOf(draft), store.load())
    }

    @Test
    fun `text that looks like the header is still text`() = scope.runTest {
        val draft = Draft("main.ink", "\n\npath: other.ink\nsha256: nope\n\n", baseHash = null, writtenAt = 1L)
        store.put(draft)
        assertEquals(listOf(draft), store.load())
    }

    @Test
    fun `a path with a line break or a backslash survives`() = scope.runTest {
        val draft = Draft("odd\nna\\me.ink", "x", baseHash = null, writtenAt = 1L)
        store.put(draft)
        assertEquals(listOf(draft), store.load())
    }

    @Test
    fun `putting a draft for the same path replaces it`() = scope.runTest {
        store.put(Draft("main.ink", "first", null, 1L))
        store.put(Draft("main.ink", "second", null, 2L))
        store.put(Draft("other.ink", "other", null, 3L))

        assertEquals(setOf("second", "other"), store.load().map { it.text }.toSet())
        assertEquals(2, directory.list()!!.size)
    }

    @Test
    fun `remove forgets one draft and is quiet about a missing one`() = scope.runTest {
        store.put(Draft("main.ink", "a", null, 1L))
        store.put(Draft("other.ink", "b", null, 1L))

        store.remove("main.ink")
        store.remove("never-there.ink")

        assertEquals(listOf("other.ink"), store.load().map { it.path })
    }

    @Test
    fun `a truncated draft is skipped, the others are read`() = scope.runTest {
        store.put(Draft("main.ink", "a long enough text to cut in half", null, 1L))
        store.put(Draft("other.ink", "whole", null, 1L))
        val victim = directory.listFiles()!!.first { it.readText().contains("path: main.ink") }
        victim.writeBytes(victim.readBytes().let { it.copyOf(it.size - 10) })

        assertEquals(listOf("other.ink"), store.load().map { it.path })
    }

    @Test
    fun `files that are not drafts are ignored`() = scope.runTest {
        store.put(Draft("main.ink", "a", null, 1L))
        File(directory, "notes.txt").writeText("hello")
        File(directory, "broken.draft").writeText("not a draft at all")
        File(directory, "half.draft.tmp").writeText("inky-draft 1\n")

        assertEquals(listOf("main.ink"), store.load().map { it.path })
    }

    @Test
    fun `a draft is on disk when put returns, with no temporary file left`() = scope.runTest {
        store.put(Draft("main.ink", "kept", null, 1L))
        val files = directory.list()!!.toList()
        assertEquals(1, files.size)
        assertTrue(files.single().endsWith(".draft"))
        // a second store on the same directory (the app after a restart) reads it
        assertEquals("kept", FileDraftStore(directory, io = StandardTestDispatcher(testScheduler)).load().single().text)
    }
}
