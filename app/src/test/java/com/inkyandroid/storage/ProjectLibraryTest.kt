package com.inkyandroid.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkyandroid.core.project.NewProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/** Recent projects, permissions, the projects folder, internal projects, copies and exports. */
@RunWith(AndroidJUnit4::class)
class ProjectLibraryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val world by lazy { StorageWorld(temp) }
    private val storage by lazy { world.newStorage() }

    private fun folderProject(name: String): ProjectRef.Saf {
        world.put("$name/$name.ink", "")
        return (blocking { storage.openFolder(TestDocumentsProvider.treeUri(name)) } as FolderOpening.Opened).ref
    }

    // ---- recents -----------------------------------------------------------------------------------

    @Test
    fun openingPutsAProjectFirstAndTheListSurvivesTheProcess() {
        val a = folderProject("A")
        val b = folderProject("B")
        blocking { storage.open(a) }
        blocking { storage.open(b) }
        blocking { storage.open(a) }

        assertEquals(listOf(a, b), storage.recents.entries.value.map { it.ref })
        val again = world.newStorage().recents.entries.value
        assertEquals(listOf(a, b), again.map { it.ref })
        assertTrue(again[0].lastOpened > again[1].lastOpened)
    }

    @Test
    fun theListKeepsUpstreamsFiveAndGivesBackPermissionsNothingUses() {
        val refs = (1..6).map { folderProject("P$it") }
        refs.forEach { ref ->
            blocking { storage.open(ref) }
            storage.close(ref)
        }

        assertEquals(refs.drop(1).reversed(), storage.recents.entries.value.map { it.ref })
        assertFalse(refs[0].treeUri in world.grants())
        assertTrue(refs[5].treeUri in world.grants())
    }

    @Test
    fun anOpenProjectKeepsItsPermissionWhenItLeavesTheList() {
        val kept = folderProject("Kept")
        blocking { storage.open(kept) }
        (1..5).map { folderProject("P$it") }.forEach { ref ->
            blocking { storage.open(ref) }
            storage.close(ref)
        }

        assertFalse(storage.recents.entries.value.any { it.ref == kept })
        assertTrue(kept.treeUri in world.grants())

        storage.close(kept)
        assertFalse(kept.treeUri in world.grants())
    }

    @Test
    fun clearEmptiesTheListAndReleasesWhatOnlyItHeld() {
        val a = folderProject("A")
        blocking { storage.open(a) }
        storage.close(a)

        blocking { storage.recents.clear() }

        assertTrue(storage.recents.entries.value.isEmpty())
        assertTrue(world.newStorage().recents.entries.value.isEmpty())
        assertFalse(a.treeUri in world.grants())
    }

    @Test
    fun entriesWhoseStorageIsGoneAreRemovedOthersStay() {
        val gone = folderProject("Gone")
        val revoked = folderProject("Revoked")
        val fine = folderProject("Fine")
        listOf(gone, revoked, fine).forEach { blocking { storage.open(it) }; storage.close(it) }
        File(world.device, "Gone").deleteRecursively()
        world.resolver.releasePersistableUriPermission(android.net.Uri.parse(revoked.treeUri), StorageWorld.READ_WRITE)

        val removed = blocking { storage.recents.removeUnavailable() }

        assertEquals(setOf(gone, revoked), removed.map { it.ref }.toSet())
        assertEquals(listOf(fine), storage.recents.entries.value.map { it.ref })
    }

    @Test
    fun aDamagedRecentFileStartsEmpty() {
        File(world.filesDir, "recent-projects.json").writeText("{not json")

        assertTrue(world.newStorage().recents.entries.value.isEmpty())
    }

    // ---- references --------------------------------------------------------------------------------

    @Test
    fun referencesSurviveTheirStringForm() {
        val refs = listOf(
            ProjectRef.Saf("content://a/tree/x%3Ay", "x:y/z", "main.ink", "Hikâye"),
            ProjectRef.Internal("Örnek", "Örnek.ink"),
            ProjectRef.SingleDocument("content://files/42", "story.ink"),
        )
        refs.forEach { assertEquals(it, ProjectRef.decode(it.encode())) }
        assertNull(ProjectRef.decode(null))
        assertNull(ProjectRef.decode(""))
        assertNull(ProjectRef.decode("garbage"))
        assertNull(ProjectRef.decode("""{"v":2,"kind":"internal","name":"a","main":"a.ink"}"""))
        assertNull(ProjectRef.decode("""{"v":1,"kind":"saf","main":"a.ink"}"""))
    }

    // ---- new projects ------------------------------------------------------------------------------

    @Test
    fun withoutAProjectsFolderNewProjectsAreInternal() {
        val ref = blocking { storage.createProject("Kuyu") } as ProjectRef.Internal

        assertEquals(ProjectRef.Internal("Kuyu", "Kuyu.ink"), ref)
        assertEquals(NewProject.MAIN_TEXT, File(world.filesDir, "projects/Kuyu/Kuyu.ink").readText())
        assertEquals(ProjectRef.Internal("kuyu 2", "kuyu 2.ink"), blocking { storage.createProject("kuyu") })
        assertEquals(listOf("Kuyu", "kuyu 2"), blocking { storage.internalProjects.list() }.map { it.name })
    }

    @Test
    fun withAProjectsFolderNewProjectsGoThereWithoutAsking() {
        File(world.device, "Documents/Inky").mkdirs()
        world.put("Documents/Inky/Musluk/old.txt", "")
        val tree = TestDocumentsProvider.treeUri("Documents/Inky")
        assertNull(blocking { storage.setProjectsFolder(tree) })
        assertEquals("Inky", storage.projectsFolder.value?.displayName)

        val ref = blocking { storage.createProject("Musluk") } as ProjectRef.Saf

        assertEquals(ProjectRef.Saf(tree.toString(), "root:Documents/Inky/Musluk 2", "Musluk 2.ink", "Musluk 2"), ref)
        assertEquals(NewProject.MAIN_TEXT, world.text("Documents/Inky/Musluk 2/Musluk 2.ink"))
        assertEquals(storage.projectsFolder.value, world.newStorage().projectsFolder.value)
        assertTrue(tree.toString() in world.grants())

        storage.clearProjectsFolder()
        assertTrue(blocking { storage.createProject("Sonraki") } is ProjectRef.Internal)
        assertFalse(tree.toString() in world.grants())
    }

    @Test
    fun namesBecomeFolderNames() {
        assertEquals("Bir gece", ProjectNames.sanitize("  Bir   gece  "))
        assertEquals("a b c", ProjectNames.sanitize("a/b:c"))
        assertEquals("gizli", ProjectNames.sanitize(".gizli."))
        assertNull(ProjectNames.sanitize(" ... "))
        assertNull(ProjectNames.sanitize("/"))
        assertEquals(ProjectNames.MAX_LENGTH, ProjectNames.sanitize("x".repeat(300))!!.length)
        assertEquals("A 3", ProjectNames.unique("A", listOf("a", "A 2")))
    }

    // ---- internal projects and copies -----------------------------------------------------------------

    @Test
    fun internalProjectsAreListedWithTheirMainFileAndDeleted() {
        File(world.filesDir, "projects/Eski/chapters").mkdirs()
        File(world.filesDir, "projects/Eski/start.ink").writeText("INCLUDE chapters/a.ink")
        File(world.filesDir, "projects/Eski/chapters/a.ink").writeText("")
        val created = blocking { storage.internalProjects.create("Yeni", "Merhaba") }

        assertEquals(listOf(ProjectRef.Internal("Eski", "start.ink"), created), blocking { storage.internalProjects.list() })
        assertEquals("Merhaba", blocking { storage.open(created).read("Yeni.ink") })

        blocking { storage.internalProjects.delete(created) }
        assertFalse(File(world.filesDir, "projects/Yeni").exists())
        assertEquals(StorageProblem.NotFound, blocking { storage.checkAccess(created) })
    }

    @Test
    fun anInternalProjectIsCopiedOutToAFolderAndBackIn() {
        val internal = blocking { storage.internalProjects.create("Örnek", "INCLUDE parts/one.ink\n") }
        val store = storage.internalProjects.store(internal)
        blocking { store.write("parts/one.ink", "Bir") }
        File(world.filesDir, "projects/Örnek/cover.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0, 1, 2))
        File(world.device, "Documents").mkdirs()

        val out = blocking { storage.copyToFolder(internal, TestDocumentsProvider.treeUri("Documents")) }

        assertEquals(listOf("cover.png"), out.skipped)
        val outRef = out.ref as ProjectRef.Saf
        assertEquals("root:Documents/Örnek", outRef.rootDocumentId)
        assertEquals("Örnek.ink", outRef.mainPath)
        assertEquals("Bir", world.text("Documents/Örnek/parts/one.ink"))
        assertNull(blocking { storage.checkAccess(outRef) })

        val back = blocking { storage.copyToInternal(outRef) }
        assertEquals(ProjectRef.Internal("Örnek 2", "Örnek.ink"), back.ref)
        assertEquals("Bir", File(world.filesDir, "projects/Örnek 2/parts/one.ink").readText())
    }

    // ---- exports -----------------------------------------------------------------------------------

    @Test
    fun anExportFileIsWrittenAndVerified() {
        world.put("Exports/story.json", "an older and much longer export that must not survive")
        val uri = TestDocumentsProvider.documentUri("Exports/story.json")

        val name = blocking { storage.exports.writeFile(uri, """{"inkVersion":21}""") }

        assertEquals("story.json", name)
        assertEquals("""{"inkVersion":21}""", world.text("Exports/story.json"))
    }

    @Test
    fun aLostExportWriteFails() {
        world.put("Exports/story.json", "")
        world.behaviour.loseWrites = true

        failure<ProviderFailedException> { storage.exports.writeFile(TestDocumentsProvider.documentUri("Exports/story.json"), "{}") }
    }

    @Test
    fun theWebExportFillsAFolderAndOverwritesWhatIsThere() {
        world.put("Web/Hikâye/index.html", "old index")
        world.put("Web/Hikâye/keep.txt", "mine")
        val files = listOf("index.html" to "<html>", "main.js" to "var x;", "css/style.css" to "body{}")

        val folder = blocking { storage.exports.writeFolder(TestDocumentsProvider.treeUri("Web"), "Hikâye", files) }

        assertEquals("Hikâye", folder)
        assertEquals("<html>", world.text("Web/Hikâye/index.html"))
        assertEquals("body{}", world.text("Web/Hikâye/css/style.css"))
        assertEquals("mine", world.text("Web/Hikâye/keep.txt"))
    }

    @Test
    fun storageProblemsHaveSentencesInBothLanguages() {
        val resources = world.context.resources
        StorageProblem.entries.forEach { assertTrue(it.message(resources).isNotBlank()) }
    }
}
