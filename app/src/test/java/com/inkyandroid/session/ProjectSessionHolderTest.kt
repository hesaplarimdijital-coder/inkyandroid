package com.inkyandroid.session

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkyandroid.core.engine.InkSources
import com.inkyandroid.core.project.DirectoryProjectStore
import com.inkyandroid.core.project.FileDraftStore
import com.inkyandroid.core.project.ProjectStore
import com.inkyandroid.core.project.StoredFile
import com.inkyandroid.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.NoSuchFileException

/**
 * The window's holder, the way the activity drives it: a project reopened after the death of the
 * process, its storage given back at the right moment, openings that fail, the same project from
 * another place.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ProjectSessionHolderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scheduler = TestCoroutineScheduler()
    private val main = StandardTestDispatcher(scheduler)
    private val folders = File(context.cacheDir, "holder-test").apply { deleteRecursively(); mkdirs() }
    private val draftsDirectory = File(context.filesDir, "holder-drafts").apply { deleteRecursively() }

    /** What storage and the window did, in order: "open ref", "write main.ink", "close ref". */
    private val log = ArrayList<String>()
    private val lifetimes = ArrayList<Job>()
    private val editors = LinkedHashMap<String, FakeEditorBuffer>()

    private val story = "Once upon a time\n"
    private val written = "Once upon a time\nBir varmış, bir yokmuş.\n"

    @Before
    fun mainThreadOnVirtualTime() = Dispatchers.setMain(main)

    @After
    fun mainThreadBack() {
        lifetimes.forEach(Job::cancel)
        Dispatchers.resetMain()
    }

    private fun folder(name: String) = File(folders, name).apply { mkdirs() }

    /** A store that writes [log] lines for its writes. */
    private inner class LoggedStore(private val real: ProjectStore) : ProjectStore by real {
        override suspend fun write(path: String, text: String): StoredFile = real.write(path, text).also { log.add("write $path") }
    }

    /** The storage package as the holder sees it: each reference is a folder with its main file. */
    private inner class FakeOpener(private val places: Map<String, File>, private val singles: Set<String>) : ProjectOpener {
        override suspend fun open(reference: String): OpenedProject {
            log.add("open $reference")
            val root = places[reference] ?: throw NoSuchFileException(reference)
            return OpenedProject(LoggedStore(DirectoryProjectStore(root, io = main)), "main.ink", singleFile = reference in singles)
        }

        override fun close(reference: String) {
            log.add("close $reference")
        }
    }

    private fun settings() = AppSettings(context.getSharedPreferences("holder-test", Context.MODE_PRIVATE))

    /** One process's environment; [kill] ends every session it made, as the system ends a process. */
    private fun environment(places: Map<String, File>, settings: AppSettings = settings(), singles: Set<String> = emptySet()): SessionEnvironment {
        val lifetime = Job().also(lifetimes::add)
        return SessionEnvironment(
            settings = settings,
            opener = FakeOpener(places, singles),
            editors = { path, text -> FakeEditorBuffer(text).also { editors[path] = it } },
            live = { _: CoroutineScope, _: () -> InkSources -> FakeLiveLink() },
            draftsDirectory = draftsDirectory,
            io = main,
            parseContext = main,
            sessionContext = main + lifetime,
        )
    }

    private fun kill(holder: ProjectSessionHolder) {
        lifetimes.forEach(Job::cancel)
        lifetimes.clear()
        holder.viewModelScope.cancel()
    }

    private fun pass(millis: Long = 0) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    private fun ProjectSessionHolder.open(): ProjectSession = checkNotNull(session) { "no project open: ${project.value}" }

    /** The editor of [path] in the holder's session, opened if it was not (the session creates editors on first use). */
    private fun ProjectSessionHolder.editor(path: String): FakeEditorBuffer {
        checkNotNull(open().document(path)) { "$path is not in the project" }
        return editors.getValue(path)
    }

    private fun test(block: suspend () -> Unit) = runTest(main) { block() }

    // ------------------------------------------------------------------ the death of the process

    @Test
    fun `a window that died with the process comes back to its project, its file and the text that was typed`() = test {
        val root = folder("story").apply { File(this, "main.ink").writeText("INCLUDE a.ink\n$story"); File(this, "a.ink").writeText("A\n") }
        val first = ProjectSessionHolder(environment(mapOf("ref" to root)).also { it.settings.updateAutosave(false) })
        first.restore(savedReference = null)
        assertEquals("a first start opens nothing", WindowProject.None, first.project.value)
        first.open("ref")
        pass()
        val session = first.open()
        session.show("a.ink")
        first.editor("a.ink").type("A, and a paragraph that was never saved\n")
        pass(ProjectSession.DRAFT_DELAY_MILLIS)

        kill(first)
        val second = ProjectSessionHolder(environment(mapOf("ref" to root), settings()))
        second.restore(savedReference = null)
        pass()

        val restored = second.open()
        assertEquals("a.ink", restored.state.value.activePath)
        assertEquals("A, and a paragraph that was never saved\n", restored.project.text("a.ink").toString())
        assertEquals(listOf("a.ink"), restored.state.value.restoredDrafts?.restored)
        assertEquals("A\n", File(root, "a.ink").readText())
    }

    @Test
    fun `the project of the saved instance state comes first`() = test {
        val one = folder("one").apply { File(this, "main.ink").writeText(story) }
        val two = folder("two").apply { File(this, "main.ink").writeText(story) }
        val first = ProjectSessionHolder(environment(mapOf("one" to one, "two" to two)))
        first.open("one")
        pass()
        kill(first)

        val second = ProjectSessionHolder(environment(mapOf("one" to one, "two" to two), settings()))
        second.restore(savedReference = "two")
        pass()

        assertEquals("two", second.open().reference)
    }

    @Test
    fun `restore happens once, so a new onCreate does not reopen anything`() = test {
        val root = folder("story").apply { File(this, "main.ink").writeText(story) }
        val holder = ProjectSessionHolder(environment(mapOf("ref" to root)))
        holder.open("ref")
        pass()
        val session = holder.open()

        holder.restore(savedReference = "ref")
        pass()

        assertSame(session, holder.session)
    }

    // ------------------------------------------------------------------ storage given back

    @Test
    fun `a window finished for good saves once more and only then gives its project's storage back`() = test {
        val root = folder("story").apply { File(this, "main.ink").writeText(story) }
        val store = ViewModelStore()
        val holder = ViewModelProvider(store, ProjectSessionHolder.Factory(environment(mapOf("ref" to root))))[ProjectSessionHolder::class.java]
        holder.open("ref")
        pass()
        holder.editor("main.ink").type(written)

        store.clear()
        pass()

        assertEquals(listOf("open ref", "write main.ink", "close ref"), log)
        assertEquals(written, File(root, "main.ink").readText())
    }

    @Test
    fun `opening another project ends the first, which gives its storage back after its last save`() = test {
        val one = folder("one").apply { File(this, "main.ink").writeText(story) }
        val two = folder("two").apply { File(this, "main.ink").writeText(story) }
        val holder = ProjectSessionHolder(environment(mapOf("one" to one, "two" to two)))
        holder.open("one")
        pass()
        holder.editor("main.ink").type(written)

        holder.open("two")
        pass()

        assertEquals("two", holder.open().reference)
        assertTrue(log.indexOf("write main.ink") < log.indexOf("close one"))
        assertEquals(written, File(one, "main.ink").readText())
    }

    @Test
    fun `closing the project gives its storage back and the next start opens nothing`() = test {
        val root = folder("story").apply { File(this, "main.ink").writeText(story) }
        val holder = ProjectSessionHolder(environment(mapOf("ref" to root)))
        holder.open("ref")
        pass()

        assertTrue(holder.open().requestClose())
        holder.closed()
        pass()

        assertEquals(WindowProject.None, holder.project.value)
        assertEquals(listOf("open ref", "close ref"), log)
        val next = ProjectSessionHolder(environment(mapOf("ref" to root), settings()))
        next.restore(savedReference = null)
        pass()
        assertEquals(WindowProject.None, next.project.value)
    }

    // ------------------------------------------------------------------ openings that fail

    @Test
    fun `a project whose main file is gone fails with the reason, and storage is not left counting it as open`() = test {
        val root = folder("empty")
        val holder = ProjectSessionHolder(environment(mapOf("ref" to root)))

        holder.open("ref")
        pass()

        val failed = holder.project.value as WindowProject.Failed
        assertEquals("ref", failed.reference)
        assertTrue(failed.cause is NoSuchFileException)
        assertEquals(listOf("open ref", "close ref"), log)
        assertEquals("ref", holder.reference)
    }

    @Test
    fun `a project that cannot be reached says which unsaved texts are kept for when it can`() = test {
        val root = folder("story").apply { File(this, "main.ink").writeText("INCLUDE a.ink\n$story"); File(this, "a.ink").writeText("A\n") }
        val first = ProjectSessionHolder(environment(mapOf("ref" to root)).also { it.settings.updateAutosave(false) })
        first.open("ref")
        pass()
        first.editor("a.ink").type("A, never saved\n")
        pass(ProjectSession.DRAFT_DELAY_MILLIS)
        kill(first)

        // the next start: the folder is not there any more (the permission was withdrawn)
        val unreachable = environment(places = emptyMap(), settings = settings())
        val second = ProjectSessionHolder(unreachable)
        second.restore(savedReference = null)
        pass()

        val failed = second.project.value as WindowProject.Failed
        assertEquals(listOf("a.ink"), failed.keptDrafts)
        assertEquals("the text waits in the drafts", "A, never saved\n", FileDraftStore(unreachable.draftsFolder("ref"), main).load().single().text)
    }

    @Test
    fun `a main file deleted while the app was dead opens with its unsaved text`() = test {
        val root = folder("story").apply { File(this, "main.ink").writeText(story) }
        val first = ProjectSessionHolder(environment(mapOf("ref" to root)).also { it.settings.updateAutosave(false) })
        first.open("ref")
        pass()
        first.editor("main.ink").type(written)
        pass(ProjectSession.DRAFT_DELAY_MILLIS)
        kill(first)
        File(root, "main.ink").delete()

        val second = ProjectSessionHolder(environment(mapOf("ref" to root), settings()))
        second.restore(savedReference = null)
        pass()

        val session = second.open()
        assertEquals(written, session.project.text("main.ink").toString())
        assertTrue(!session.project.state.value.main.isOnStorage)
        assertTrue(session.saveAll().isComplete)
        assertEquals(written, File(root, "main.ink").readText())
    }

    @Test
    fun `a main file that is not text fails to open with the failure itself, and nothing crashes`() = test {
        val root = folder("story").apply { File(this, "main.ink").writeBytes(byteArrayOf(0x4F, 0xC5.toByte())) }
        val holder = ProjectSessionHolder(environment(mapOf("ref" to root)))

        holder.open("ref")
        pass()

        assertTrue((holder.project.value as WindowProject.Failed).cause is CharacterCodingException)
    }

    @Test
    fun `an included file that is not text is a load failure of that file, and the project opens`() = test {
        val root = folder("story").apply {
            File(this, "main.ink").writeText("INCLUDE notes.ink\n$story")
            File(this, "notes.ink").writeBytes(byteArrayOf(0x4F, 0xC5.toByte()))
        }
        val holder = ProjectSessionHolder(environment(mapOf("ref" to root)))

        holder.open("ref")
        pass()

        val project = holder.open().project.state.value
        assertTrue(project.loadFailures["notes.ink"] is CharacterCodingException)
        assertNotNull(project.loadErrors["notes.ink"])
        assertNull(project.file("notes.ink"))
    }

    // ------------------------------------------------------------------ the same project from another place

    @Test
    fun `switching to the same project in another place carries the unsaved text along and moves its drafts`() = test {
        val single = folder("single").apply { File(this, "main.ink").writeText(story) }
        val granted = folder("granted").apply { File(this, "main.ink").writeText(story) }
        val environment = environment(mapOf("single" to single, "folder" to granted)).also { it.settings.updateAutosave(false) }
        val holder = ProjectSessionHolder(environment)
        holder.open("single")
        pass()
        holder.editor("main.ink").type(written)
        pass(ProjectSession.DRAFT_DELAY_MILLIS)

        holder.switchTo("folder")
        pass()

        val moved = holder.open()
        assertEquals("folder", moved.reference)
        assertEquals(written, moved.project.text("main.ink").toString())
        assertTrue(moved.project.state.value.file("main.ink")!!.hasUnsavedChanges)
        assertEquals(story, File(single, "main.ink").readText())
        assertEquals(listOf("open single", "close single", "open folder"), log)
        assertEquals(emptyList<String>(), FileDraftStore(environment.draftsFolder("single"), main).load().map { it.path })
        assertEquals(listOf(written), FileDraftStore(environment.draftsFolder("folder"), main).load().map { it.text })
    }

    @Test
    fun `when the unsaved text cannot be carried over the window keeps its project and says why`() = test {
        val single = folder("single").apply { File(this, "main.ink").writeText(story) }
        val granted = folder("granted").apply { File(this, "main.ink").writeText(story) }
        val environment = environment(mapOf("single" to single, "folder" to granted)).also { it.settings.updateAutosave(false) }
        val holder = ProjectSessionHolder(environment)
        holder.open("single")
        pass()
        val session = holder.open()
        holder.editor("main.ink").type(written)
        // where the new place's drafts would go, a file stands in the way
        environment.draftsFolder("folder").apply { parentFile?.mkdirs(); writeText("not a folder") }

        holder.switchTo("folder")
        pass()

        assertSame(session, holder.session)
        assertNotNull(session.state.value.draftProblem)
        assertEquals(written, session.project.text("main.ink").toString())
        assertEquals(listOf("open single"), log)
    }

    // ------------------------------------------------------------------ one window per project

    @Test
    fun `a project open in one window is not opened in a second, which says why`() = test {
        val root = folder("story").apply { File(this, "main.ink").writeText(story) }
        val environment = environment(mapOf("ref" to root))
        val first = ProjectSessionHolder(environment)
        val second = ProjectSessionHolder(environment)
        first.open("ref")
        pass()

        second.open("ref")
        pass()

        val failed = second.project.value as WindowProject.Failed
        assertTrue(failed.cause is ProjectOpenElsewhereException)
        assertEquals(listOf("open ref"), log)
    }

    @Test
    fun `opening a project another window has open keeps this window's own project, and says why`() = test {
        val one = folder("one").apply { File(this, "main.ink").writeText(story) }
        val two = folder("two").apply { File(this, "main.ink").writeText(story) }
        val environment = environment(mapOf("one" to one, "two" to two))
        val first = ProjectSessionHolder(environment)
        val second = ProjectSessionHolder(environment)
        first.open("one")
        second.open("two")
        pass()
        val kept = second.open()

        second.open("one")
        pass()

        assertSame(kept, second.session)
        assertTrue(second.declined.value is ProjectOpenElsewhereException)
        assertEquals("nothing was ended or given back", listOf("open one", "open two"), log)
        second.dismissDeclined()
        assertNull(second.declined.value)
    }

    @Test
    fun `a switch to a project another window has open keeps the window's project working, and says why`() = test {
        val single = folder("single").apply { File(this, "main.ink").writeText(story) }
        val granted = folder("granted").apply { File(this, "main.ink").writeText(story) }
        val environment = environment(mapOf("single" to single, "folder" to granted)).also { it.settings.updateAutosave(false) }
        val other = ProjectSessionHolder(environment)
        other.open("folder")
        val holder = ProjectSessionHolder(environment)
        holder.open("single")
        pass()
        val session = holder.open()
        holder.editor("main.ink").type(written)

        holder.switchTo("folder")
        pass()

        assertSame(session, holder.session)
        assertTrue(holder.declined.value is ProjectOpenElsewhereException)
        assertTrue("the session goes on", session.project.state.value.hasUnsavedChanges)
        pass(ProjectSession.DRAFT_DELAY_MILLIS)
        assertEquals(listOf(written), FileDraftStore(environment.draftsFolder("single"), main).load().map { it.text })
    }

    @Test
    fun `after a switch to the same project in another place each file opens where it was left`() = test {
        val single = folder("single").apply { File(this, "main.ink").writeText("line\n".repeat(30)) }
        val granted = folder("granted").apply { File(this, "main.ink").writeText("line\n".repeat(30)) }
        val holder = ProjectSessionHolder(environment(mapOf("single" to single, "folder" to granted)))
        holder.open("single")
        pass()
        holder.editor("main.ink").cursor = TextPosition(12, 3)

        holder.switchTo("folder")
        pass()

        assertEquals("folder", holder.open().reference)
        assertEquals(TextPosition(12, 3), holder.editor("main.ink").cursor)
    }

    @Test
    fun `reopening the open project keeps it this window's`() = test {
        val root = folder("story").apply { File(this, "main.ink").writeText(story) }
        val environment = environment(mapOf("ref" to root))
        val holder = ProjectSessionHolder(environment)
        holder.open("ref")
        pass()

        holder.open("ref")
        pass()
        val other = ProjectSessionHolder(environment)
        other.open("ref")
        pass()

        assertEquals("ref", holder.open().reference)
        assertTrue((other.project.value as WindowProject.Failed).cause is ProjectOpenElsewhereException)
    }

    @Test
    fun `a deleted project's drafts are forgotten once its last flush is done`() = test {
        val root = folder("story").apply { File(this, "main.ink").writeText(story) }
        val environment = environment(mapOf("ref" to root)).also { it.settings.updateAutosave(false) }
        val holder = ProjectSessionHolder(environment)
        holder.open("ref")
        pass()
        holder.editor("main.ink").type(written)

        val last = holder.closed()
        pass()
        assertTrue(last!!.isCompleted)
        assertEquals("the last flush wrote the draft", 1, FileDraftStore(environment.draftsFolder("ref"), main).load().size)
        environment.forgetDrafts("ref")

        assertTrue(!environment.draftsFolder("ref").exists())
    }

    @Test
    fun `a new window beside the one that has the last project open starts without a project`() = test {
        val root = folder("story").apply { File(this, "main.ink").writeText(story) }
        val environment = environment(mapOf("ref" to root))
        val first = ProjectSessionHolder(environment)
        first.open("ref")
        pass()

        val second = ProjectSessionHolder(environment)
        second.restore(savedReference = null)
        pass()

        assertEquals(WindowProject.None, second.project.value)
    }

    @Test
    fun `once the first window has closed the project, another window opens it with the drafts the first one left`() = test {
        val root = folder("story").apply { File(this, "main.ink").writeText(story) }
        val environment = environment(mapOf("ref" to root)).also { it.settings.updateAutosave(false) }
        val first = ProjectSessionHolder(environment)
        val second = ProjectSessionHolder(environment)
        first.open("ref")
        pass()
        first.editor("main.ink").type(written)

        // the window is finished for good (its unsaved text stays in the drafts), then the other opens the project
        first.closed()
        second.open("ref")
        pass()

        assertEquals(written, second.open().project.text("main.ink").toString())
    }

    @Test
    fun `opening the open project again in the same window waits for its last drafts`() = test {
        val root = folder("story").apply { File(this, "main.ink").writeText(story) }
        val holder = ProjectSessionHolder(environment(mapOf("ref" to root)).also { it.settings.updateAutosave(false) })
        holder.open("ref")
        pass()
        holder.editor("main.ink").type(written)

        // within the second after typing: no draft yet
        holder.open("ref")
        pass()

        assertEquals(written, holder.open().project.text("main.ink").toString())
    }

    @Test
    fun `a single file opened without its folder is known as one, also after the process died`() = test {
        val root = folder("single").apply { File(this, "main.ink").writeText(story) }
        val first = ProjectSessionHolder(environment(mapOf("single" to root), singles = setOf("single")))
        first.open("single")
        pass()
        assertTrue(first.open().isSingleFile)
        kill(first)

        val second = ProjectSessionHolder(environment(mapOf("single" to root), settings(), singles = setOf("single")))
        second.restore(savedReference = null)
        pass()

        assertTrue(second.open().isSingleFile)
    }

    // ------------------------------------------------------------------ the activity's hooks

    @Test
    fun `the window gaining focus looks at storage, and going to the background writes the drafts`() = test {
        val root = folder("story").apply { File(this, "main.ink").writeText("INCLUDE a.ink\n$story"); File(this, "a.ink").writeText("A\n") }
        val environment = environment(mapOf("ref" to root)).also { it.settings.updateAutosave(false) }
        val holder = ProjectSessionHolder(environment)
        holder.open("ref")
        pass()
        val a = holder.editor("a.ink")

        File(root, "a.ink").apply { writeText("A, changed on the computer\n"); setLastModified(lastModified() + 5_000) }
        holder.onWindowFocusGained()
        pass()
        assertEquals("A, changed on the computer\n", a.text.toString())

        holder.editor("main.ink").type(written)
        holder.onBackground()
        pass()
        assertEquals(listOf(written), FileDraftStore(environment.draftsFolder("ref"), main).load().map { it.text })
    }
}
