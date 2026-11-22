package com.inkyandroid.storage

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.nio.file.NoSuchFileException

/** Picking a folder, opening one file, granting its folder afterwards, and the VIEW intent. */
@RunWith(AndroidJUnit4::class)
class ProjectOpeningTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val world by lazy { StorageWorld(temp) }
    private val storage by lazy { world.newStorage() }

    // ---- folders ---------------------------------------------------------------------------------

    @Test
    fun aFolderWithOneMainFileOpensAtOnceAndKeepsItsPermission() {
        world.put("Story/main.ink", "INCLUDE chapters/one.ink\nINCLUDE two.ink  \n")
        world.put("Story/chapters/one.ink", "")
        world.put("Story/two.ink", "")
        val tree = TestDocumentsProvider.treeUri("Story")

        val opened = blocking { storage.openFolder(tree) }

        assertEquals(FolderOpening.Opened(ProjectRef.Saf(tree.toString(), "root:Story", "main.ink", "Story")), opened)
        assertTrue(tree.toString() in world.grants())
    }

    @Test
    fun severalPossibleMainFilesAreAskedForAndTheAnswerIsRemembered() {
        world.put("Drafts/a.ink", "")
        world.put("Drafts/b.ink", "INCLUDE shared.ink")
        world.put("Drafts/shared.ink", "")
        val tree = TestDocumentsProvider.treeUri("Drafts")

        val asked = blocking { storage.openFolder(tree) } as FolderOpening.ChooseMain
        assertEquals(listOf("a.ink", "b.ink"), asked.candidates)

        val chosen = storage.chooseMain(asked.folder, "b.ink")
        assertEquals("b.ink", chosen.mainPath)
        assertEquals(FolderOpening.Opened(chosen), blocking { world.newStorage().openFolder(tree) })
    }

    @Test
    fun anIncludeCycleStillLeavesAChoice() {
        world.put("Loop/a.ink", "INCLUDE b.ink")
        world.put("Loop/b.ink", "INCLUDE a.ink")

        val asked = blocking { storage.openFolder(TestDocumentsProvider.treeUri("Loop")) } as FolderOpening.ChooseMain

        assertEquals(listOf("a.ink", "b.ink"), asked.candidates)
    }

    @Test
    fun aFolderWithoutInkFilesSaysSoAndCanGetANewProject() {
        world.put("Empty/notes.txt", "")
        world.put("Empty/sub/deep.ink", "")
        val tree = TestDocumentsProvider.treeUri("Empty")

        val result = blocking { storage.openFolder(tree) } as FolderOpening.NoInkFiles
        val created = blocking { storage.createProjectIn(result.folder, "İlk hikâye") }

        assertEquals("İlk hikâye.ink", created.mainPath)
        assertEquals("İlk hikâye", created.displayName)
        assertTrue(world.text("Empty/İlk hikâye/İlk hikâye.ink").startsWith("Once upon a time"))
        assertNull(blocking { storage.checkAccess(created) })
    }

    @Test
    fun aPickedFolderCanTakeANewProjectWithoutBeingScanned() {
        world.put("Yazılar/notes.ink", "")
        val tree = TestDocumentsProvider.treeUri("Yazılar")

        val folder = blocking { storage.folderFor(tree) }
        val created = blocking { storage.createProjectIn(folder, "Roman") }

        assertEquals(PickedFolder(tree.toString(), "root:Yazılar", "Yazılar"), folder)
        assertEquals("root:Yazılar/Roman", created.rootDocumentId)
        assertTrue(world.exists("Yazılar/Roman/Roman.ink"))
        assertTrue(tree.toString() in world.grants())
    }

    @Test
    fun aFolderThatCannotBeReadFails() {
        world.put("Story/main.ink", "")
        world.behaviour.crash = true

        val result = blocking { storage.openFolder(TestDocumentsProvider.treeUri("Story")) }

        assertTrue(result is FolderOpening.Failed)
    }

    // ---- single files ----------------------------------------------------------------------------

    @Test
    fun aFileInAGrantedFolderOpensAsThatFoldersProject() {
        world.put("Story/main.ink", "INCLUDE one.ink")
        world.put("Story/one.ink", "")
        val tree = TestDocumentsProvider.treeUri("")
        world.resolver.takePersistableUriPermission(tree, StorageWorld.READ_WRITE)

        val result = blocking { storage.openFile(TestDocumentsProvider.documentUri("Story/main.ink")) }

        assertEquals(FileOpening.InFolder(ProjectRef.Saf(tree.toString(), "root:Story", "main.ink", "Story")), result)
    }

    @Test
    fun withoutFindDocumentPathAnotherProvidersFileOpensAlone() {
        world.put("Story/main.ink", "")
        world.behaviour.noFindPath = true
        val tree = TestDocumentsProvider.treeUri("Story")
        world.resolver.takePersistableUriPermission(tree, StorageWorld.READ_WRITE)

        // only the system's external storage provider has a known id scheme to fall back on
        val result = blocking { storage.openFile(TestDocumentsProvider.documentUri("Story/main.ink")) }

        assertTrue(result is FileOpening.Single)
    }

    @Test
    fun theExternalStorageIdSchemeGivesTheParentFolder() {
        assertEquals("primary:Stories", TreeLocator.externalStorageParent("primary:Stories/a.ink"))
        assertEquals("primary:", TreeLocator.externalStorageParent("primary:a.ink"))
        assertNull(TreeLocator.externalStorageParent("primary:"))
        val hint = TreeLocator.folderHint(Uri.parse("content://com.android.externalstorage.documents/document/primary%3AStories%2Fa.ink"))
        assertEquals("content://com.android.externalstorage.documents/document/primary%3AStories", hint.toString())
    }

    @Test
    fun aFileOutsideGrantedFoldersOpensAloneAndOnlyItsOwnPathExists() {
        world.put("Story/main.ink", "INCLUDE one.ink\n")
        world.put("Story/one.ink", "")
        val uri = TestDocumentsProvider.documentUri("Story/main.ink")
        world.writeGrants += uri

        val single = blocking { storage.openFile(uri) } as FileOpening.Single
        assertEquals(ProjectRef.SingleDocument(uri.toString(), "main.ink"), single.ref)
        assertTrue(single.writable)
        assertTrue(single.remembered)

        val store = blocking { storage.open(single.ref) }
        assertEquals(listOf("main.ink"), blocking { store.list() }.map { it.path })
        assertEquals("INCLUDE one.ink\n", blocking { store.read("main.ink") })
        assertNull(blocking { store.stat("one.ink") })
        failure<NoSuchFileException> { store.read("one.ink") }
        failure<NoSuchFileException> { store.write("one.ink", "x") }

        val written = blocking { store.write("main.ink", "Yeni") }
        assertEquals("Yeni", world.text("Story/main.ink"))
        assertEquals(written, blocking { store.stat("main.ink") })
        assertEquals(single.ref, storage.recents.entries.value.single().ref)
    }

    @Test
    fun aReadOnlyFileCanBeReadButNotSaved() {
        world.put("Story/main.ink", "metin")
        world.behaviour.readOnlyPaths += "Story/main.ink"
        val uri = TestDocumentsProvider.documentUri("Story/main.ink")

        val single = blocking { storage.openFile(uri) } as FileOpening.Single
        assertFalse(single.writable)

        val store = storage.storeFor(single.ref)
        assertEquals("metin", blocking { store.read("main.ink") })
        val e = failure<ReadOnlyException> { store.write("main.ink", "x") }
        assertEquals(StorageProblem.ReadOnly, StorageProblem.of(e))
        assertEquals("metin", world.text("Story/main.ink"))
    }

    @Test
    fun grantingTheFoldersOfASingleFileUpgradesTheProject() {
        world.put("Story/main.ink", "INCLUDE one.ink")
        world.put("Story/one.ink", "")
        world.put("Other/x.ink", "")
        val uri = TestDocumentsProvider.documentUri("Story/main.ink")
        val single = blocking { storage.openFile(uri) } as FileOpening.Single

        assertEquals(FolderUpgrade.NotContained, blocking { storage.upgradeToFolder(single.ref, TestDocumentsProvider.treeUri("Other")) })

        val upgraded = blocking { storage.upgradeToFolder(single.ref, TestDocumentsProvider.treeUri("Story")) } as FolderUpgrade.Upgraded
        assertEquals(ProjectRef.Saf(TestDocumentsProvider.treeUri("Story").toString(), "root:Story", "main.ink", "Story"), upgraded.ref)

        // a parent folder works too: the project is rooted at the file's own folder
        val fromParent = blocking { storage.upgradeToFolder(single.ref, TestDocumentsProvider.treeUri("")) } as FolderUpgrade.Upgraded
        assertEquals("root:Story", fromParent.ref.rootDocumentId)
        assertEquals(setOf("main.ink", "one.ink"), blocking { storage.storeFor(fromParent.ref).list() }.map { it.path }.toSet())
    }

    @Test
    fun aFileFromAnotherProviderIsRecognisedByNameInTheGrantedFolder() {
        world.put("Story/main.ink", "")
        val single = ProjectRef.SingleDocument("content://com.example.files/share/main.ink", "main.ink")

        val upgraded = blocking { storage.upgradeToFolder(single, TestDocumentsProvider.treeUri("Story")) }

        assertEquals(FolderUpgrade.Upgraded(ProjectRef.Saf(TestDocumentsProvider.treeUri("Story").toString(), "root:Story", "main.ink", "Story")), upgraded)
    }

    @Test
    fun binaryFilesAreNotOpened() {
        world.put("Pictures/cover.ink", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0, 0, 1))

        val result = blocking { storage.openFile(TestDocumentsProvider.documentUri("Pictures/cover.ink")) }

        assertEquals(FileOpening.NotText("cover.ink"), result)
    }

    @Test
    fun aMissingFileFails() {
        val result = blocking { storage.openFile(TestDocumentsProvider.documentUri("nowhere.ink")) }

        assertTrue(result is FileOpening.Failed)
    }

    // ---- intents ---------------------------------------------------------------------------------

    @Test
    fun viewIntentsWithContentUrisAreFilesToOpen() {
        val uri = Uri.parse("content://com.example.files/share/story.ink")

        assertEquals(uri, ProjectIntents.fileToOpen(Intent(Intent.ACTION_VIEW, uri)))
        assertNull(ProjectIntents.fileToOpen(Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/story.ink"))))
        assertNull(ProjectIntents.fileToOpen(Intent(Intent.ACTION_MAIN)))
        assertNull(ProjectIntents.fileToOpen(null))
    }

    @Test
    fun thePickerContractsAskForPersistableAccessAndStartWhereTold() {
        val folder = StorageContracts.PickFolder().createIntent(world.context, StorageContracts.DOCUMENTS_HINT)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, folder.action)
        assertEquals(StorageContracts.DOCUMENTS_HINT, folder.getParcelableExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java))
        assertTrue(folder.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)

        val file = StorageContracts.OpenInkFile().createIntent(world.context, null)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, file.action)
        assertEquals("*/*", file.type)

        val export = StorageContracts.CreateExportFile().createIntent(world.context, ExportFileRequest.javascript("story.js"))
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, export.action)
        assertEquals("story.js", export.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals("application/octet-stream", export.type)
        assertEquals("application/json", ExportFileRequest.json("story.json").mimeType)

        val picked = Uri.parse("content://x/tree/y")
        assertEquals(picked, StorageContracts.PickFolder().parseResult(android.app.Activity.RESULT_OK, Intent().setData(picked)))
        assertNull(StorageContracts.PickFolder().parseResult(android.app.Activity.RESULT_CANCELED, Intent().setData(picked)))
        assertNull(StorageContracts.OpenInkFile().parseResult(android.app.Activity.RESULT_OK, null))
    }

    @Test
    fun theProcessInstanceKeepsInternalProjectsInTheAppsFiles() {
        val shared = ProjectStorage.from(world.context)
        assertTrue(shared === ProjectStorage.from(world.context))

        val ref = blocking { shared.internalProjects.create("Örnek") }
        assertEquals("Örnek.ink", ref.mainPath)
        assertTrue(java.io.File(world.context.filesDir, "projects/Örnek/Örnek.ink").isFile)
        blocking { shared.internalProjects.delete(ref) }
    }

    @Test
    fun theManifestOffersInkyForInkFilesFromOtherApps() {
        val context = world.context
        val packageManager = context.packageManager
        val ink = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://com.example.files/root/My.Story.ink"), "application/x-unknown")
        val octet = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://com.example.files/42"), "application/octet-stream")
        val image = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://com.example.files/cover.png"), "image/png")

        assertFalse(packageManager.queryIntentActivities(ink, 0).isEmpty())
        assertFalse(packageManager.queryIntentActivities(octet, 0).isEmpty())
        assertTrue(packageManager.queryIntentActivities(image, 0).isEmpty())
    }
}
