package com.inkyandroid.core.project

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A scripted store in memory: the tests play the outside world through [put], [remove] and [touch],
 * make operations fail or take time, and read back what the project did in [log].
 */
class MemoryStore(override val displayName: String = "memory") : ProjectStore {

    private class Entry(var text: String, var modified: Long)

    private val entries = LinkedHashMap<String, Entry>()
    private var clock = 1_000L

    /** Every call the project made, as `"write main.ink"`, `"read a.ink"`, `"list"`, `"rename a.ink -> b.ink"`. */
    val log = ArrayList<String>()

    /** Returns the error an operation (`"read"`, `"write"`, `"list"`, `"stat"`, `"delete"`, `"rename"`) on a path fails with, or null. */
    var failure: (operation: String, path: String) -> IOException? = { _, _ -> null }

    /** How long a write takes, in virtual time. */
    var writeDelayMillis = 0L

    /** False for a provider that does not tell modification times: every file reports 0. */
    var reportsTimes = true

    /** The paths written, in order. */
    val writes: List<String> get() = log.filter { it.startsWith("write ") }.map { it.removePrefix("write ") }

    // ---- the outside world

    /** Someone outside the app creates or changes [path]. */
    fun put(path: String, text: String) {
        entries[path] = Entry(text, ++clock)
    }

    /** Someone outside the app deletes [path]. */
    fun remove(path: String) {
        entries.remove(path)
    }

    /** The file's time stamp changes without its content. */
    fun touch(path: String) {
        entries.getValue(path).modified = ++clock
    }

    fun textOf(path: String): String? = entries[path]?.text

    val paths: Set<String> get() = entries.keys.toSet()

    // ---- ProjectStore

    override suspend fun list(): List<StoredFile> {
        called("list", "")
        return entries.map { (path, entry) -> describe(path, entry) }
    }

    override suspend fun read(path: String): String {
        called("read", path)
        return entries[path]?.text ?: throw NoSuchFileException(path)
    }

    override suspend fun write(path: String, text: String): StoredFile {
        called("write", path)
        delay(writeDelayMillis)
        val entry = Entry(text, ++clock)
        entries[path] = entry
        return describe(path, entry)
    }

    override suspend fun delete(path: String) {
        called("delete", path)
        entries.remove(path) ?: throw NoSuchFileException(path)
    }

    override suspend fun rename(from: String, to: String): StoredFile {
        log.add("rename $from -> $to")
        failure("rename", from)?.let { throw it }
        if (entries.containsKey(to)) throw FileAlreadyExistsException(to)
        val entry = entries.remove(from) ?: throw NoSuchFileException(from)
        entries[to] = entry
        return describe(to, entry)
    }

    override suspend fun stat(path: String): StoredFile? {
        called("stat", path)
        return entries[path]?.let { describe(path, it) }
    }

    private fun called(operation: String, path: String) {
        if (path.isNotEmpty()) ProjectPaths.requireValid(path)
        log.add(if (path.isEmpty()) operation else "$operation $path")
        failure(operation, path)?.let { throw it }
    }

    private fun describe(path: String, entry: Entry) =
        StoredFile(path, entry.text.toByteArray().size.toLong(), if (reportsTimes) entry.modified else 0L)
}

/** Drafts in memory, with scripted failures. */
class MemoryDraftStore : DraftStore {
    val drafts = LinkedHashMap<String, Draft>()
    var puts = 0
    var failure: (operation: String) -> IOException? = { null }

    override suspend fun load(): List<Draft> {
        failure("load")?.let { throw it }
        return drafts.values.toList()
    }

    override suspend fun put(draft: Draft) {
        failure("put")?.let { throw it }
        puts++
        drafts[draft.path] = draft
    }

    override suspend fun remove(path: String) {
        failure("remove")?.let { throw it }
        drafts.remove(path)
    }
}

/**
 * The project's view of ink for these tests: the `INCLUDE` lines, found by a line scan. (The real
 * parser is tested in `core.symbols`; the model only needs to know where the includes are.)
 */
object LineScanParser : InkFileParser {
    private val include = Regex("""^[ \t]*INCLUDE[ \t]+(.*)$""")

    override fun parse(text: CharSequence): ParsedInk {
        val includes = ArrayList<String>()
        var last = -1
        text.split(Regex("\r\n|\r|\n")).forEachIndexed { row, line ->
            include.find(line)?.let {
                includes.add(it.groupValues[1])
                last = row
            }
        }
        return ParsedInk(includes, last, symbols = null)
    }
}

/** An editor as the project sees one: the text lives here, and changes are reported through [onChange]. */
class FakeEditor(initial: String) : LiveText {
    var text: String = initial
        private set
    var onChange: () -> Unit = {}

    /** How often the project asked for the text. */
    var snapshots = 0
    val loads = ArrayList<String>()
    val edits = ArrayList<String>()

    /** A real editor may report a load like any other change; the project must not take that for typing. */
    var echoesLoads = false

    /** The user changes the text to [newText]. */
    fun type(newText: String) {
        text = newText
        onChange()
    }

    override fun snapshot(): CharSequence {
        snapshots++
        return text
    }

    override fun load(text: String) {
        this.text = text
        loads.add(text)
        if (echoesLoads) onChange()
    }

    override fun edit(start: Int, end: Int, replacement: String) {
        text = text.replaceRange(start, end, replacement)
        edits.add(replacement)
        onChange()
    }
}

/** What a model test works with. */
class ProjectFixture(val scope: TestScope) {
    val store = MemoryStore()

    suspend fun open(mainPath: String = "main.ink", parser: InkFileParser = LineScanParser): InkProject =
        InkProject.open(scope.backgroundScope, store, mainPath, parser, parseContext = EmptyCoroutineContext)

    suspend fun createNew(mainPath: String = "main.ink"): InkProject =
        InkProject.createNew(scope.backgroundScope, store, mainPath, LineScanParser, parseContext = EmptyCoroutineContext)

    /** Opens [path] in an editor, as the session does. */
    fun InkProject.edit(path: String): FakeEditor {
        val editor = FakeEditor(checkNotNull(text(path)) { "$path is not in the project" }.toString())
        editor.onChange = { textChanged(path) }
        check(attach(path, editor))
        return editor
    }

    /** Lets [millis] of virtual time pass and everything due run. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun pass(millis: Long) {
        scope.advanceTimeBy(millis)
        scope.runCurrent()
    }

    val InkProject.paths: List<String> get() = state.value.files.map { it.path }

    fun InkProject.file(path: String): ProjectFile = checkNotNull(state.value.file(path)) { "$path is not in the project" }
}

fun projectTest(block: suspend ProjectFixture.() -> Unit) = runTest { ProjectFixture(this).block() }
