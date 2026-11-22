package com.inkyandroid.core.project

import com.inkyandroid.core.engine.InkSources
import com.inkyandroid.core.settings.ProjectSettings
import com.inkyandroid.core.symbols.InkFileSymbols
import com.inkyandroid.core.syntax.InkTokenizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.NoSuchFileException
import kotlin.coroutines.CoroutineContext

/**
 * What the project takes from a parse of one file: where its `INCLUDE` lines point, and the symbols
 * it hands on to the rest of the app.
 *
 * @property includes the text after each `INCLUDE`, in order, as written.
 * @property lastIncludeRow the zero-based row of the last `INCLUDE` line, -1 without one.
 * @property symbols the file's symbols; the project only passes them on ([ProjectFile.symbols]).
 */
class ParsedInk(
    val includes: List<String>,
    val lastIncludeRow: Int,
    val symbols: InkFileSymbols?,
) {
    constructor(symbols: InkFileSymbols) : this(symbols.includes, symbols.lastIncludeRow, symbols)
}

/** What storage holds of one path, as a restore of drafts needs to know it ([InkProject.storedVersion]). */
internal sealed interface StoredVersion {
    /** Text with this [hash]. */
    data class Text(val hash: String, val stored: StoredFile) : StoredVersion

    /** A file that cannot be read as text (or storage could not be asked: [stored] is null then). */
    data class Unreadable(val stored: StoredFile?) : StoredVersion

    /** No file. */
    data object Absent : StoredVersion
}

/** Parses a file's text. The app's is [InkFileParser.of] a tokenizer; the project's own tests use a line scanner. */
fun interface InkFileParser {
    fun parse(text: CharSequence): ParsedInk

    companion object {
        /** The real parser: the symbols of `core.symbols` over [tokenizer] (one per `instructionPrefix`). */
        fun of(tokenizer: InkTokenizer): InkFileParser = InkFileParser { text -> ParsedInk(InkFileSymbols.parse(text, tokenizer)) }
    }
}

/**
 * The text of a file that is open in an editor. While one is attached ([InkProject.attach]) the
 * editor is the truth: the project keeps no copy and asks for the text when it needs it (a symbol
 * parse, a compile, a save, a draft). Called on the project's thread.
 */
interface LiveText {
    /** The text right now, as a value that never changes afterwards. Must be cheap: no copy per call if it can be avoided. */
    fun snapshot(): CharSequence

    /**
     * Storage's version replaces the text (a reload after an outside change, a revert): show exactly
     * [text] and forget the undo history, as upstream does. This is not an edit by the user: it must
     * not be reported back through [InkProject.textChanged].
     */
    fun load(text: String)

    /**
     * The project edits the text itself (an `INCLUDE` line added, or rewritten after a rename):
     * replace the characters from [start] to [end] by [replacement], as an edit the user can undo.
     */
    fun edit(start: Int, end: Int, replacement: String)
}

/**
 * An open ink project: upstream's `inkProject.js` and `inkFile.js` without the UI.
 *
 * A project is a root (a [ProjectStore]) and a main ink file in it. Every `.ink` file under the
 * root belongs to it; the files the main file reaches through `INCLUDE` lines are *included*, the
 * rest are *spare*. The project knows each file's text, whether it differs from storage, its
 * symbols and its includes, and publishes all of it as an immutable [ProjectSnapshot] in [state].
 *
 * **Threading.** A project is used from one thread: the thread of the [scope] it is given (the
 * main thread in the app, the test dispatcher in tests). Its suspending functions leave that
 * thread only inside the store and the parser, and come back before they touch anything. [state]
 * and [contentRevision] may be read from anywhere.
 *
 * **Text.** A file's text lives here until an editor opens the file and [attach]es itself; from
 * then on the project pulls the text from the editor. The editor reports every change with
 * [textChanged], which is cheap: it flips the unsaved flag at once and schedules a symbol parse
 * 200 ms later (upstream's delay); it copies nothing.
 *
 * **Storage.** Nothing is read or written except in the functions that say so, plus one case, as
 * upstream: when a parse finds an `INCLUDE` of a file the project does not have yet, that file is
 * looked for on storage and loaded. All storage work of a project runs one operation at a time.
 *
 * Create one with [open] or [createNew].
 */
class InkProject private constructor(
    private val scope: CoroutineScope,
    val store: ProjectStore,
    val mainPath: String,
    private var parser: InkFileParser,
    private val parseContext: CoroutineContext,
    private val parseDelayMillis: Long,
    private val writeAhead: WriteAhead?,
) {
    private class FileRecord(var path: String) {
        /** The text while no editor is attached; empty while one is. */
        var ownText: String = ""
        var live: LiveText? = null

        /** Grows with every change of the text, whoever made it. */
        var revision = 0L

        /** The file as storage reported it at our last read or write; null while it is not on storage. */
        var stored: StoredFile? = null

        /**
         * The hash of the text storage held at that moment; null while it is not on storage, and
         * while storage holds a version the project cannot know (the remains of a failed write of
         * its own, or a file that cannot be read as text).
         */
        var storedHash: String? = null
        var edited = false
        var conflict = false

        /**
         * A write of this file began and has not been seen to succeed: storage may hold part of it
         * (it may write in place). Every draft of the file says so until a write succeeds.
         */
        var saveUnfinished = false
        var symbols: InkFileSymbols? = null
        var includes: List<String> = emptyList()
        var lastIncludeRow = -1
        var parsedRevision = -1L
        var parseJob: Job? = null

        /** True while a load is being pushed into the editor, so its echo is not taken for typing. */
        var loading = false

        val unsaved: Boolean get() = edited || stored == null

        fun text(): CharSequence = live?.snapshot() ?: ownText
    }

    private val files = LinkedHashMap<String, FileRecord>()

    /** Files on storage that could not be read, with the failure; they are not in [files]. */
    private val loadErrors = LinkedHashMap<String, IOException>()

    /** Paths an `INCLUDE` names that storage did not have when last asked; asked again after the next sync. */
    private val knownAbsent = HashSet<String>()
    private var settingsFile: StoredFile? = null
    private var mentionedLoad: Job? = null

    /** Storage operations run one at a time: a save never interleaves with a sync or a rename. */
    private val storage = Mutex()

    private val mutableState = MutableStateFlow(ProjectSnapshot(mainPath, listOf(emptyFile(mainPath))))
    private val mutableContentRevision = MutableStateFlow(0L)

    /** The project as it is now. A new value appears when files, flags, includes or symbols change; not on every keystroke. */
    val state: StateFlow<ProjectSnapshot> = mutableState.asStateFlow()

    /**
     * Grows whenever what the compiler would see has changed: any text edit, load, added or removed
     * file. The session watches it to feed the live compile session and to schedule drafts and
     * autosave.
     */
    val contentRevision: StateFlow<Long> = mutableContentRevision.asStateFlow()

    /** The root-relative path of the project settings file (`<main without .ink>.settings.json`). */
    val settingsPath: String get() = ProjectSettings.pathFor(mainPath)

    // ------------------------------------------------------------------ text

    /** Whether [path] is a file of the project. */
    operator fun contains(path: String): Boolean = files.containsKey(path)

    /** The current text of [path]: from the attached editor, or the project's own copy. Null for a path that is not in the project. */
    fun text(path: String): CharSequence? = files[path]?.text()

    /** How often the text of [path] has changed; -1 for a path that is not in the project. Two equal values mean the same text. */
    fun revision(path: String): Long = files[path]?.revision ?: -1

    /** The hash of the version of [path] that storage holds as far as the project knows; null while the file is not on storage. */
    fun storedHash(path: String): String? = files[path]?.storedHash

    /**
     * An editor has opened [path]: from now on its text is [live]'s. The editor must have been
     * filled with [text] of that path just before. Returns false for a path that is not in the project.
     */
    fun attach(path: String, live: LiveText): Boolean {
        val record = files[path] ?: return false
        record.live = live
        record.ownText = ""
        return true
    }

    /** The editor of [path] goes away: the project takes a copy of its text. */
    fun detach(path: String) {
        val record = files[path] ?: return
        val live = record.live ?: return
        record.ownText = live.snapshot().toString()
        record.live = null
    }

    /**
     * The attached editor's text of [path] has changed. Call it for every change; it is cheap. The
     * file is unsaved from this moment; its symbols follow 200 ms after the last change.
     */
    fun textChanged(path: String) {
        val record = files[path] ?: return
        if (record.loading) return
        touched(record)
        scheduleParse(record)
        if (!record.edited) {
            record.edited = true
            publish()
        }
    }

    /**
     * The sources for the compiler: the text of every file at this moment, spare files too (upstream
     * copies every project file to the compiler's folder).
     */
    fun sources(): InkSources = InkSources(mainPath, files.mapValues { (_, record) -> record.text().toString() })

    /**
     * Replaces the parser (the project's `instructionPrefix` changed, which changes the tokenizer)
     * and parses every file again.
     */
    suspend fun setParser(parser: InkFileParser) {
        this.parser = parser
        for (record in files.values.toList()) parseNow(record)
    }

    /**
     * Waits until nothing is pending: every scheduled symbol parse has run and every file a parse
     * found an `INCLUDE` for has been looked up. For the moments that need the settled state (the
     * first compile after opening, tests).
     */
    suspend fun settle() {
        while (true) {
            val pending = files.values.mapNotNull { it.parseJob?.takeIf(Job::isActive) }
            if (pending.isNotEmpty()) {
                // run them now instead of sitting out their delay
                for (record in files.values.toList()) if (record.parseJob?.isActive == true) parseNow(record)
                continue
            }
            val loading = mentionedLoad?.takeIf(Job::isActive) ?: return
            joinAll(loading)
        }
    }

    // ------------------------------------------------------------------ files

    /**
     * Upstream's "add new include" (`inkProject.js: addNewInclude`): a new, empty, unsaved file
     * named by [ProjectPaths.newIncludePath], refused when a file with that path is already in the
     * project or on storage. Nothing is written until the next save.
     *
     * With [addToMain] the main file gets the line `INCLUDE <path>`: directly after its last
     * `INCLUDE` line, or at the very top when it has none. (Upstream leaves an extra empty line
     * behind each time it inserts after an existing line; this does not.)
     */
    suspend fun addInclude(name: String, addToMain: Boolean): AddIncludeResult = storage.withLock {
        val path = ProjectPaths.newIncludePath(name) ?: return AddIncludeResult.InvalidName(name)
        if (files.containsKey(path)) return AddIncludeResult.AlreadyExists(path)
        val onStorage = try {
            store.stat(path) != null
        } catch (unreadable: IOException) {
            // cannot tell: the save will, and it never overwrites a file it has not seen
            false
        }
        if (onStorage || files.containsKey(path)) return AddIncludeResult.AlreadyExists(path)

        val record = FileRecord(path)
        files[path] = record
        knownAbsent.remove(path)
        touched(record)
        parseNow(record, refresh = false)
        if (addToMain) {
            val main = files.getValue(mainPath)
            parseNow(main, refresh = false)
            insertIncludeLine(main, path)
            parseNow(main, refresh = false)
        }
        refreshIncludes()
        publish()
        AddIncludeResult.Added(path)
    }

    /**
     * Renames a file (not in upstream). [newName] is a root-relative path and gets `.ink` by the
     * rule of [ProjectPaths.newIncludePath]. The file moves on storage when it is there, and every
     * `INCLUDE` line that names it, in any file of the project, is rewritten to the new path; those
     * files become unsaved. The main file cannot be renamed.
     */
    suspend fun rename(path: String, newName: String): RenameResult = storage.withLock {
        val record = files[path] ?: return RenameResult.NotInProject(path)
        if (path == mainPath) return RenameResult.IsMainFile
        val target = ProjectPaths.newIncludePath(newName) ?: return RenameResult.InvalidName(newName)
        if (target == path) return RenameResult.Renamed(path, path, emptyList())
        if (files.containsKey(target)) return RenameResult.AlreadyExists(target)
        try {
            if (store.stat(target) != null) return RenameResult.AlreadyExists(target)
            if (record.stored != null) record.stored = store.rename(path, target)
        } catch (exists: java.nio.file.FileAlreadyExistsException) {
            return RenameResult.AlreadyExists(target)
        } catch (failed: IOException) {
            return RenameResult.Failed(failed)
        }

        // the same place in the order of files, under the new name
        val reordered = files.entries.map { (key, value) -> (if (key == path) target else key) to value }
        files.clear()
        reordered.toMap(files)
        record.path = target
        knownAbsent.remove(target)

        val rewritten = ArrayList<String>()
        for (other in files.values.toList()) {
            if (path !in other.includes) continue
            // the line numbers must be those of the text as it is now
            if (rewriteIncludeLines(other, path, target)) {
                rewritten.add(other.path)
                parseNow(other, refresh = false)
            }
        }
        mutableContentRevision.value++
        refreshIncludes()
        publish()
        RenameResult.Renamed(path, target, rewritten)
    }

    /**
     * Deletes a file from the project and from storage (upstream deletes only when a file vanishes
     * from the folder). The main file is never deleted. `INCLUDE` lines that name the file stay:
     * the compiler's error shows the writer what is left to do.
     */
    suspend fun delete(path: String): DeleteResult = storage.withLock {
        val record = files[path] ?: return DeleteResult.NotInProject(path)
        if (path == mainPath) return DeleteResult.IsMainFile
        if (record.stored != null) {
            try {
                store.delete(path)
            } catch (alreadyGone: NoSuchFileException) {
                // what was wanted
            } catch (failed: IOException) {
                return DeleteResult.Failed(failed)
            }
        }
        remove(record)
        refreshIncludes()
        publish()
        DeleteResult.Deleted(path)
    }

    // ------------------------------------------------------------------ saving

    /**
     * Writes [path] to storage if it has unsaved changes.
     *
     * Before writing, storage is asked whether the file is still the version the project last saw.
     * If someone else changed it, nothing is written: the file is marked as a conflict and the
     * answer is [SaveResult.Conflict]. A failed write changes nothing: the file stays unsaved.
     * Text typed while the write is under way is kept and leaves the file unsaved again.
     */
    suspend fun save(path: String): SaveResult = storage.withLock { saveLocked(path, overwrite = false) }

    /**
     * Saves every file with unsaved changes, the main file first (upstream's "Save Project", which
     * writes every file; this writes only what differs from storage, so an unchanged file never
     * overwrites a change made outside). Goes on after a failure, so one bad file does not keep the
     * others unsaved.
     */
    suspend fun saveAll(): SaveReport = storage.withLock {
        val results = LinkedHashMap<String, SaveResult>()
        val order = listOf(mainPath) + files.keys.filter { it != mainPath }
        for (path in order) {
            val record = files[path] ?: continue
            if (!record.unsaved) continue
            results[path] = saveLocked(path, overwrite = false)
        }
        SaveReport(results)
    }

    /**
     * Discards the unsaved changes of [path] and takes storage's version. Returns false, changing
     * nothing, when the file is not on storage (there is nothing to go back to) or cannot be read.
     */
    @Throws(IOException::class)
    suspend fun revert(path: String): Boolean = storage.withLock {
        val record = files[path] ?: return false
        if (store.stat(path) == null) return false
        reloadLocked(record)
        refreshIncludes()
        publish()
        true
    }

    /**
     * Settles a conflict ([ProjectFile.hasConflict]). With [keepMine] the file's text here is
     * written over storage's version; otherwise storage's version replaces the text here and the
     * unsaved changes are gone.
     */
    @Throws(IOException::class)
    suspend fun resolveConflict(path: String, keepMine: Boolean): SaveResult = storage.withLock {
        val record = files[path] ?: return SaveResult.NothingToSave
        if (keepMine) {
            saveLocked(path, overwrite = true)
        } else {
            if (store.stat(path) == null) {
                // their version is "no file": ours stays, as a file that is not on storage
                record.stored = null
                record.storedHash = null
                record.conflict = false
            } else {
                reloadLocked(record)
            }
            refreshIncludes()
            publish()
            SaveResult.NothingToSave
        }
    }

    private suspend fun saveLocked(path: String, overwrite: Boolean): SaveResult {
        val record = files[path] ?: return SaveResult.NothingToSave
        if (!record.unsaved && !overwrite) return SaveResult.NothingToSave
        if (record.conflict && !overwrite) return SaveResult.Conflict

        val revision = record.revision
        val text = record.text().toString()
        val hash = TextHash.of(text)
        try {
            if (!overwrite) {
                val theirs = store.stat(path)
                val seen = record.stored
                if (theirs != null && theirs != seen) {
                    // someone wrote to it, or it appeared: only the content says whether it matters
                    val theirHash = TextHash.of(store.read(path))
                    if (theirHash != hash && theirHash != record.storedHash) {
                        if (files[path] === record) {
                            record.conflict = true
                            publish()
                        }
                        return SaveResult.Conflict
                    }
                    if (files[path] === record) {
                        record.stored = theirs
                        record.storedHash = theirHash
                    }
                }
                if (theirs != null && record.storedHash == hash) {
                    // typed and taken back: storage already has exactly this
                    if (files[path] === record && record.revision == revision && record.edited) {
                        record.edited = false
                        publish()
                    }
                    return SaveResult.NothingToSave
                }
            }
            // Not cancellable: a write that reaches storage must be recorded, or the next save would
            // find a version it does not know and call its own work an outside change.
            return withContext(NonCancellable) {
                // Storage may write in place (SAF), so a write cut short can leave part of the new
                // text over the old: the whole text goes into a draft first, and that draft stays
                // until a write of this file has succeeded.
                record.saveUnfinished = true
                writeAhead?.beforeWrite(path, text, record.storedHash, revision)
                val written = try {
                    store.write(path, text)
                } catch (failed: IOException) {
                    leftBehind(record)
                    return@withContext SaveResult.Failed(failed)
                }
                if (files[path] === record) {
                    record.stored = written
                    record.storedHash = hash
                    record.conflict = false
                    record.saveUnfinished = false
                    // typing during the write: what is on storage is no longer what is here
                    record.edited = record.revision != revision
                    publish()
                }
                SaveResult.Saved
            }
        } catch (failed: IOException) {
            return SaveResult.Failed(failed)
        }
    }

    /**
     * A write of [record] has failed. What storage holds now is the old version, or part of the
     * text that failed to be written: ours either way, never a change from outside. The project
     * takes note of the file as storage now reports it, so the next save writes over it without a
     * conflict and a look at storage does not reload it; a change made outside after this moment
     * is still noticed. (When storage cannot even be asked, the next save compares contents as usual.)
     */
    private suspend fun leftBehind(record: FileRecord) {
        val now = try {
            store.stat(record.path)
        } catch (unreachable: IOException) {
            return
        }
        if (files[record.path] !== record || now == record.stored) return
        record.stored = now
        record.storedHash = null
    }

    // ------------------------------------------------------------------ storage

    /**
     * Looks at storage and brings the project in line with it, by upstream's rules for its file
     * watcher (`inkProject.js: startFileWatching`):
     *
     * - a file that changed and has no unsaved changes is reloaded silently;
     * - a file that changed and has unsaved changes is left alone and reported as a conflict
     *   (upstream keeps silent and overwrites on the next save; here nothing is overwritten until
     *   the user has chosen);
     * - a new `.ink` file joins the project;
     * - a file that is gone leaves the project, unless it has unsaved changes or is the main file;
     * - the app's own saves are not changes: what counts is the content, compared by hash with what
     *   the project last read or wrote.
     *
     * Throws when storage cannot be listed at all; a single unreadable file is in [SyncReport.errors].
     */
    @Throws(IOException::class)
    suspend fun syncWithStorage(): SyncReport = storage.withLock {
        val listing = store.list().associateBy { it.path }
        knownAbsent.clear()

        val reloaded = ArrayList<String>()
        val added = ArrayList<String>()
        val removed = ArrayList<String>()
        val conflicts = ArrayList<String>()
        val keptMissing = ArrayList<String>()
        val errors = LinkedHashMap<String, String>()

        for (record in files.values.toList()) {
            val path = record.path
            // a file an INCLUDE reached may lie where the listing does not look (a dot-folder)
            val theirs = listing[path] ?: if (record.stored != null) statOrNull(path) else null
            if (files[path] !== record) continue
            val seen = record.stored
            if (theirs == null) {
                if (seen == null) continue
                if (record.unsaved || path == mainPath) {
                    record.stored = null
                    record.storedHash = null
                    record.conflict = false
                    keptMissing.add(path)
                } else {
                    remove(record)
                    removed.add(path)
                }
                continue
            }
            // an equal answer is trusted when storage tells times, or when there is no content to compare with
            if (theirs == seen && (theirs.modified != 0L || record.storedHash == null)) continue
            val theirText = try {
                store.read(path)
            } catch (failed: IOException) {
                errors[path] = describe(failed)
                continue
            }
            if (files[path] !== record) continue
            val theirHash = TextHash.of(theirText)
            if (theirHash == record.storedHash) {
                // touched, or our own save seen with a later time stamp: the same content
                record.stored = theirs
                continue
            }
            if (!record.unsaved && record.live != null && TextHash.of(record.text()) != record.storedHash) {
                // the editor holds a change it has not reported yet (it reports within the frame):
                // that text is unsaved, and a reload must never replace it
                record.edited = true
            }
            if (!record.unsaved) {
                applyLoaded(record, theirText, theirs)
                parseNow(record, refresh = false)
                reloaded.add(path)
            } else if (!record.edited && seen == null || TextHash.of(record.text()) == theirHash) {
                // a file we only knew as "not on storage" has appeared untouched by us, or both
                // sides hold the same text: storage's version is simply the stored one
                if (!record.edited) applyLoaded(record, theirText, theirs) else {
                    record.stored = theirs
                    record.storedHash = theirHash
                    record.edited = false
                    record.conflict = false
                }
                parseNow(record, refresh = false)
            } else {
                if (!record.conflict) conflicts.add(path)
                record.conflict = true
            }
        }

        for ((path, file) in listing) {
            if (files.containsKey(path) || !ProjectPaths.isInkFile(path)) continue
            try {
                val text = store.read(path)
                if (files.containsKey(path)) continue
                val record = FileRecord(path)
                files[path] = record
                applyLoaded(record, text, file)
                parseNow(record, refresh = false)
                loadErrors.remove(path)
                added.add(path)
            } catch (failed: IOException) {
                errors[path] = describe(failed)
                loadErrors[path] = failed
            }
        }

        val settingsNow = listing[settingsPath]
        val settingsChanged = settingsNow != settingsFile
        settingsFile = settingsNow

        refreshIncludes()
        loadMentionedLocked()
        publish()
        SyncReport(reloaded, added, removed, conflicts, keptMissing, settingsChanged, errors)
    }

    private suspend fun statOrNull(path: String): StoredFile? = try {
        store.stat(path)
    } catch (failed: IOException) {
        null
    }

    private suspend fun reloadLocked(record: FileRecord) {
        val path = record.path
        val text = store.read(path)
        val stored = store.stat(path) ?: throw NoSuchFileException(path)
        if (files[path] !== record) return
        applyLoaded(record, text, stored)
        parseNow(record, refresh = false)
    }

    /** Storage's [text] becomes the file's text: not an edit, so the file is saved afterwards. */
    private fun applyLoaded(record: FileRecord, text: String, stored: StoredFile) {
        val live = record.live
        if (live != null) {
            record.loading = true
            try {
                live.load(text)
            } finally {
                record.loading = false
            }
        } else {
            record.ownText = text
        }
        record.stored = stored
        record.storedHash = TextHash.of(text)
        record.edited = false
        record.conflict = false
        record.saveUnfinished = false
        touched(record)
    }

    private fun remove(record: FileRecord) {
        record.parseJob?.cancel()
        files.remove(record.path)
        mutableContentRevision.value++
    }

    private fun touched(record: FileRecord) {
        record.revision++
        mutableContentRevision.value++
    }

    // ------------------------------------------------------------------ drafts

    /** Whether a write of [path] began and has not been seen to succeed (see [WriteAhead]); for the drafts journal. */
    internal fun isSaveUnfinished(path: String): Boolean = files[path]?.saveUnfinished == true

    /**
     * What storage holds of [path], for [DraftJournal.restore]: from what the project knows for
     * its files, from storage for any other path.
     */
    internal suspend fun storedVersion(path: String): StoredVersion = storage.withLock {
        files[path]?.let { record ->
            val stored = record.stored ?: return StoredVersion.Absent
            return record.storedHash?.let { StoredVersion.Text(it, stored) } ?: StoredVersion.Unreadable(stored)
        }
        val stored = try {
            store.stat(path) ?: return StoredVersion.Absent
        } catch (unreachable: IOException) {
            return StoredVersion.Unreadable(null)
        }
        try {
            StoredVersion.Text(TextHash.of(store.read(path)), stored)
        } catch (unreadable: IOException) {
            StoredVersion.Unreadable(stored)
        }
    }

    /**
     * Brings a draft back as unsaved changes; [DraftJournal.restore] decides what to do with each
     * draft and calls this, right after the project was opened.
     *
     * @param version what [storedVersion] said storage holds; a file that is not in the project yet
     *   joins it as storage has it, with the draft's text.
     * @param conflict the draft was written over another stored version than the one storage holds
     *   now (or storage's version cannot be read): the file is marked as a conflict as well.
     * @param saveUnfinished the draft was written for a save that never finished: whatever storage
     *   holds is the remains of it, which the next save writes over.
     */
    internal suspend fun restoreUnsaved(path: String, text: String, version: StoredVersion, conflict: Boolean, saveUnfinished: Boolean) {
        var record = files[path]
        if (record == null) {
            record = FileRecord(path)
            files[path] = record
            knownAbsent.remove(path)
            loadErrors.remove(path)
            when (version) {
                is StoredVersion.Text -> {
                    record.stored = version.stored
                    record.storedHash = version.hash
                }
                is StoredVersion.Unreadable -> record.stored = version.stored
                StoredVersion.Absent -> {}
            }
        }
        val live = record.live
        if (live != null) live.edit(0, live.snapshot().length, text) else record.ownText = text
        record.edited = true
        record.conflict = conflict
        record.saveUnfinished = saveUnfinished
        touched(record)
        parseNow(record, refresh = false)
        refreshIncludes()
        publish()
    }

    // ------------------------------------------------------------------ symbols and includes

    private fun scheduleParse(record: FileRecord) {
        record.parseJob?.cancel()
        record.parseJob = scope.launch {
            delay(parseDelayMillis)
            parse(record, refresh = true)
        }
    }

    private suspend fun parseNow(record: FileRecord, refresh: Boolean = true) {
        record.parseJob?.cancel()
        record.parseJob = null
        parse(record, refresh)
    }

    private suspend fun parse(record: FileRecord, refresh: Boolean) {
        val revision = record.revision
        val text = record.text()
        val parsed = try {
            withContext(parseContext) { parser.parse(text) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (broken: Exception) {
            // a parser bug must not take the project down: the file keeps the symbols it had
            return
        }
        if (files[record.path] !== record || revision < record.parsedRevision) return
        record.parsedRevision = revision
        record.symbols = parsed.symbols
        record.lastIncludeRow = parsed.lastIncludeRow
        record.includes = parsed.includes.mapNotNull(ProjectPaths::normalizeInclude)
        if (refresh) {
            refreshIncludes()
            publish()
        }
    }

    /**
     * Upstream's `refreshIncludes`: which files the main file reaches, and which mentioned files
     * are not in the project yet. Those are looked for on storage (in the background unless the
     * caller holds the storage lock and does it itself).
     */
    private fun refreshIncludes() {
        val missing = reachable().filter { it !in files && it !in knownAbsent }
        if (missing.isNotEmpty() && mentionedLoad?.isActive != true) {
            mentionedLoad = scope.launch {
                storage.withLock {
                    loadMentionedLocked()
                    publish()
                }
            }
        }
    }

    /** Every path the main file reaches through `INCLUDE` lines, in the order it reaches them, without the main file. */
    private fun reachable(): LinkedHashSet<String> {
        val reached = LinkedHashSet<String>()
        fun walk(record: FileRecord) {
            for (include in record.includes) {
                if (include == mainPath || !reached.add(include)) continue
                files[include]?.let(::walk)
            }
        }
        files[mainPath]?.let(::walk)
        return reached
    }

    /** Loads the mentioned files that storage has; loading one may mention more. Call with the storage lock held. */
    private suspend fun loadMentionedLocked() {
        while (true) {
            val wanted = reachable().filter { it !in files && it !in knownAbsent }
            if (wanted.isEmpty()) return
            for (path in wanted) {
                if (!ProjectPaths.isValid(path)) {
                    knownAbsent.add(path)
                    continue
                }
                try {
                    val stored = store.stat(path)
                    if (stored == null) {
                        knownAbsent.add(path)
                        continue
                    }
                    val text = store.read(path)
                    if (files.containsKey(path)) continue
                    val record = FileRecord(path)
                    files[path] = record
                    applyLoaded(record, text, stored)
                    parseNow(record, refresh = false)
                    loadErrors.remove(path)
                } catch (failed: IOException) {
                    // upstream: "Failed to load ink file"; the file stays out of the project
                    knownAbsent.add(path)
                    loadErrors[path] = failed
                }
            }
        }
    }

    private fun insertIncludeLine(main: FileRecord, path: String) {
        val text = main.text()
        val lineBreak = if (text.contains("\r\n")) "\r\n" else "\n"
        val lastIncludeRow = main.lastIncludeRow
        if (lastIncludeRow < 0) {
            applyEdit(main, 0, 0, "INCLUDE $path$lineBreak")
        } else {
            val end = TextLines.endOfRow(text, lastIncludeRow)
            applyEdit(main, end, end, "${lineBreak}INCLUDE $path")
        }
    }

    /** Points the `INCLUDE` lines of [record] that name [from] at [to]. Returns whether any line changed. */
    private fun rewriteIncludeLines(record: FileRecord, from: String, to: String): Boolean {
        val text = record.text()
        val edits = TextLines.includePathRanges(text).filter { (range, _) ->
            ProjectPaths.normalizeInclude(text.substring(range.first, range.last + 1)) == from
        }
        // from the end, so the earlier offsets stay true
        for ((range, _) in edits.asReversed()) applyEdit(record, range.first, range.last + 1, to)
        return edits.isNotEmpty()
    }

    private fun applyEdit(record: FileRecord, start: Int, end: Int, replacement: String) {
        val live = record.live
        if (live != null) live.edit(start, end, replacement) else record.ownText = record.ownText.replaceRange(start, end, replacement)
        record.edited = true
        touched(record)
    }

    // ------------------------------------------------------------------ publishing

    private fun publish() {
        val reached = reachable()
        val main = files[mainPath] ?: return
        val included = reached.mapNotNull { files[it] }
        val spare = files.values.filter { it !== main && it.path !in reached }.sortedBy { it.path }
        val ordered = listOf(main) + included + spare
        mutableState.value = ProjectSnapshot(
            mainPath = mainPath,
            files = ordered.map { record ->
                ProjectFile(
                    path = record.path,
                    isMain = record === main,
                    isSpare = record !== main && record.path !in reached,
                    isOnStorage = record.stored != null,
                    hasUnsavedChanges = record.unsaved,
                    hasConflict = record.conflict,
                    includes = record.includes,
                    symbols = record.symbols,
                )
            },
            missingIncludes = reached.filter { it !in files },
            loadErrors = loadErrors.mapValues { (_, failed) -> describe(failed) },
            loadFailures = loadErrors.toMap(),
        )
    }

    private suspend fun load() {
        val listing = store.list()
        val mainStored = listing.firstOrNull { it.path == mainPath } ?: store.stat(mainPath) ?: throw NoSuchFileException(mainPath)
        val main = FileRecord(mainPath)
        files[mainPath] = main
        val mainText = try {
            store.read(mainPath)
        } catch (unreadable: IOException) {
            if (unreadable is NoSuchFileException) throw unreadable
            // A save of ours that was cut short can leave the main file half written, even in the
            // middle of a character (storage may write in place). Its draft holds the whole text,
            // which takes the file's place; the next save writes it over the remains.
            main.ownText = writeAhead?.unfinishedSave(mainPath) ?: throw unreadable
            null
        }
        if (mainText != null) {
            applyLoaded(main, mainText, mainStored)
        } else {
            main.stored = mainStored
            main.edited = true
            main.saveUnfinished = true
            touched(main)
        }
        for (file in listing.sortedBy { it.path }) {
            if (file.path == mainPath || !ProjectPaths.isInkFile(file.path)) continue
            try {
                val record = FileRecord(file.path)
                applyLoaded(record, store.read(file.path), file)
                files[file.path] = record
            } catch (failed: IOException) {
                loadErrors[file.path] = failed
            }
        }
        settingsFile = listing.firstOrNull { it.path == settingsPath }
        for (record in files.values.toList()) parseNow(record, refresh = false)
        storage.withLock { loadMentionedLocked() }
        publish()
    }

    private suspend fun startNew(mainText: String) {
        val main = FileRecord(mainPath)
        main.ownText = mainText
        files[mainPath] = main
        touched(main)
        parseNow(main, refresh = false)
        publish()
    }

    companion object {
        /** Upstream's delay between a file's last change and the parse of its symbols (`inkFileSymbols.js`). */
        const val PARSE_DELAY_MILLIS = 200L

        /**
         * Opens the project whose main file is [mainPath] in [store]: reads the main file and every
         * `.ink` file under the root (upstream's watcher adds them all), parses them, follows the
         * `INCLUDE` lines, and returns when the first complete [ProjectSnapshot] is in [state].
         *
         * Throws an [IOException] when storage cannot be listed or the main file cannot be read
         * ([NoSuchFileException] when it does not exist). Other files that cannot be read are left
         * out and named in [ProjectSnapshot.loadErrors]. One exception: a main file that cannot be
         * read after a save of it was cut short opens with the text that save was writing, when
         * [writeAhead] has it ([WriteAhead.unfinishedSave]).
         *
         * @param scope the project's timers and background look-ups run in it, on its dispatcher,
         *   which is the one thread the project is used from; cancelling it ends them.
         * @param parseContext where the parser runs (a background dispatcher: parsing a long file
         *   takes milliseconds that the UI thread does not have).
         * @param writeAhead hears of every write before it begins: the drafts journal
         *   ([DraftJournal]), so that no write that is cut short loses text.
         */
        @Throws(IOException::class)
        suspend fun open(
            scope: CoroutineScope,
            store: ProjectStore,
            mainPath: String,
            parser: InkFileParser,
            parseContext: CoroutineContext = Dispatchers.Default,
            parseDelayMillis: Long = PARSE_DELAY_MILLIS,
            writeAhead: WriteAhead? = null,
        ): InkProject {
            ProjectPaths.requireValid(mainPath)
            return InkProject(scope, store, mainPath, parser, parseContext, parseDelayMillis, writeAhead).also { it.load() }
        }

        /**
         * A brand-new project: a main file with [mainText] (upstream's little starter story) that is
         * not on storage yet, so the project starts unsaved and the first save creates it.
         */
        suspend fun createNew(
            scope: CoroutineScope,
            store: ProjectStore,
            mainPath: String,
            parser: InkFileParser,
            mainText: String = NewProject.MAIN_TEXT,
            parseContext: CoroutineContext = Dispatchers.Default,
            parseDelayMillis: Long = PARSE_DELAY_MILLIS,
            writeAhead: WriteAhead? = null,
        ): InkProject {
            ProjectPaths.requireValid(mainPath)
            return InkProject(scope, store, mainPath, parser, parseContext, parseDelayMillis, writeAhead).also { it.startNew(mainText) }
        }

        private fun emptyFile(path: String) = ProjectFile(
            path = path,
            isMain = true,
            isSpare = false,
            isOnStorage = false,
            hasUnsavedChanges = false,
            hasConflict = false,
            includes = emptyList(),
            symbols = null,
        )

        private fun describe(error: IOException): String = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }
}
