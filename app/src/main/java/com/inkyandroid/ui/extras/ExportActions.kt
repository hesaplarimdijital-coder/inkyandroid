package com.inkyandroid.ui.extras

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.inkyandroid.R
import com.inkyandroid.core.live.ExportResult
import com.inkyandroid.core.engine.InkExport
import com.inkyandroid.session.ProjectSession
import com.inkyandroid.storage.ExportFileRequest
import com.inkyandroid.storage.ProjectStorage
import com.inkyandroid.storage.StorageContracts
import com.inkyandroid.storage.StorageProblem
import desktopkit.components.BannerTone
import desktopkit.components.ToastState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Upstream's three exports, each run end to end: compile, choose the target, write, report.
 * Obtained from [rememberExportActions].
 */
interface ExportActions {
    /** "Export to JSON…": the compiled story as ink's runtime JSON. */
    fun exportJson()

    /** "Export story.js only…": `var storyContent = <json>;`. */
    fun exportStoryJs()

    /** "Export for web…": a folder with the web player (index.html, the story's .js, ink.js, main.js, style.css). */
    fun exportForWeb()

    /** An export is running; the commands are disabled meanwhile. Compose state. */
    val busy: Boolean
}

/**
 * The exports of the project open in [session]: each compiles the project (refusing, as upstream does,
 * when the ink has errors), asks for the target with the system's picker under upstream's suggested
 * name, writes through the app's storage and says how it went in a toast of [toasts].
 */
@Composable
fun rememberExportActions(session: ProjectSession, toasts: ToastState): ExportActions {
    val context = LocalContext.current
    val source = remember(session) { SessionExportSource(session) }
    val writer = remember(context) { StorageExportWriting(ProjectStorage.from(context)) }
    val places = remember(context) { ExportPlaces.of(context) }
    return rememberExportActions(source, toasts, writer, places)
}

/**
 * Where the last export went: the next one's picker starts there, whichever kind it is (upstream
 * remembers one export path for all three). Kept across restarts.
 */
internal class ExportPlaces(private val prefs: SharedPreferences) {
    var last: Uri?
        get() = prefs.getString(KEY_LAST, null)?.let(Uri::parse)
        set(value) {
            prefs.edit().putString(KEY_LAST, value?.toString()).apply()
        }

    companion object {
        const val FILE = "inky-exports"
        private const val KEY_LAST = "last"

        fun of(context: Context) = ExportPlaces(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}

/** What an export needs from the open project. */
internal interface ExportSource {
    /** The main file's root-relative path: the names of the exports come from it. */
    val mainPath: String

    /** The main file's `# title:` tag, the web page's title; null without one. */
    fun titleTag(): String?

    /** The compiled story's JSON, or why there is none. */
    suspend fun exportJson(): ExportResult
}

/** Where exports are written: the storage's export writer. */
internal interface ExportWriting {
    /** Writes [text] into the picked document; returns its name as stored. */
    suspend fun writeFile(uri: Uri, text: String): String

    /** Writes [files] (relative name, text) into a folder [folderName] of the picked tree; returns the folder's name. */
    suspend fun writeFolder(treeUri: Uri, folderName: String, files: List<Pair<String, String>>): String
}

private class SessionExportSource(private val session: ProjectSession) : ExportSource {
    override val mainPath: String get() = session.project.mainPath
    override fun titleTag(): String? = session.project.state.value.main.symbols?.dictionaryTags?.get("title")
    override suspend fun exportJson(): ExportResult =
        session.live.session?.exportJson() ?: ExportResult.Crashed("no live session")
}

private class StorageExportWriting(private val storage: ProjectStorage) : ExportWriting {
    override suspend fun writeFile(uri: Uri, text: String): String = storage.exports.writeFile(uri, text)
    override suspend fun writeFolder(treeUri: Uri, folderName: String, files: List<Pair<String, String>>): String =
        storage.exports.writeFolder(treeUri, folderName, files)
}

/** The three exports over a [source] and a [writer]; the pickers are the storage's contracts. */
@Composable
internal fun rememberExportActions(source: ExportSource, toasts: ToastState, writer: ExportWriting, places: ExportPlaces): ExportActions {
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    val flow = remember(source, toasts, writer, places) { ExportFlow(source, toasts, writer, places, scope) }
    flow.resources = resources
    val fileTarget = rememberLauncherForActivityResult(StorageContracts.CreateExportFile()) { uri -> flow.fileChosen(uri) }
    val folderTarget = rememberLauncherForActivityResult(StorageContracts.PickFolder()) { tree -> flow.folderChosen(tree) }
    val latestFile by rememberUpdatedState(fileTarget)
    val latestFolder by rememberUpdatedState(folderTarget)
    flow.chooseFile = { request -> latestFile.launch(request) }
    flow.chooseFolder = { latestFolder.launch(places.last ?: StorageContracts.DOCUMENTS_HINT) }
    return flow
}

/** What is being exported while the user chooses where to. */
private sealed interface PendingExport {
    data class File(val text: String) : PendingExport
    data class Web(val folderName: String, val files: List<Pair<String, String>>) : PendingExport
}

private class ExportFlow(
    private val source: ExportSource,
    private val toasts: ToastState,
    private val writer: ExportWriting,
    private val places: ExportPlaces,
    private val scope: CoroutineScope,
) : ExportActions {
    lateinit var resources: Resources
    var chooseFile: (ExportFileRequest) -> Unit = {}
    var chooseFolder: () -> Unit = {}

    override var busy: Boolean by mutableStateOf(false)
        private set

    private var pending: PendingExport? = null

    override fun exportJson() = start { json ->
        pending = PendingExport.File(json)
        chooseFile(ExportFileRequest.json(ExportNames.json(source.mainPath), places.last))
    }

    override fun exportStoryJs() = start { json ->
        val script = withContext(Dispatchers.Default) { InkExport.storyJs(json) }
        pending = PendingExport.File(script)
        chooseFile(ExportFileRequest.javascript(InkExport.jsFileName(source.mainPath), places.last))
    }

    override fun exportForWeb() = start { json ->
        val folderName = ExportNames.webFolder(source.mainPath)
        val title = InkExport.webTitle(source.titleTag(), folderName)
        // the template and inkjs are read from :core's resources: not on the main thread
        val files = withContext(Dispatchers.Default) { InkExport.webPlayer(json, source.mainPath, title).map { it.name to it.content } }
        pending = PendingExport.Web(folderName, files)
        chooseFolder()
    }

    /** Compiles, then hands the JSON to [then] (which opens a picker), or says why there is nothing to export. */
    private fun start(then: suspend (String) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                when (val result = source.exportJson()) {
                    is ExportResult.Json -> {
                        then(result.json)
                        // busy until the picker answers
                        return@launch
                    }
                    is ExportResult.HasErrors -> failed(resources.getString(R.string.export_has_errors))
                    is ExportResult.Crashed -> failed(resources.getString(R.string.export_unexpected_error))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failed(failure.message ?: resources.getString(R.string.export_unexpected_error))
            }
            busy = false
        }
    }

    fun fileChosen(uri: Uri?) {
        val export = pending as? PendingExport.File
        pending = null
        if (uri == null || export == null) {
            busy = false
            return
        }
        places.last = uri
        write { resources.getString(R.string.export_done_file, writer.writeFile(uri, export.text)) }
    }

    fun folderChosen(tree: Uri?) {
        val export = pending as? PendingExport.Web
        pending = null
        if (tree == null || export == null) {
            busy = false
            return
        }
        places.last = tree
        write { resources.getString(R.string.export_done_web, writer.writeFolder(tree, export.folderName, export.files)) }
    }

    private fun write(work: suspend () -> String) {
        scope.launch {
            try {
                val done = work()
                busy = false
                toasts.show(done, tone = BannerTone.Success)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                busy = false
                failed(StorageProblem.of(failure).message(resources))
            }
        }
    }

    private suspend fun failed(reason: String) {
        busy = false
        toasts.show(resources.getString(R.string.export_failed, reason), tone = BannerTone.Error)
    }
}

/** The names upstream suggests for exports, from the main file's path. */
internal object ExportNames {
    /** `story.ink` → `story.json` (upstream means this; its dialog actually keeps `.ink`, a bug not copied). */
    fun json(mainPath: String): String = baseName(mainPath) + ".json"

    /** The web export's folder: the main file's name without `.ink`, as upstream's dialog suggests it. */
    fun webFolder(mainPath: String): String = baseName(mainPath)

    private fun baseName(mainPath: String): String = mainPath.substringAfterLast('/').removeSuffix(".ink").ifEmpty { "story" }
}
