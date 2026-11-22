package com.inkyandroid.storage

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import com.inkyandroid.core.project.DirectoryProjectStore
import com.inkyandroid.core.project.NewProject
import com.inkyandroid.core.project.ProjectPaths
import com.inkyandroid.core.project.ProjectStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.NoSuchFileException

/**
 * Everything about where projects live, for the session and the screens: turning a [ProjectRef]
 * into its [ProjectStore], the recent projects, the projects folder, creating projects, and what
 * the system pickers' results mean (a folder, a single file, a file another app sent).
 *
 * One instance per process ([from]): it counts which projects are open in which windows, so that
 * a folder's permission is never given back while a window still uses it.
 *
 * Every `suspend` function does its own dispatching; call them from any coroutine.
 */
class ProjectStorage internal constructor(
    private val resolver: ContentResolver,
    filesDir: File,
    private val prefs: SharedPreferences,
    private val io: CoroutineDispatcher,
    clock: () -> Long,
    directoryStoreFactory: (File, String) -> ProjectStore,
    /** Whether this process may write to a URI another app shared (a permission check). */
    private val writeGranted: (Uri) -> Boolean,
) {
    private val permissions = UriPermissions(resolver)

    /** Ref keys of the projects open in a window, with how many windows have each open. Guarded by itself. */
    private val openCounts = HashMap<String, Pair<ProjectRef, Int>>()

    /**
     * Grants taken in this process for a pick whose project is not opened yet (the user is still
     * choosing a main file); they are not given back meanwhile. Guarded by [openCounts].
     */
    private val pendingGrants = HashSet<String>()

    /** The app's own projects. */
    val internalProjects = InternalProjects(File(filesDir, "projects"), io, directoryStoreFactory)

    /** Upstream's Open Recent. [open] adds to it; the projects screen and the menu read it. */
    val recents = RecentProjects(
        file = File(filesDir, "recent-projects.json"),
        io = io,
        clock = clock,
        isAvailable = ::checkAccess,
        onRemoved = ::releaseUnusedPermissions,
    )

    /** Where exports are written. */
    val exports = ExportWriter(resolver, io)

    private val folderState = MutableStateFlow(loadProjectsFolder())

    /** The folder new projects go to; `null` until the user picks one (new projects are internal meanwhile). */
    val projectsFolder: StateFlow<PickedFolder?> = folderState.asStateFlow()

    // ---- stores ------------------------------------------------------------------------------------

    /**
     * The store of [ref], for a window that opens the project: the project counts as open (its
     * permission is kept) until [close] is called with the same ref, and it goes to the front of
     * the recent projects (a single file only when its permission survives a restart).
     */
    suspend fun open(ref: ProjectRef): ProjectStore {
        val store = storeFor(ref)
        synchronized(openCounts) {
            val (_, count) = openCounts[ref.key] ?: (ref to 0)
            openCounts[ref.key] = ref to count + 1
            pendingGrants.remove(grantOf(ref))
        }
        if (isRememberable(ref)) recents.add(ref)
        return store
    }

    /** The window that opened [ref] closed it (or moved to another project). */
    fun close(ref: ProjectRef) {
        synchronized(openCounts) {
            val (_, count) = openCounts[ref.key] ?: return
            if (count <= 1) openCounts.remove(ref.key) else openCounts[ref.key] = ref to count - 1
        }
        releaseUnusedPermissions()
    }

    /**
     * A store for [ref] that does not count as opened: for a quick look (a preview, a check).
     * Prefer [open] for a project a window works on.
     */
    fun storeFor(ref: ProjectRef): ProjectStore = when (ref) {
        is ProjectRef.Saf -> folderStore(PickedFolder(ref.treeUri, ref.rootDocumentId, ref.displayName))
        is ProjectRef.Internal -> internalProjects.store(ref)
        is ProjectRef.SingleDocument -> {
            val uri = Uri.parse(ref.documentUri)
            SingleDocumentStore(
                resolver, uri, ref.mainPath, isWritable = isWritable(uri),
                permissionHeld = { permissions.holds(ref.documentUri) || writeGranted(uri) }, io = io,
            )
        }
    }

    /**
     * `null` when [ref]'s project can be opened now (its folder answers and the main file is
     * there); otherwise why not. Never throws, except on cancellation.
     */
    suspend fun checkAccess(ref: ProjectRef): StorageProblem? = try {
        when (ref) {
            is ProjectRef.Internal -> internalProjects.checkAccess(ref)
            is ProjectRef.Saf -> {
                val store = storeFor(ref) as SafProjectStore
                store.checkAccess() ?: if (store.stat(ref.mainPath) == null) StorageProblem.NotFound else null
            }
            is ProjectRef.SingleDocument -> (storeFor(ref) as SingleDocumentStore).checkAccess()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        StorageProblem.of(e)
    }

    // ---- opening what the pickers return -------------------------------------------------------------

    /**
     * The folder the tree picker returned ([StorageContracts.PickFolder]): its permission is
     * taken, its `.ink` files are looked at, and the main file is decided if it can be: the one
     * remembered for this folder, or the only candidate.
     */
    suspend fun openFolder(treeUri: Uri): FolderOpening {
        val folder = try {
            withContext(io) { pickedFolder(treeUri) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return FolderOpening.Failed(StorageProblem.of(e), e)
        }
        return openPickedFolder(folder)
    }

    /** The user's answer to [FolderOpening.ChooseMain]; remembered for the folder. */
    fun chooseMain(folder: PickedFolder, mainPath: String): ProjectRef.Saf {
        prefs.edit().putString(mainChoiceKey(folder), mainPath).apply()
        return folder.project(mainPath)
    }

    /**
     * A file from the file picker ([StorageContracts.OpenInkFile]) or from another app (a `VIEW`
     * intent, [ProjectIntents.fileToOpen]). Its permission is kept when it can be. When it lies in
     * a folder the app already holds a grant for, the result is the folder project; otherwise the
     * file alone, with what the UI needs to offer granting its folder.
     */
    suspend fun openFile(uri: Uri): FileOpening = try {
        withContext(io) { openFileBlocking(uri) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, "opening $uri failed", e)
        FileOpening.Failed(StorageProblem.of(e), e)
    }

    /**
     * The folder the user granted for the single-file project [single] (the tree picker started at
     * [FileOpening.Single.grantHint]): when it holds the file, the folder project with the file as
     * main. The session then closes [single] and opens the new ref; unsaved text carries over by
     * path (the main path stays the file's name).
     */
    suspend fun upgradeToFolder(single: ProjectRef.SingleDocument, treeUri: Uri): FolderUpgrade = try {
        withContext(io) { upgradeBlocking(single, treeUri) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, "granting the folder of ${single.mainPath} failed", e)
        FolderUpgrade.Failed(StorageProblem.of(e), e)
    }

    // ---- the projects folder and new projects ---------------------------------------------------------

    /** Makes the picked tree the folder for new projects; its permission is kept. */
    suspend fun setProjectsFolder(treeUri: Uri): StorageProblem? = try {
        val folder = withContext(io) { pickedFolder(treeUri) }
        prefs.edit()
            .putString(PREF_FOLDER_TREE, folder.treeUri)
            .putString(PREF_FOLDER_ROOT, folder.rootDocumentId)
            .putString(PREF_FOLDER_NAME, folder.displayName)
            .apply()
        folderState.value = folder
        synchronized(openCounts) { pendingGrants.remove(folder.treeUri) }
        releaseUnusedPermissions()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, "the projects folder $treeUri cannot be used", e)
        StorageProblem.of(e)
    }

    /** New projects go to the app's own storage again. */
    fun clearProjectsFolder() {
        prefs.edit().remove(PREF_FOLDER_TREE).remove(PREF_FOLDER_ROOT).remove(PREF_FOLDER_NAME).apply()
        folderState.value = null
        releaseUnusedPermissions()
    }

    /**
     * A new project named after [typedName] ([ProjectNames.sanitize]; a taken name gets a number):
     * `<projects folder>/<name>/<name>.ink` holding [mainText] (by default upstream's starter story), or an
     * internal project when no projects folder is set. The caller then [open]s the result.
     *
     * @throws IllegalArgumentException when nothing of [typedName] can be a folder name.
     * @throws IOException (typed as the stores' failures) when the folder cannot be written.
     */
    suspend fun createProject(typedName: String, mainText: String = NewProject.MAIN_TEXT): ProjectRef {
        val folder = folderState.value ?: return internalProjects.create(typedName, mainText)
        return createProjectIn(folder, typedName, mainText)
    }

    /**
     * A new project in the sub-folder `<name>` of [parent] (the projects folder, or a folder the
     * user picked that has no ink files yet: [FolderOpening.NoInkFiles]).
     */
    suspend fun createProjectIn(parent: PickedFolder, typedName: String, mainText: String = NewProject.MAIN_TEXT): ProjectRef.Saf {
        val clean = requireNotNull(ProjectNames.sanitize(typedName)) { "\"$typedName\" cannot be a project name" }
        val parentStore = folderStore(parent)
        val name = ProjectNames.unique(clean, parentStore.childNames())
        val main = ProjectNames.mainFileOf(name)
        parentStore.write("$name/$main", mainText)
        val rootId = parentStore.documentIdOf(name) ?: throw ProviderFailedException("the new folder $name cannot be found")
        return ProjectRef.Saf(parent.treeUri, rootId, main, name)
    }

    // ---- copying -------------------------------------------------------------------------------------

    /**
     * Copies the internal project [ref] into a new sub-folder of the picked tree [treeUri]. The
     * internal project stays; the caller can delete it once the copy is open.
     */
    suspend fun copyToFolder(ref: ProjectRef.Internal, treeUri: Uri): ProjectCopy {
        val parent = withContext(io) { pickedFolder(treeUri) }
        val parentStore = folderStore(parent)
        val name = ProjectNames.unique(ref.name, parentStore.childNames())
        val skipped = ProjectCopier.copy(internalProjects.store(ref), PrefixedStore(parentStore, name))
        val rootId = parentStore.documentIdOf(name) ?: throw ProviderFailedException("the copied folder $name cannot be found")
        synchronized(openCounts) { pendingGrants.remove(parent.treeUri) }
        return ProjectCopy(ProjectRef.Saf(parent.treeUri, rootId, ref.mainPath, name), skipped)
    }

    /** Copies any project into the app's own storage (a new internal project named after it). */
    suspend fun copyToInternal(ref: ProjectRef): ProjectCopy {
        val target = internalProjects.createEmpty(ref.displayName.removeSuffix(".ink"), ref.mainPath)
        val skipped = try {
            ProjectCopier.copy(storeFor(ref), internalProjects.store(target))
        } catch (e: Exception) {
            internalProjects.delete(target)
            throw e
        }
        return ProjectCopy(target, skipped)
    }

    // ---- permissions ---------------------------------------------------------------------------------

    /**
     * Gives back every persisted grant that neither a recent project, nor the projects folder, nor
     * an open project, nor a pick in progress refers to.
     */
    fun releaseUnusedPermissions() {
        permissions.releaseAllExcept(currentlyInUse())
    }

    private fun grantOf(ref: ProjectRef): String? = when (ref) {
        is ProjectRef.Saf -> ref.treeUri
        is ProjectRef.SingleDocument -> ref.documentUri
        is ProjectRef.Internal -> null
    }

    private fun isRememberable(ref: ProjectRef): Boolean = when (ref) {
        is ProjectRef.Internal -> true
        is ProjectRef.Saf -> permissions.holds(ref.treeUri)
        is ProjectRef.SingleDocument -> permissions.holds(ref.documentUri)
    }

    private fun takeGrant(uri: Uri): Boolean {
        val taken = permissions.take(uri)
        if (taken) synchronized(openCounts) { pendingGrants += uri.toString() }
        return taken
    }

    // ---- the blocking parts --------------------------------------------------------------------------

    private fun folderStore(folder: PickedFolder) = SafProjectStore(
        resolver, Uri.parse(folder.treeUri), folder.rootDocumentId, folder.displayName,
        permissionHeld = { permissions.holds(folder.treeUri) }, io = io,
    )

    private fun pickedFolder(treeUri: Uri): PickedFolder {
        require(DocumentsContract.isTreeUri(treeUri)) { "not a tree URI: $treeUri" }
        if (!takeGrant(treeUri)) Log.w(LOG_TAG, "the folder $treeUri was picked without a persistable permission")
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val root = SafDocuments.document(resolver, treeUri, rootId)
            ?: throw NoSuchFileException(treeUri.toString(), null, "the picked folder cannot be found")
        if (!root.isDirectory) throw ProviderFailedException("the picked tree is not a folder (${root.mimeType})")
        return PickedFolder(treeUri.toString(), rootId, root.name)
    }

    private suspend fun openPickedFolder(folder: PickedFolder): FolderOpening {
        val store = folderStore(folder)
        return try {
            val ink = store.list().map { it.path }.filter(MainFileCandidates::isInk)
            if (ink.none { '/' !in it }) return FolderOpening.NoInkFiles(folder)
            val texts = ink.associateWith { path ->
                try {
                    store.read(path)
                } catch (e: NotTextException) {
                    ""
                }
            }
            val candidates = MainFileCandidates.find(ink) { texts.getValue(it) }
            val remembered = prefs.getString(mainChoiceKey(folder), null)
            when {
                remembered != null && remembered in ink -> FolderOpening.Opened(folder.project(remembered))
                candidates.size == 1 -> FolderOpening.Opened(folder.project(candidates.single()))
                else -> FolderOpening.ChooseMain(folder, candidates)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FolderOpening.Failed(StorageProblem.of(e), e)
        }
    }

    private fun openFileBlocking(uri: Uri): FileOpening {
        val remembered = takeGrant(uri)
        val documentId = TreeLocator.documentIdOf(uri)
        val name = fileNameOf(uri, documentId)
        val bytes = DocumentIo.readBytes(resolver, uri, name)
        if (!DocumentIo.looksLikeText(bytes)) return FileOpening.NotText(name)

        if (documentId != null) {
            for (tree in grantedTreesOf(uri.authority)) {
                val parentId = TreeLocator.parentInTree(resolver, tree, documentId) ?: continue
                val parent = SafDocuments.document(resolver, tree, parentId) ?: continue
                val file = SafDocuments.document(resolver, tree, documentId) ?: continue
                if (!isFileName(file.name)) continue
                Log.i(LOG_TAG, "${file.name} lies in the granted tree $tree")
                // the tree's grant serves the project; the file's own grant is not needed
                synchronized(openCounts) {
                    pendingGrants.remove(uri.toString())
                    pendingGrants += tree.toString()
                }
                releaseUnusedPermissions()
                return FileOpening.InFolder(ProjectRef.Saf(tree.toString(), parentId, file.name, parent.name))
            }
        }
        return FileOpening.Single(
            ref = ProjectRef.SingleDocument(uri.toString(), name),
            writable = isWritable(uri),
            remembered = remembered,
            grantHint = TreeLocator.folderHint(uri),
        )
    }

    private fun upgradeBlocking(single: ProjectRef.SingleDocument, treeUri: Uri): FolderUpgrade {
        val folder = pickedFolder(treeUri)
        val documentUri = Uri.parse(single.documentUri)
        val documentId = TreeLocator.documentIdOf(documentUri)
        if (documentId != null && documentUri.authority == treeUri.authority) {
            val parentId = TreeLocator.parentInTree(resolver, treeUri, documentId)
            val file = parentId?.let { SafDocuments.document(resolver, treeUri, documentId) }
            if (parentId != null && file != null) {
                val parentName = SafDocuments.document(resolver, treeUri, parentId)?.name ?: folder.displayName
                return FolderUpgrade.Upgraded(ProjectRef.Saf(folder.treeUri, parentId, file.name, parentName))
            }
        }
        // Another provider's URI (a file manager's, the media store's) cannot be compared by id: the
        // file is recognised by its name directly in the picked folder.
        val children = SafDocuments.children(resolver, treeUri, folder.rootDocumentId)
            ?: throw ProviderFailedException("the picked folder could not be listed")
        val match = children.firstOrNull { it.isFile && it.name == single.mainPath } ?: return FolderUpgrade.NotContained
        return FolderUpgrade.Upgraded(folder.project(match.name))
    }

    private fun grantedTreesOf(authority: String?): List<Uri> =
        permissions.persisted().filter { DocumentsContract.isTreeUri(it) && it.authority == authority }

    /** The file's name: the provider's display name, else the URI's last segment, else "story.ink"; never with a `/`. */
    private fun fileNameOf(uri: Uri, documentId: String?): String {
        val fromProvider = (if (documentId != null) SafDocuments.query(resolver, uri)?.name else null)
            ?: OpenableInfo.query(resolver, uri)?.name
        val fromUri = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
        return sequenceOf(fromProvider, fromUri)
            .filterNotNull()
            .map { it.substringAfterLast('/').trim() }
            .firstOrNull { isFileName(it) && it.contains('.') }
            ?: DEFAULT_FILE_NAME
    }

    private fun isWritable(uri: Uri): Boolean {
        val doc = if (TreeLocator.documentIdOf(uri) != null) {
            try {
                SafDocuments.query(resolver, uri)
            } catch (e: SecurityException) {
                null
            }
        } else {
            null
        }
        if (doc != null && doc.refuses(Document.FLAG_SUPPORTS_WRITE)) return false
        return permissions.holds(uri.toString(), write = true) || writeGranted(uri)
    }

    private fun currentlyInUse(): Set<String> {
        val inUse = HashSet<String>()
        recents.entries.value.forEach { grantOf(it.ref)?.let(inUse::add) }
        folderState.value?.let { inUse += it.treeUri }
        synchronized(openCounts) {
            openCounts.values.forEach { (ref, _) -> grantOf(ref)?.let(inUse::add) }
            inUse += pendingGrants
        }
        return inUse
    }

    private fun loadProjectsFolder(): PickedFolder? {
        val tree = prefs.getString(PREF_FOLDER_TREE, null) ?: return null
        val root = prefs.getString(PREF_FOLDER_ROOT, null) ?: return null
        return PickedFolder(tree, root, prefs.getString(PREF_FOLDER_NAME, null).orEmpty())
    }

    private fun mainChoiceKey(folder: PickedFolder) = "main\u0000${folder.treeUri}\u0000${folder.rootDocumentId}"

    companion object {
        @Volatile private var instance: ProjectStorage? = null

        /** The process's instance. */
        fun from(context: Context): ProjectStorage = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(app: Context) = ProjectStorage(
            resolver = app.contentResolver,
            filesDir = app.filesDir,
            prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            io = Dispatchers.IO,
            clock = System::currentTimeMillis,
            directoryStoreFactory = { dir, name -> DirectoryProjectStore(dir, name) },
            writeGranted = { uri ->
                app.checkUriPermission(uri, Process.myPid(), Process.myUid(), android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
                    PackageManager.PERMISSION_GRANTED
            },
        )

        internal const val PREFS_NAME = "inky-storage"
        private const val PREF_FOLDER_TREE = "projectsFolder.tree"
        private const val PREF_FOLDER_ROOT = "projectsFolder.root"
        private const val PREF_FOLDER_NAME = "projectsFolder.name"

        /** The name a received file gets when neither its provider nor its URI tells one. */
        const val DEFAULT_FILE_NAME = "story.ink"
    }
}

/** Whether [name] can be a file name in a project: one path segment. */
private fun isFileName(name: String): Boolean = '/' !in name && ProjectPaths.isValid(name)
