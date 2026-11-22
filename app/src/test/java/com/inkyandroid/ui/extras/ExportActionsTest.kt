package com.inkyandroid.ui.extras

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkyandroid.core.engine.InkIssue
import com.inkyandroid.core.live.ExportResult
import com.inkyandroid.storage.ExportFileRequest
import com.inkyandroid.storage.PermissionLostException
import desktopkit.components.ToastState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/** The three exports end to end, with a scripted project, picker and writer. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "tr")
class ExportActionsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class FakeSource(var result: ExportResult, override val mainPath: String = "tales/night.ink", val title: String? = null) : ExportSource {
        var compiles = 0
        override fun titleTag(): String? = title
        override suspend fun exportJson(): ExportResult = result.also { compiles++ }
    }

    private class FakeWriter : ExportWriting {
        val files = HashMap<Uri, String>()
        val folders = HashMap<String, List<Pair<String, String>>>()
        var failure: IOException? = null
        override suspend fun writeFile(uri: Uri, text: String): String {
            failure?.let { throw it }
            files[uri] = text
            return uri.lastPathSegment!!
        }
        override suspend fun writeFolder(treeUri: Uri, folderName: String, files: List<Pair<String, String>>): String {
            failure?.let { throw it }
            folders[folderName] = files
            return folderName
        }
    }

    /** The system's pickers: remembers what was asked and answers with [answer] (null: the user backed out). */
    private class Pickers : ActivityResultRegistry() {
        var answer: Uri? = null
        val asked = ArrayList<Any?>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            asked += input
            dispatchResult(requestCode, answer)
        }
    }

    private val json = """{"inkVersion":21,"root":[["^Hello","\n",["done",{"#n":"g-0"}],null],"done",null],"listDefs":{}}"""
    private val source = FakeSource(ExportResult.Json(json))
    private val writer = FakeWriter()
    private val pickers = Pickers()
    private val toasts = ToastState()
    private lateinit var actions: ExportActions

    private fun launch() {
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = pickers
        }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                actions = rememberExportActions(source, toasts, writer)
            }
        }
        compose.waitForIdle()
    }

    private fun run(action: ExportActions.() -> Unit) {
        compose.runOnUiThread { actions.action() }
        compose.waitForIdle()
    }

    private fun toast(): String? = toasts.current?.message

    @Test
    fun jsonIsWrittenWhereTheUserChose() {
        pickers.answer = Uri.parse("content://docs/document/night.json")
        launch()
        run { exportJson() }
        val request = pickers.asked.single() as ExportFileRequest
        assertEquals("night.json", request.suggestedName)
        assertEquals(json, writer.files[pickers.answer])
        assertEquals("night.json olarak dışa aktarıldı", toast())
        assertFalse(actions.busy)
    }

    @Test
    fun storyJsIsTheJsonAsAScript() {
        pickers.answer = Uri.parse("content://docs/document/night.js")
        launch()
        run { exportStoryJs() }
        assertEquals("night.js", (pickers.asked.single() as ExportFileRequest).suggestedName)
        assertEquals("var storyContent = $json;", writer.files[pickers.answer])
    }

    @Test
    fun theWebVersionIsAFolderOfThePlayer() {
        pickers.answer = Uri.parse("content://docs/tree/primary%3ADocuments")
        launch()
        run { exportForWeb() }
        val files = writer.folders.getValue("night").toMap()
        assertEquals(setOf("night.js", "index.html", "ink.js", "style.css", "main.js"), files.keys)
        // no title tag: the folder's name is the page's title
        assertTrue(files.getValue("index.html").contains("<title>night</title>"))
        assertTrue(files.getValue("index.html").contains("night.js"))
        assertEquals("Web sürümü night klasöründe", toast())
    }

    @Test
    fun inkWithErrorsIsNotExported() {
        source.result = ExportResult.HasErrors(emptyList<InkIssue>())
        launch()
        run { exportJson() }
        assertTrue(pickers.asked.isEmpty())
        assertEquals("Dışa aktarılamadı: Ink metninde hatalar var. Dışa aktarmadan önce onları düzeltin.", toast())
        assertFalse(actions.busy)
    }

    @Test
    fun backingOutOfThePickerWritesNothing() {
        pickers.answer = null
        launch()
        run { exportJson() }
        assertTrue(writer.files.isEmpty())
        assertNull(toast())
        assertFalse(actions.busy)
    }

    @Test
    fun aFailedWriteSaysWhy() {
        pickers.answer = Uri.parse("content://docs/document/night.json")
        writer.failure = PermissionLostException("gone")
        launch()
        run { exportJson() }
        assertTrue(toast()!!.startsWith("Dışa aktarılamadı: "))
        assertFalse(actions.busy)
    }
}
