package com.inkyandroid.core.project

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException

class DirectoryProjectStoreTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val scope = TestScope()
    private val root: File by lazy { temporary.newFolder("project") }
    private val store by lazy { DirectoryProjectStore(root, io = StandardTestDispatcher(scope.testScheduler)) }

    private fun file(path: String) = File(root, path)

    @Test
    fun `list finds every file at any depth with root-relative slash paths`() = scope.runTest {
        file("main.ink").writeText("main")
        file("chapters/part1").mkdirs()
        file("chapters/one.ink").writeText("one")
        file("chapters/part1/two.ink").writeText("two!")
        file("main.settings.json").writeText("{}")

        val listed = store.list().associateBy { it.path }

        assertEquals(setOf("main.ink", "chapters/one.ink", "chapters/part1/two.ink", "main.settings.json"), listed.keys)
        assertEquals(4L, listed.getValue("chapters/part1/two.ink").size)
        assertEquals(file("main.ink").lastModified(), listed.getValue("main.ink").modified)
    }

    @Test
    fun `list skips names that start with a dot`() = scope.runTest {
        file("main.ink").writeText("main")
        file(".git").mkdirs()
        file(".git/config.ink").writeText("x")
        file(".hidden.ink").writeText("x")

        assertEquals(listOf("main.ink"), store.list().map { it.path })
    }

    @Test
    fun `a root that does not exist yet has no files`() = scope.runTest {
        val absent = DirectoryProjectStore(File(root, "not-yet"), io = StandardTestDispatcher(testScheduler))
        assertEquals(emptyList<StoredFile>(), absent.list())
        assertNull(absent.stat("main.ink"))
    }

    @Test
    fun `read strips a byte order mark and nothing else`() = scope.runTest {
        file("bom.ink").writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Merhaba\r\ndünya\n".toByteArray())
        assertEquals("Merhaba\r\ndünya\n", store.read("bom.ink"))
    }

    @Test
    fun `line endings come back as stored and are written as given`() = scope.runTest {
        val text = "one\r\ntwo\rthree\n\n"
        store.write("mixed.ink", text)
        assertArrayEquals(text.toByteArray(), file("mixed.ink").readBytes())
        assertEquals(text, store.read("mixed.ink"))
    }

    @Test
    fun `a file that is not UTF-8 fails to read instead of being read damaged`() = scope.runTest {
        // "şey" in Windows-1254
        file("legacy.ink").writeBytes(byteArrayOf(0xFE.toByte(), 0x65, 0x79))
        assertThrows(CharacterCodingException::class.java) { runBlockingRead("legacy.ink") }
    }

    private fun runBlockingRead(path: String): String = kotlinx.coroutines.runBlocking { DirectoryProjectStore(root).read(path) }

    @Test
    fun `write creates the file and its parent folders and returns what stat reports`() = scope.runTest {
        val written = store.write("chapters/deep/one.ink", "Bir varmış")

        assertEquals("Bir varmış", file("chapters/deep/one.ink").readText())
        assertEquals(written, store.stat("chapters/deep/one.ink"))
        assertEquals(written, store.list().single())
        assertEquals("Bir varmış".toByteArray().size.toLong(), written.size)
    }

    @Test
    fun `write replaces the content and leaves no temporary file behind`() = scope.runTest {
        store.write("main.ink", "first, and rather long")
        store.write("main.ink", "second")

        assertEquals("second", store.read("main.ink"))
        assertEquals(listOf("main.ink"), root.list()!!.toList())
    }

    @Test
    fun `a write that fails leaves the old file whole`() = scope.runTest {
        store.write("main.ink", "precious")
        // a folder where the temporary file has to go makes the write fail before the target is touched
        File(root, ".main.ink.inky-tmp").mkdir()
        File(root, ".main.ink.inky-tmp/blocker").writeText("x")

        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { DirectoryProjectStore(root).write("main.ink", "new") } }

        assertEquals("precious", store.read("main.ink"))
    }

    @Test
    fun `write refuses a path that is a folder`() = scope.runTest {
        file("chapters").mkdirs()
        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { DirectoryProjectStore(root).write("chapters", "x") } }
        assertTrue(file("chapters").isDirectory)
    }

    @Test
    fun `stat is null for a missing file and for a folder`() = scope.runTest {
        file("chapters").mkdirs()
        assertNull(store.stat("missing.ink"))
        assertNull(store.stat("chapters"))
    }

    @Test
    fun `reading or deleting a missing file is NoSuchFileException`() = scope.runTest {
        assertThrows(NoSuchFileException::class.java) { kotlinx.coroutines.runBlocking { DirectoryProjectStore(root).read("missing.ink") } }
        assertThrows(NoSuchFileException::class.java) { kotlinx.coroutines.runBlocking { DirectoryProjectStore(root).delete("missing.ink") } }
    }

    @Test
    fun `delete removes the file`() = scope.runTest {
        store.write("a/gone.ink", "x")
        store.delete("a/gone.ink")
        assertFalse(file("a/gone.ink").exists())
        assertNull(store.stat("a/gone.ink"))
    }

    @Test
    fun `rename moves the file, creating folders, and keeps the content`() = scope.runTest {
        store.write("one.ink", "content")
        val moved = store.rename("one.ink", "chapters/first.ink")

        assertEquals("chapters/first.ink", moved.path)
        assertEquals(moved, store.stat("chapters/first.ink"))
        assertEquals("content", store.read("chapters/first.ink"))
        assertNull(store.stat("one.ink"))
    }

    @Test
    fun `rename never overwrites`() = scope.runTest {
        store.write("one.ink", "one")
        store.write("two.ink", "two")

        assertThrows(FileAlreadyExistsException::class.java) { kotlinx.coroutines.runBlocking { DirectoryProjectStore(root).rename("one.ink", "two.ink") } }

        assertEquals("one", store.read("one.ink"))
        assertEquals("two", store.read("two.ink"))
    }

    @Test
    fun `a path that leaves the root is refused`() = scope.runTest {
        for (bad in listOf("../outside.ink", "/abs.ink", "a/../../b.ink", "")) {
            assertThrows(bad, IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { DirectoryProjectStore(root).read(bad) } }
            assertThrows(bad, IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { DirectoryProjectStore(root).write(bad, "x") } }
        }
    }

    @Test
    fun `the display name is the folder's name unless one is given`() {
        assertEquals("project", DirectoryProjectStore(root).displayName)
        assertEquals("Hikâyem", DirectoryProjectStore(root, displayName = "Hikâyem").displayName)
    }
}
