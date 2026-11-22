package com.inkyandroid.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException

/** [SafProjectStore] against a real DocumentsProvider over a temporary directory. */
@RunWith(AndroidJUnit4::class)
class SafProjectStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val world by lazy { StorageWorld(temp) }

    @Test
    fun listsNestedFilesWithSizesAndSkipsHiddenOnes() {
        world.put("Story/main.ink", "INCLUDE chapters/one.ink\n")
        world.put("Story/chapters/one.ink", "Hello")
        world.put("Story/chapters/deep/two.ink", "")
        world.put("Story/notes.txt", "x")
        world.put("Story/.git/config", "[core]")
        world.put("Story/.draft.ink", "hidden")
        val store = world.safStore("Story")

        val files = blocking { store.list() }.associateBy { it.path }

        assertEquals(setOf("main.ink", "chapters/one.ink", "chapters/deep/two.ink", "notes.txt"), files.keys)
        assertEquals(5L, files.getValue("chapters/one.ink").size)
        assertEquals(File(world.device, "Story/chapters/one.ink").lastModified(), files.getValue("chapters/one.ink").modified)
    }

    @Test
    fun readsUtf8WithoutTheByteOrderMarkAndKeepsLineEndings() {
        world.put("Story/main.ink", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Çay\r\nbitti\r\n".toByteArray())
        val store = world.safStore("Story")

        assertEquals("Çay\r\nbitti\r\n", blocking { store.read("main.ink") })
    }

    @Test
    fun refusesTextThatIsNotUtf8() {
        world.put("Story/latin1.ink", byteArrayOf(0x43, 0xE7.toByte(), 0x61)) // "Ça" in Latin-1
        val store = world.safStore("Story")

        failure<NotTextException> { store.read("latin1.ink") }
    }

    @Test
    fun writeCreatesMissingFoldersAndReturnsWhatStatReports() {
        world.put("Story/main.ink", "")
        val store = world.safStore("Story")

        val written = blocking { store.write("chapters/new/scene.ink", "Bir sahne\n") }

        assertEquals("Bir sahne\n", world.text("Story/chapters/new/scene.ink"))
        assertEquals(written, blocking { store.stat("chapters/new/scene.ink") })
        assertEquals(written, blocking { store.list() }.single { it.path == "chapters/new/scene.ink" })
        assertEquals("Bir sahne\n".toByteArray().size.toLong(), written.size)
    }

    @Test
    fun writeReplacesALongerTextCompletely() {
        world.put("Story/main.ink", "a long text that is longer than what replaces it")
        val store = world.safStore("Story")

        blocking { store.write("main.ink", "short") }

        assertEquals("short", world.text("Story/main.ink"))
    }

    @Test
    fun writeFallsBackWhenTheProviderRefusesTruncatingMode() {
        world.put("Story/main.ink", "a long text that is longer than what replaces it")
        world.behaviour.refuseTruncateMode = true
        val store = world.safStore("Story")

        blocking { store.write("main.ink", "short") }

        assertEquals("short", world.text("Story/main.ink"))
    }

    @Test
    fun aWriteTheProviderSilentlyLosesFails() {
        world.put("Story/main.ink", "old")
        world.behaviour.loseWrites = true
        val store = world.safStore("Story")

        failure<ProviderFailedException> { store.write("main.ink", "new and longer") }
        assertEquals("old", world.text("Story/main.ink"))
    }

    @Test
    fun withoutASizeColumnTheWriteIsVerifiedByReadingBack() {
        world.put("Story/main.ink", "old")
        world.behaviour.omitSize = true
        val store = world.safStore("Story")

        val written = blocking { store.write("main.ink", "new") }

        assertEquals(0L, written.size)
        assertEquals("new", world.text("Story/main.ink"))
    }

    @Test
    fun aFileTheProviderStoresWithAnotherExtensionIsRenamedBack() {
        world.put("Story/main.ink", "")
        world.behaviour.renameOnCreate = { "$it.txt" }
        val store = world.safStore("Story")

        blocking { store.write("extra.ink", "x") }

        assertTrue(world.exists("Story/extra.ink"))
        assertFalse(world.exists("Story/extra.ink.txt"))
    }

    @Test
    fun aRenamedCreateThatCannotBeUndoneFailsAndLeavesNothingBehind() {
        world.put("Story/main.ink", "")
        world.behaviour.renameOnCreate = { "$it.txt" }
        world.behaviour.supportsRename = false
        val store = world.safStore("Story")

        failure<ProviderFailedException> { store.write("extra.ink", "x") }
        assertEquals(listOf("main.ink"), File(world.device, "Story").list()!!.toList())
    }

    @Test
    fun anExistingFileTheCacheDidNotKnowIsUsedInsteadOfANumberedCopy() {
        world.put("Story/main.ink", "")
        val store = world.safStore("Story")
        blocking { store.list() }
        world.put("Story/late.ink", "outside")

        blocking { store.write("late.ink", "ours") }

        assertEquals("ours", world.text("Story/late.ink"))
        assertEquals(listOf("late.ink", "main.ink"), File(world.device, "Story").list()!!.sorted())
    }

    @Test
    fun renamesInTheSameFolder() {
        world.put("Story/a.ink", "A")
        val store = world.safStore("Story")

        val renamed = blocking { store.rename("a.ink", "b.ink") }

        assertEquals("b.ink", renamed.path)
        assertEquals("A", world.text("Story/b.ink"))
        assertFalse(world.exists("Story/a.ink"))
        assertNull(blocking { store.stat("a.ink") })
    }

    @Test
    fun movesToAnotherFolderCreatingIt() {
        world.put("Story/a.ink", "A")
        val store = world.safStore("Story")

        blocking { store.rename("a.ink", "chapters/b.ink") }

        assertEquals("A", world.text("Story/chapters/b.ink"))
        assertFalse(world.exists("Story/a.ink"))
    }

    @Test
    fun withoutRenameAndMoveSupportARenameCopiesAndDeletes() {
        world.put("Story/a.ink", "A")
        world.behaviour.supportsRename = false
        world.behaviour.supportsMove = false
        val store = world.safStore("Story")

        blocking { store.rename("a.ink", "b.ink") }
        blocking { store.rename("b.ink", "sub/c.ink") }

        assertEquals("A", world.text("Story/sub/c.ink"))
        assertFalse(world.exists("Story/a.ink"))
        assertFalse(world.exists("Story/b.ink"))
    }

    @Test
    fun renameNeverOverwritesAndNeedsAnExistingSource() {
        world.put("Story/a.ink", "A")
        world.put("Story/b.ink", "B")
        val store = world.safStore("Story")

        failure<FileAlreadyExistsException> { store.rename("a.ink", "b.ink") }
        failure<NoSuchFileException> { store.rename("missing.ink", "c.ink") }
        assertEquals("B", world.text("Story/b.ink"))
    }

    @Test
    fun deletesAFileAndReportsAMissingOne() {
        world.put("Story/a.ink", "A")
        val store = world.safStore("Story")

        blocking { store.delete("a.ink") }

        assertFalse(world.exists("Story/a.ink"))
        failure<NoSuchFileException> { store.delete("a.ink") }
        failure<NoSuchFileException> { store.read("a.ink") }
    }

    @Test
    fun followsAFileRenamedOutsideAfterListing() {
        world.put("Story/a.ink", "A")
        val store = world.safStore("Story")
        blocking { store.list() }
        File(world.device, "Story/a.ink").renameTo(File(world.device, "Story/b.ink"))

        assertNull(blocking { store.stat("a.ink") })
        assertEquals("A", blocking { store.read("b.ink") })
    }

    @Test
    fun namesAreMatchedExactly() {
        world.put("Story/Chapter.ink", "A")
        val store = world.safStore("Story")

        assertNull(blocking { store.stat("chapter.ink") })
    }

    @Test
    fun rejectsPathsOutsideTheRules() {
        val store = world.safStore("")

        failure<IllegalArgumentException> { store.read("../etc/passwd") }
        failure<IllegalArgumentException> { store.write("/abs.ink", "") }
        failure<IllegalArgumentException> { store.stat("a//b.ink") }
    }

    @Test
    fun aFolderTooDeepIsReportedInsteadOfListedInPart() {
        world.put("Big/" + (1..StorageLimits.MAX_DEPTH + 1).joinToString("/") { "d$it" } + "/x.ink", "")
        val store = world.safStore("Big")

        failure<FolderTooLargeException> { store.list() }
    }

    @Test
    fun aFolderWithTooManyEntriesIsReportedInsteadOfListedInPart() {
        val dir = File(world.device, "Big").apply { mkdirs() }
        repeat(StorageLimits.MAX_ENTRIES + 1) { File(dir, "f$it.txt").createNewFile() }
        val store = world.safStore("Big")

        failure<FolderTooLargeException> { store.list() }
    }

    @Test
    fun aCrashingProviderIsAProviderFailureWithItsWords() {
        world.put("Story/a.ink", "A")
        val store = world.safStore("Story")
        world.behaviour.crash = true

        val e = failure<ProviderFailedException> { store.list() }
        assertTrue(e.detail, e.detail.contains("crashed"))
        assertEquals(StorageProblem.ProviderFailed, StorageProblem.of(e))
    }

    @Test
    fun aPermissionRevokedMidSessionIsReportedAsLost() {
        world.put("Story/a.ink", "A")
        val store = world.safStore("Story")
        assertEquals("A", blocking { store.read("a.ink") })

        world.resolver.releasePersistableUriPermission(store.treeUri, StorageWorld.READ_WRITE)
        world.behaviour.denyAccess = true

        failure<PermissionLostException> { store.read("a.ink") }
        failure<PermissionLostException> { store.write("a.ink", "B") }
        assertEquals(StorageProblem.PermissionLost, blocking { store.checkAccess() })
        assertEquals("A", world.text("Story/a.ink"))
    }

    @Test
    fun aProjectFolderDeletedOutsideIsNotFound() {
        world.put("Story/a.ink", "A")
        val store = world.safStore("Story")
        File(world.device, "Story").deleteRecursively()

        failure<NoSuchFileException> { store.list() }
        assertEquals(StorageProblem.NotFound, blocking { store.checkAccess() })
    }
}
