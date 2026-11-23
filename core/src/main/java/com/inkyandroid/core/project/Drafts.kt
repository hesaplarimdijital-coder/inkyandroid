package com.inkyandroid.core.project

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The unsaved text of one file, kept outside the project's own storage so that it survives
 * whatever happens to the app.
 *
 * @property path the file's root-relative path in its project.
 * @property text the whole unsaved text.
 * @property baseHash the [TextHash] of the stored version the text was edited from; null when the
 *   file was not on storage (a new file that was never saved).
 * @property writtenAt when the draft was written, in milliseconds since the epoch (for a person
 *   reading a bug report; no rule depends on clocks).
 * @property saving a save of the file had begun and was not seen to succeed when the draft was
 *   written: storage may hold part of that save instead of [baseHash]'s version (see [WriteAhead]).
 */
data class Draft(
    val path: String,
    val text: String,
    val baseHash: String?,
    val writtenAt: Long,
    val saving: Boolean = false,
)

/**
 * Where the drafts of one project are kept. The app gives each project its own store in
 * app-private storage; [FileDraftStore] is the implementation.
 */
interface DraftStore {
    /** Every draft that can be read. A damaged entry is left out, never an error. */
    @Throws(IOException::class)
    suspend fun load(): List<Draft>

    /** Stores [draft], replacing the one for the same path; all or nothing. */
    @Throws(IOException::class)
    suspend fun put(draft: Draft)

    /** Forgets the draft of [path], if there is one. */
    @Throws(IOException::class)
    suspend fun remove(path: String)
}

/** What [DraftJournal.restore] did with the drafts it found. Each list holds paths. */
data class DraftRestoreReport(
    /** Brought back as unsaved changes over the stored version they were written from (or as files that are not on storage). */
    val restored: List<String> = emptyList(),
    /** Brought back as unsaved changes, but storage has changed since: the files are also marked as conflicts. */
    val conflicts: List<String> = emptyList(),
    /** Storage already holds exactly the draft's text (it was saved just before the app died): dropped. */
    val obsolete: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = restored.isEmpty() && conflicts.isEmpty()
}

/**
 * The drafts journal as a project sees it while saving; [DraftJournal] is the implementation.
 *
 * Storage may write a file in place (the Storage Access Framework has no atomic replace), so a write
 * that is cut short, by a failure or by the death of the process, can leave part of the new text
 * over the old one. Therefore, right before the project writes a file, the text it is about to
 * write goes into the file's draft, marked as a save that has begun ([Draft.saving]); and that
 * draft stays until a write of the file has returned successfully.
 */
interface WriteAhead {
    /**
     * [text], the file's [revision], is about to be written to [path] over the stored version whose
     * hash is [baseHash] (null: none known). Called right before the write. The write goes ahead
     * whatever happens here: a draft that cannot be written is the journal's to report.
     */
    suspend fun beforeWrite(path: String, text: String, baseHash: String?, revision: Long)

    /**
     * The text a save of [path] was writing when it was cut short, if a draft holds it: what takes
     * the place of a stored file that cannot be read any more (a character cut in half).
     */
    suspend fun unfinishedSave(path: String): String?

    /** The unsaved text of [path], if a draft holds it: what a file that is gone from storage opens with. */
    suspend fun draftText(path: String): String? = null
}

/**
 * The drafts journal: the rule that unsaved text is never only in memory for long.
 *
 * - **Written**: [flush] stores a draft for every file with unsaved changes whose text has changed
 *   since its last draft. The session calls it a second after the last keystroke and when the app
 *   goes to the background. And before every write of a file, the text being written goes into its
 *   draft first ([beforeWrite], the project calls it).
 * - **Discarded**: the same [flush] removes the draft of every file that no longer has unsaved
 *   changes (it was saved, reverted, reloaded or deleted). A file is saved only once a write has
 *   returned successfully, so a failed or interrupted write never takes the draft with it.
 * - **Restored**: [restore], called once after a project is opened, brings each draft back as
 *   unsaved changes. A draft remembers the hash of the stored version it was written over: if
 *   storage still holds that version the draft is simply the newer text; if storage holds another
 *   version (or one that cannot be read) the draft is restored *and* the file is marked as a
 *   conflict, so the user chooses and neither text is lost; if storage holds exactly the draft's
 *   text the draft is obsolete. A draft of a save that was not seen to finish ([Draft.saving]) wins,
 *   without a conflict, only over what can be all that save left on storage (the version it started
 *   from, nothing, a file cut mid-character, a beginning of its text); over anything else, which may be
 *   another device's writing after the save had in fact finished, it is a conflict like any other.
 *
 * Used from the project's thread; its operations run one at a time.
 */
class DraftJournal(
    private val store: DraftStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : WriteAhead {
    /** What the draft on disk holds of a file: its revision then, and whether it was marked [Draft.saving]. */
    private data class Journaled(val revision: Long, val saving: Boolean)

    private val journaled = HashMap<String, Journaled>()

    /** A put for a path while another is under way would mix two drafts in one temporary file. */
    private val lock = Mutex()

    /**
     * Applies the stored drafts to the freshly opened [project]. Throws only when the drafts cannot
     * be listed at all; the project is then as storage has it.
     */
    @Throws(IOException::class)
    suspend fun restore(project: InkProject): DraftRestoreReport {
        val restored = ArrayList<String>()
        val conflicts = ArrayList<String>()
        val obsolete = ArrayList<String>()
        // the journal's lock is never held while the project asks storage: a save holds the
        // project's storage lock when it calls beforeWrite, which takes the journal's
        for (draft in lock.withLock { store.load() }) {
            val path = draft.path
            if (!ProjectPaths.isValid(path)) {
                lock.withLock { removeQuietly(path) }
                continue
            }
            val version = project.storedVersion(path)
            val conflict = when {
                version is StoredVersion.Text && version.hash == TextHash.of(draft.text) -> {
                    lock.withLock { removeQuietly(path) }
                    obsolete.add(path)
                    continue
                }
                draft.saving -> !isRemainsOfItsSave(draft, version, project)
                version is StoredVersion.Absent -> false
                version is StoredVersion.Text && version.hash == draft.baseHash -> false
                else -> true
            }
            project.restoreUnsaved(path, draft.text, version, conflict, saveUnfinished = draft.saving)
            journaled[path] = Journaled(project.revision(path), draft.saving)
            (if (conflict) conflicts else restored).add(path)
        }
        return DraftRestoreReport(restored, conflicts, obsolete)
    }

    /**
     * Whether what storage holds of a file can be all that the save [draft] was written for left
     * there: the version the save started from (it never touched storage), nothing, a file cut in
     * the middle of a character, or a beginning of the draft's text (a truncating write cut short).
     * Anything else may be someone else's text written after the save had finished (the other
     * device, through a synced folder): never replaced without the writer's word.
     */
    private fun isRemainsOfItsSave(draft: Draft, version: StoredVersion, project: InkProject): Boolean = when (version) {
        StoredVersion.Absent, is StoredVersion.Unreadable -> true
        // at restore time no editor is attached: the project's own copy of a file it has is storage's text
        is StoredVersion.Text -> version.hash == draft.baseHash ||
            (version.text ?: project.text(draft.path))?.let { remains -> draft.text.startsWith(remains) } == true
    }

    /**
     * Brings the journal in line with [project]: writes the missing and outdated drafts, removes
     * the ones no longer needed. Returns the files whose draft could not be written (a full disk),
     * with the reason; their drafts are tried again by the next call.
     */
    suspend fun flush(project: InkProject): Map<String, IOException> = lock.withLock { flushLocked(project) }

    private suspend fun flushLocked(project: InkProject): Map<String, IOException> {
        val failures = LinkedHashMap<String, IOException>()
        val unsaved = project.state.value.unsavedFiles.map { it.path }
        for (path in unsaved) {
            val now = Journaled(project.revision(path), project.isSaveUnfinished(path))
            if (journaled[path] == now) continue
            val text = project.text(path) ?: continue
            try {
                store.put(Draft(path, text.toString(), project.storedHash(path), clock(), now.saving))
                journaled[path] = now
            } catch (failed: IOException) {
                failures[path] = failed
            }
        }
        for (path in journaled.keys.filter { it !in unsaved }) {
            try {
                store.remove(path)
                journaled.remove(path)
            } catch (failed: IOException) {
                // an orphaned draft is harmless: restore drops it when storage has the same text
                // and offers it otherwise; it is tried again by the next flush
            }
        }
        return failures
    }

    override suspend fun beforeWrite(path: String, text: String, baseHash: String?, revision: Long) {
        lock.withLock {
            try {
                store.put(Draft(path, text, baseHash, clock(), saving = true))
                journaled[path] = Journaled(revision, saving = true)
            } catch (failed: IOException) {
                // the write goes ahead (storage is the better place for the text); the next flush
                // writes the draft again and reports it if it fails once more
                journaled.remove(path)
            }
        }
    }

    override suspend fun unfinishedSave(path: String): String? = draft(path)?.takeIf { it.saving }?.text

    override suspend fun draftText(path: String): String? = draft(path)?.text

    private suspend fun draft(path: String): Draft? = lock.withLock {
        try {
            store.load().firstOrNull { it.path == path }
        } catch (unreadable: IOException) {
            null
        }
    }

    /**
     * Gives the unsaved text of [project] to [target], the drafts of the same project opened from
     * another place, and forgets this journal's own drafts once every one of them is there. When a
     * draft cannot be put, nothing is forgotten and the failure is thrown.
     */
    @Throws(IOException::class)
    suspend fun moveTo(project: InkProject, target: DraftStore) = lock.withLock {
        for (file in project.state.value.unsavedFiles) {
            val text = project.text(file.path) ?: continue
            target.put(Draft(file.path, text.toString(), project.storedHash(file.path), clock(), project.isSaveUnfinished(file.path)))
        }
        discardAllLocked()
        Unit
    }

    /**
     * Forgets every draft: the user chose "don't save" when closing. Drafts that cannot be removed
     * now are named in the result.
     */
    suspend fun discardAll(): List<String> = lock.withLock { discardAllLocked() }

    private suspend fun discardAllLocked(): List<String> {
        val kept = ArrayList<String>()
        val paths = try {
            (store.load().map { it.path } + journaled.keys).distinct()
        } catch (failed: IOException) {
            journaled.keys.toList()
        }
        for (path in paths) {
            try {
                store.remove(path)
                journaled.remove(path)
            } catch (failed: IOException) {
                kept.add(path)
            }
        }
        return kept
    }

    private suspend fun removeQuietly(path: String) {
        try {
            store.remove(path)
        } catch (failed: IOException) {
            // see flush: a draft that stays is looked at again on the next open
        }
    }
}

/**
 * Drafts as files in one directory, one file per draft, named after the hash of the project path.
 *
 * Each file is a small header and then the text, byte for byte:
 *
 * ```
 * inky-draft 1
 * path: chapters/one.ink
 * base: <hash of the stored version, or "-">
 * written: <milliseconds>
 * saving: 1                     (only in the draft of a save that has begun, Draft.saving)
 * sha256: <hash of the text>
 * <empty line>
 * <the text>
 * ```
 *
 * The text's own hash makes a truncated or damaged file recognisable: it is skipped. Writes go
 * through a temporary file that is forced to the disk and moved into place, so a draft is either
 * the old one or the new one.
 */
class FileDraftStore(
    private val directory: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DraftStore {

    override suspend fun load(): List<Draft> = withContext(io) {
        val files = directory.listFiles() ?: if (directory.exists()) {
            throw IOException("Cannot list ${directory.path}")
        } else {
            emptyArray()
        }
        files.filter { it.isFile && it.name.endsWith(EXTENSION) }.sortedBy { it.name }.mapNotNull(::parse)
    }

    override suspend fun put(draft: Draft) = withContext(io) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Cannot create ${directory.path}")
        }
        val target = fileFor(draft.path)
        val temporary = File(directory, target.name + TEMPORARY_SUFFIX)
        val header = buildString {
            append(MAGIC).append('\n')
            append(KEY_PATH).append(escape(draft.path)).append('\n')
            append(KEY_BASE).append(draft.baseHash ?: NO_BASE).append('\n')
            append(KEY_WRITTEN).append(draft.writtenAt).append('\n')
            if (draft.saving) append(KEY_SAVING).append(SAVING).append('\n')
            append(KEY_HASH).append(TextHash.of(draft.text)).append('\n')
            append('\n')
        }
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(header.toByteArray(Charsets.UTF_8))
                stream.write(draft.text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (notAtomic: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
        Unit
    }

    override suspend fun remove(path: String) = withContext(io) {
        val file = fileFor(path)
        if (file.exists() && !file.delete() && file.exists()) throw IOException("Cannot remove ${file.path}")
    }

    private fun fileFor(path: String) = File(directory, TextHash.of(path).take(NAME_LENGTH) + EXTENSION)

    /** The draft in [file], or null when it is not a whole draft. */
    private fun parse(file: File): Draft? {
        val content = try {
            String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)
        } catch (unreadable: IOException) {
            return null
        }
        val headerEnd = content.indexOf("\n\n")
        if (headerEnd < 0) return null
        val lines = content.substring(0, headerEnd).split('\n')
        if (lines.firstOrNull() != MAGIC) return null
        fun value(key: String): String? = lines.firstOrNull { it.startsWith(key) }?.substring(key.length)
        val path = unescape(value(KEY_PATH) ?: return null)
        val base = value(KEY_BASE) ?: return null
        val written = value(KEY_WRITTEN)?.toLongOrNull() ?: return null
        val hash = value(KEY_HASH) ?: return null
        val text = content.substring(headerEnd + 2)
        if (TextHash.of(text) != hash) return null
        return Draft(path, text, base.takeIf { it != NO_BASE }, written, saving = value(KEY_SAVING) == SAVING)
    }

    /** A path on one header line: a file name may hold a line break. */
    private fun escape(path: String): String = path.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescape(written: String): String {
        val path = StringBuilder(written.length)
        var index = 0
        while (index < written.length) {
            val char = written[index]
            if (char == '\\' && index + 1 < written.length) {
                index++
                path.append(
                    when (written[index]) {
                        'n' -> '\n'
                        'r' -> '\r'
                        else -> written[index]
                    },
                )
            } else {
                path.append(char)
            }
            index++
        }
        return path.toString()
    }

    private companion object {
        const val MAGIC = "inky-draft 1"
        const val KEY_PATH = "path: "
        const val KEY_BASE = "base: "
        const val KEY_WRITTEN = "written: "
        const val KEY_SAVING = "saving: "
        const val SAVING = "1"
        const val KEY_HASH = "sha256: "
        const val NO_BASE = "-"
        const val EXTENSION = ".draft"
        const val TEMPORARY_SUFFIX = ".tmp"
        const val NAME_LENGTH = 32
    }
}
