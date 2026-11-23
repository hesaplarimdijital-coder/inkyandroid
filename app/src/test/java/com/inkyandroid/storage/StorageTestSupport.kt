package com.inkyandroid.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.inkyandroid.core.project.DirectoryProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A test's storage world: a temporary "device storage" behind [TestDocumentsProvider], an app
 * files directory, and the [ProjectStorage] of a process over both.
 */
class StorageWorld(temp: TemporaryFolder) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val resolver: ContentResolver = context.contentResolver

    /** What the provider shows: a phone's shared storage. */
    val device: File = temp.newFolder("device")

    /** The app's files directory. */
    val filesDir: File = temp.newFolder("files")

    var now = 1_000L

    /** URIs another app shared with write access (a test sets them). */
    val writeGrants = HashSet<Uri>()

    init {
        TestDocumentsProvider.install(device)
    }

    val behaviour: TestDocumentsProvider.Behaviour get() = TestDocumentsProvider.behaviour

    /** A fresh process's storage over the same files (recents and settings are read again). */
    fun newStorage(): ProjectStorage = ProjectStorage(
        resolver = resolver,
        filesDir = filesDir,
        prefs = context.getSharedPreferences(ProjectStorage.PREFS_NAME, Context.MODE_PRIVATE),
        io = Dispatchers.IO,
        clock = { now++ },
        directoryStoreFactory = { dir, name -> DirectoryProjectStore(dir, name) },
        writeGranted = { it in writeGrants },
    )

    /** Writes a file on the "device", creating folders. */
    fun put(path: String, text: String): File = File(device, path).apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    fun put(path: String, bytes: ByteArray): File = File(device, path).apply {
        parentFile!!.mkdirs()
        writeBytes(bytes)
    }

    fun text(path: String): String = File(device, path).readText()

    fun exists(path: String): Boolean = File(device, path).exists()

    /** The persisted grants, as strings. */
    fun grants(): Set<String> = resolver.persistedUriPermissions.map { it.uri.toString() }.toSet()

    /** A store over [folder] of the device, as [ProjectStorage] builds it after a pick. */
    fun safStore(folder: String = ""): SafProjectStore {
        val tree = TestDocumentsProvider.treeUri(folder)
        resolver.takePersistableUriPermission(tree, READ_WRITE)
        return SafProjectStore(
            resolver, tree, TestDocumentsProvider.ROOT_ID + folder, folder.ifEmpty { "device" },
            permissionHeld = { resolver.persistedUriPermissions.any { it.uri == tree } }, io = Dispatchers.IO,
        )
    }

    companion object {
        const val READ_WRITE = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

/** Runs a suspending test body. */
fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

/** The exception [block] throws, asserting its type. */
inline fun <reified E : Throwable> failure(noinline block: suspend () -> Unit): E {
    try {
        runBlocking { block() }
    } catch (e: Throwable) {
        if (e is E) return e
        throw AssertionError("expected ${E::class.simpleName}, got $e", e)
    }
    throw AssertionError("expected ${E::class.simpleName}, nothing was thrown")
}
