package com.inkyandroid.session

import com.inkyandroid.core.engine.InkIssue
import com.inkyandroid.core.engine.InkIssues
import com.inkyandroid.core.project.AddIncludeResult
import com.inkyandroid.core.project.DeleteResult
import com.inkyandroid.core.project.DraftJournal
import com.inkyandroid.core.project.DraftRestoreReport
import com.inkyandroid.core.project.DraftStore
import com.inkyandroid.core.project.InkFileParser
import com.inkyandroid.core.project.InkProject
import com.inkyandroid.core.project.LiveText
import com.inkyandroid.core.project.NavStep
import com.inkyandroid.core.project.NavigationHistory
import com.inkyandroid.core.project.ProjectStore
import com.inkyandroid.core.project.RenameResult
import com.inkyandroid.core.project.SaveReport
import com.inkyandroid.core.project.SaveResult
import com.inkyandroid.core.project.SyncReport
import com.inkyandroid.core.settings.ProjectSettings
import com.inkyandroid.core.syntax.InkTokenizer
import com.inkyandroid.settings.AppSettings
import com.inkyandroid.settings.FilePlace
import com.inkyandroid.settings.LastSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/**
 * Everything a window has open: the project, the editor of every file that was opened, the live
 * compile session, the place the user is at and the way back, and the machinery that keeps the
 * text safe.
 *
 * **Text flows one way.** While a file is open its editor buffer is the truth. Every change is
 * reported to the project model, which marks the file unsaved at once and lets the session know
 * that the content moved; from there the live session recompiles, a draft is written and, with
 * autosave, the file is saved.
 *
 * **No text is lost.**
 * - Every unsaved buffer is written to the drafts journal one second after the last keystroke (and
 *   at the latest five seconds after the first one of a burst), and when the app goes to the
 *   background ([flush]).
 * - With autosave (a setting, on by default) unsaved files are saved two seconds after the last
 *   edit and when the app goes to the background.
 * - A failed save leaves the file unsaved, keeps its draft and is reported in
 *   [SessionState.saveProblem]; [saveAll] is the retry.
 * - A file that changed on storage while it has unsaved changes is never overwritten and never
 *   reloaded by itself: it is a conflict in the project's snapshot until [resolveConflict].
 * - Opening a project brings its drafts back as unsaved changes ([SessionState.restoredDrafts]).
 *
 * Used from the main thread. Create one with [open]; the window's [ProjectSessionHolder] does.
 */
class ProjectSession private constructor(
    /** The project's reference in its string form, as it was given to [open]. */
    val reference: String,
    /** The project model: its `state` is the files, their flags and symbols. */
    val project: InkProject,
    private val scope: CoroutineScope,
    private val journal: DraftJournal,
    private val settings: AppSettings,
    private val editors: EditorBufferFactory,
    liveFactory: LiveLinkFactory,
    tokenizer: InkTokenizer,
    projectSettings: ProjectSettings,
    restored: DraftRestoreReport,
) {
    private val documents = LinkedHashMap<String, OpenDocument>()
    private val places = HashMap<String, FilePlace>()
    private val history = NavigationHistory()
    private var draftsDiscarded = false
    private var draftDebounce: Job? = null
    private var draftDeadline: Job? = null
    private var autosaveTimer: Job? = null
    private var nextRequestId = 1L
    private var selectedIssue = -1

    private val mutableState: MutableStateFlow<SessionState>

    /** What the window shows of the session; see [SessionState]. */
    val state: StateFlow<SessionState>

    /** The live compile session's link: the session feeds it; the screens read the player from the live session itself. */
    val live: LiveLink = liveFactory.create(scope) { project.sources() }

    init {
        val last = settings.lastSession?.takeIf { it.project == reference }
        last?.places?.let(places::putAll)
        val active = last?.activeFile?.takeIf { it in project } ?: project.mainPath
        mutableState = MutableStateFlow(
            SessionState(
                activePath = active,
                tokenizer = tokenizer,
                projectSettings = projectSettings,
                restoredDrafts = restored.takeUnless { it.isEmpty },
            ),
        )
        state = mutableState.asStateFlow()
        // upstream: a new project resets the history and adds its first step
        history.add(NavStep(active, places[active]?.row ?: 0, places[active]?.column ?: 0))

        scope.launch { project.contentRevision.drop(1).collect { contentChanged() } }
        scope.launch { project.state.collect { projectChanged() } }
        scope.launch { live.issues.collect(::issuesChanged) }
        // restored drafts are unsaved changes like any other: journal and autosave take over
        if (project.state.value.hasUnsavedChanges) scheduleAutosave()
        rememberSession()
        live.setInstructionPrefix(projectSettings.instructionPrefix)
        live.reloadNow()
    }

    // ------------------------------------------------------------------ documents

    /**
     * The open document of [path], created the first time it is asked for and kept until the
     * project is closed, so each file keeps its undo history, cursor and scroll position. Null for
     * a path that is not a file of the project.
     */
    fun document(path: String): OpenDocument? {
        documents[path]?.let { return it }
        val text = project.text(path) ?: return null
        val buffer = editors.create(path, text.toString())
        val document = OpenDocument(path, buffer)
        places[path]?.let { place ->
            buffer.cursor = TextPosition(place.row, place.column)
            buffer.firstVisibleRow = place.firstVisibleRow
        }
        buffer.onTextChanged = { project.textChanged(document.path) }
        project.attach(
            path,
            object : LiveText {
                override fun snapshot(): CharSequence = buffer.text
                override fun load(text: String) = buffer.load(text)
                override fun edit(start: Int, end: Int, replacement: String) = buffer.edit(start, end, replacement)
            },
        )
        documents[path] = document
        return document
    }

    /** The document of the file the editor shows. */
    val activeDocument: OpenDocument
        get() = document(state.value.activePath) ?: checkNotNull(document(project.mainPath)) { "A project always has its main file" }

    // ------------------------------------------------------------------ where the user is

    /**
     * Shows [path] in the editor, at [position] when one is given (upstream's `showInkFile` and
     * `gotoLine`): the file becomes the active one and a [ShowRequest] is published for the screen
     * to execute. With [addStep] the place joins the back / forward history, as upstream does for
     * file switches and jumps. Returns false, changing nothing, when the project has no such file
     * (upstream: the editor stays where it is).
     */
    fun show(path: String, position: TextPosition? = null, addStep: Boolean = true): Boolean {
        val document = document(path) ?: return false
        if (position != null) document.buffer.cursor = position
        val at = position ?: document.buffer.cursor
        if (addStep) history.add(NavStep(path, at.row, at.column))
        mutableState.update {
            it.copy(
                activePath = path,
                showRequest = ShowRequest(nextRequestId++, path, position),
                canGoBack = history.canGoBack,
                canGoForward = history.canGoForward,
            )
        }
        rememberSession()
        return true
    }

    /** The screen has executed [request]. A newer request stays. */
    fun showHandled(request: ShowRequest) {
        mutableState.update { if (it.showRequest?.id == request.id) it.copy(showRequest = null) else it }
    }

    /**
     * The user moved the cursor on purpose with a click or a tap in the editor: the place joins the
     * history (upstream adds a step on every plain click; not on keyboard movement).
     */
    fun navigated(position: TextPosition) {
        if (history.add(NavStep(state.value.activePath, position.row, position.column))) historyChanged()
    }

    /** Upstream's back button: shows the previous place of the history (the row; the column is not restored). */
    fun back() {
        history.back()?.let { show(it.path, TextPosition(it.row), addStep = false) }
        historyChanged()
    }

    /** Upstream's forward button. */
    fun forward() {
        history.forward()?.let { show(it.path, TextPosition(it.row), addStep = false) }
        historyChanged()
    }

    private fun historyChanged() {
        mutableState.update { it.copy(canGoBack = history.canGoBack, canGoForward = history.canGoForward) }
    }

    // ------------------------------------------------------------------ issues and watches

    /** Upstream's `gotoIssue`: shows the issue's file at its line and adds a history step. False when the issue names no file of the project. */
    fun showIssue(issue: InkIssue): Boolean {
        val path = issue.file ?: return false
        return show(path, TextPosition(((issue.line ?: 1) - 1).coerceAtLeast(0)))
    }

    /** Upstream's "Next Issue" (Ctrl+.): goes to the issue after the one last gone to, wrapping; starts over after every recompile. */
    fun nextIssue(): Boolean {
        val issues = state.value.issues
        if (issues.isEmpty()) return false
        selectedIssue = (selectedIssue + 1) % issues.size
        return showIssue(issues[selectedIssue])
    }

    /** Sets the watch expressions (upstream's expression watch view); the live session evaluates them after every turn. */
    fun setWatches(expressions: List<String>) {
        mutableState.update { it.copy(watches = expressions) }
        live.setWatches(expressions)
    }

    private fun issuesChanged(reported: List<InkIssue>) {
        selectedIssue = -1
        mutableState.update { it.copy(issues = InkIssues.sorted(reported)) }
    }

    // ------------------------------------------------------------------ files

    /**
     * Upstream's "add new include": creates the file ([InkProject.addInclude]) and, when that
     * worked, shows it and adds a history step.
     */
    suspend fun addInclude(name: String, addToMain: Boolean): AddIncludeResult {
        val result = project.addInclude(name, addToMain)
        if (result is AddIncludeResult.Added) show(result.path)
        return result
    }

    /** Renames a file ([InkProject.rename]); its open document, its history steps and its place follow it. */
    suspend fun rename(path: String, newName: String): RenameResult {
        val result = project.rename(path, newName)
        if (result is RenameResult.Renamed && result.from != result.to) {
            documents.remove(result.from)?.let { document ->
                document.path = result.to
                documents[result.to] = document
            }
            places.remove(result.from)?.let { places[result.to] = it }
            history.renamed(result.from, result.to)
            mutableState.update { if (it.activePath == result.from) it.copy(activePath = result.to) else it }
            rememberSession()
            writeDrafts()
        }
        return result
    }

    /** Deletes a file from the project and from storage ([InkProject.delete]). If it was the one shown, the main file is shown. */
    suspend fun delete(path: String): DeleteResult {
        val result = project.delete(path)
        if (result is DeleteResult.Deleted) {
            projectChanged()
            writeDrafts()
        }
        return result
    }

    private fun projectChanged() {
        val gone = documents.keys.filter { it !in project }
        for (path in gone) {
            documents.remove(path)?.buffer?.close()
            places.remove(path)
            history.removed(path)
        }
        if (state.value.activePath !in project) show(project.mainPath, addStep = false)
        if (gone.isNotEmpty()) historyChanged()
    }

    // ------------------------------------------------------------------ saving

    /**
     * Saves every file with unsaved changes (upstream's "Save Project"; also the "retry" of the
     * save banner). Failures are in the result and in [SessionState.saveProblem]; conflicts stay in
     * the project's snapshot. Drafts of the files that were saved are removed.
     */
    suspend fun saveAll(): SaveReport {
        mutableState.update { it.copy(saving = true) }
        val report = try {
            project.saveAll()
        } finally {
            mutableState.update { it.copy(saving = false) }
        }
        val failed = report.failed
        mutableState.update { it.copy(saveProblem = if (failed.isEmpty()) null else SaveProblem(failed.mapValues { (_, error) -> describe(error) }, failed)) }
        writeDrafts()
        return report
    }

    /** Discards the unsaved changes of [path] and shows storage's version again. False when there is none to go back to. */
    suspend fun revert(path: String): Boolean {
        val reverted = try {
            project.revert(path)
        } catch (failed: IOException) {
            storageFailed(failed)
            false
        }
        if (reverted) writeDrafts()
        return reverted
    }

    /**
     * Answers the banner of a file that changed outside while it has unsaved changes: [keepMine]
     * writes this window's text over storage's, otherwise storage's version is loaded and the
     * unsaved changes are dropped.
     */
    suspend fun resolveConflict(path: String, keepMine: Boolean) {
        try {
            val result = project.resolveConflict(path, keepMine)
            if (result is SaveResult.Failed) {
                mutableState.update { it.copy(saveProblem = SaveProblem(mapOf(path to describe(result.error)), mapOf(path to result.error))) }
            }
        } catch (failed: IOException) {
            // storage's version cannot be read ("load theirs"): this text stays, unsaved
            storageFailed(failed)
        }
        writeDrafts()
    }

    // ------------------------------------------------------------------ the window's life

    /**
     * Looks at storage for changes made outside the app ([InkProject.syncWithStorage]); the
     * window's holder calls it when the window gains focus, and "reload from storage" calls it on
     * demand. Null when storage could not be reached: the reason is in [SessionState.storageProblem].
     */
    suspend fun syncWithStorage(): SyncReport? {
        val report = try {
            project.syncWithStorage()
        } catch (failed: IOException) {
            storageFailed(failed)
            return null
        }
        mutableState.update { it.copy(storageProblem = null, storageError = null) }
        if (report.settingsChanged) reloadProjectSettings()
        return report
    }

    private fun storageFailed(error: IOException) {
        mutableState.update { it.copy(storageProblem = describe(error), storageError = error) }
    }

    /**
     * The app goes to the background, where the system may kill it without a word: remembers where
     * the user is, writes every draft now and, with autosave, saves.
     */
    suspend fun flush() {
        rememberSession()
        writeDrafts()
        if (settings.autosave && project.state.value.hasUnsavedChanges) saveAll()
    }

    /** [flush] for callers that cannot wait (a lifecycle callback): it runs in the session's own scope. */
    fun flushInBackground() {
        scope.launch { flush() }
    }

    /**
     * The user wants to close the project. True when it is closed: there was nothing unsaved, or
     * autosave could save everything. Otherwise the session stays open and
     * [SessionState.closeQuestion] asks upstream's question ("Would you like to save changes before
     * exiting?"); the answer goes to [answerClose].
     */
    suspend fun requestClose(): Boolean {
        if (settings.autosave && project.state.value.hasUnsavedChanges) saveAll()
        if (!project.state.value.hasUnsavedChanges) {
            finish()
            return true
        }
        mutableState.update { it.copy(closeQuestion = true) }
        return false
    }

    /**
     * The answer to the close question. [CloseAnswer.Save] saves and closes if everything could be
     * saved (upstream saves and leaves the window open; closing is what was meant).
     * [CloseAnswer.DontSave] drops the unsaved changes and their drafts and closes.
     * [CloseAnswer.Cancel] keeps the project open. Returns whether the session is closed.
     */
    suspend fun answerClose(answer: CloseAnswer): Boolean {
        mutableState.update { it.copy(closeQuestion = false) }
        when (answer) {
            CloseAnswer.Cancel -> return false
            CloseAnswer.Save -> {
                saveAll()
                if (project.state.value.hasUnsavedChanges) return false
            }
            CloseAnswer.DontSave -> {
                cancelTimers()
                // a draft write under way must not bring back what was just thrown away
                draftsDiscarded = true
                journal.discardAll()
            }
        }
        finish()
        return true
    }

    /** Dismisses the "unsaved changes were restored" notice. */
    fun dismissRestoredNotice() {
        mutableState.update { it.copy(restoredDrafts = null) }
    }

    /**
     * Ends the session without asking: the window is destroyed, or another project takes its place.
     * What [flush] does is done one last time (drafts, and with autosave a save); unsaved text that
     * stays unsaved is in the drafts and comes back when the project is opened again. The returned
     * job ends when that last flush is done (the holder gives the project's storage back then).
     */
    fun abandon(): Job {
        if (state.value.closed) return Job().apply { complete() }
        mutableState.update { it.copy(closed = true) }
        live.close()
        cancelTimers()
        return scope.launch {
            try {
                flush()
            } finally {
                closeDocuments()
                scope.cancel()
            }
        }
    }

    /**
     * Ends the session and hands its unsaved changes to a successor: the same project opened from
     * another place ([ProjectSessionHolder.switchTo]). Nothing is saved. The unsaved texts go into
     * [target], the successor's drafts, and are forgotten here only once all of them are there; a
     * null [target] means the successor keeps its drafts where this session does: they are brought
     * up to date and left. When that fails the session goes on as it was and the failure is thrown.
     */
    @Throws(IOException::class)
    internal suspend fun handOver(target: DraftStore?) {
        if (state.value.closed) return
        do {
            val revision = project.contentRevision.value
            if (target != null) {
                journal.moveTo(project, target)
            } else {
                journal.flush(project).values.firstOrNull()?.let { throw it }
            }
            // typed while the drafts moved: move them again
        } while (project.contentRevision.value != revision)
        cancelTimers()
        rememberSession()
        mutableState.update { it.copy(closed = true) }
        live.close()
        closeDocuments()
        scope.cancel()
    }

    private fun finish() {
        rememberSession()
        // the window closed its project on purpose: the next start does not reopen it
        if (settings.lastSession?.project == reference) settings.updateLastSession(null)
        mutableState.update { it.copy(closed = true) }
        live.close()
        closeDocuments()
        scope.cancel()
    }

    private fun closeDocuments() {
        for (document in documents.values) document.buffer.close()
    }

    // ------------------------------------------------------------------ the machinery

    private fun contentChanged() {
        live.edited()
        scheduleDrafts()
        scheduleAutosave()
    }

    private fun scheduleDrafts() {
        draftDebounce?.cancel()
        draftDebounce = scope.launch {
            delay(DRAFT_DELAY_MILLIS)
            draftDeadline?.cancel()
            draftDeadline = null
            // in a job of its own: the next keystroke restarts the timer, never a write under way
            scope.launch { writeDrafts() }
        }
        if (draftDeadline?.isActive != true) {
            draftDeadline = scope.launch {
                delay(DRAFT_MAX_WAIT_MILLIS)
                draftDeadline = null
                draftDebounce?.cancel()
                scope.launch { writeDrafts() }
            }
        }
    }

    private fun scheduleAutosave() {
        autosaveTimer?.cancel()
        if (!settings.autosave) return
        autosaveTimer = scope.launch {
            delay(AUTOSAVE_DELAY_MILLIS)
            if (settings.autosave && project.state.value.hasUnsavedChanges) scope.launch { saveAll() }
        }
    }

    private fun cancelTimers() {
        draftDebounce?.cancel()
        draftDeadline?.cancel()
        autosaveTimer?.cancel()
    }

    private suspend fun writeDrafts() {
        if (draftsDiscarded) return
        draftsFailed(journal.flush(project).values.firstOrNull())
    }

    /** The drafts could not be written ([error]), or could again (null). */
    internal fun draftsFailed(error: IOException?) {
        val problem = error?.let(::describe)
        mutableState.update { if (it.draftProblem == problem) it else it.copy(draftProblem = problem, draftError = error) }
    }

    private suspend fun reloadProjectSettings() {
        val loaded = readProjectSettings(project.store, project.mainPath)
        val previous = state.value
        if (loaded == previous.projectSettings) return
        if (loaded.instructionPrefix != previous.projectSettings.instructionPrefix) {
            val tokenizer = InkTokenizer(loaded.instructionPrefix)
            mutableState.update { it.copy(projectSettings = loaded, tokenizer = tokenizer) }
            project.setParser(InkFileParser.of(tokenizer))
            live.setInstructionPrefix(loaded.instructionPrefix)
        } else {
            mutableState.update { it.copy(projectSettings = loaded) }
        }
    }

    /** Stores where the user is, so the next start (or the restart after the system killed the app) returns there. */
    private fun rememberSession() {
        for ((path, document) in documents) {
            val cursor = document.buffer.cursor
            places[path] = FilePlace(cursor.row, cursor.column, document.buffer.firstVisibleRow)
        }
        settings.updateLastSession(LastSession(reference, state.value.activePath, places.toMap()))
    }

    companion object {
        /** A draft is written this long after the last keystroke. */
        const val DRAFT_DELAY_MILLIS = 1_000L

        /** While the typing never pauses that long, a draft is still written this long after the first unjournaled keystroke. */
        const val DRAFT_MAX_WAIT_MILLIS = 5_000L

        /** With autosave, unsaved files are saved this long after the last edit. */
        const val AUTOSAVE_DELAY_MILLIS = 2_000L

        /**
         * Opens the project behind [store] with [mainPath] as its main file: reads the project's
         * settings file, loads and parses the files, brings the drafts back, and returns to the
         * file and the places the window was at when it last showed this project ([reference]).
         *
         * Throws an `IOException` when the project cannot be opened (storage unreachable, the main
         * file missing or not readable); nothing is left running then.
         *
         * @param reference the project's reference in its string form; stored in the settings as
         *   the last session and compared with it to find the places to restore.
         * @param drafts the drafts store of this project, in app-private storage.
         * @param context the session's thread: the main thread in the app. A `Job` in it becomes the
         *   parent of everything the session runs.
         * @param parseContext where symbol parsing runs.
         */
        @Throws(IOException::class)
        suspend fun open(
            reference: String,
            store: ProjectStore,
            mainPath: String,
            drafts: DraftStore,
            settings: AppSettings,
            editors: EditorBufferFactory,
            live: LiveLinkFactory,
            context: CoroutineContext = Dispatchers.Main.immediate,
            parseContext: CoroutineContext = Dispatchers.Default,
        ): ProjectSession = withContext(context.minusKey(Job)) {
            // a child of the job in [context] when there is one: ending that job ends the session
            val scope = CoroutineScope(context + SupervisorJob(context[Job]))
            try {
                val projectSettings = readProjectSettings(store, mainPath)
                val tokenizer = InkTokenizer(projectSettings.instructionPrefix)
                // the journal hears of every write before it begins: a write cut short loses nothing
                val journal = DraftJournal(drafts)
                val project = InkProject.open(scope, store, mainPath, InkFileParser.of(tokenizer), parseContext, writeAhead = journal)
                val restored = try {
                    journal.restore(project)
                } catch (unreadable: IOException) {
                    // the project is as storage has it; the journal will say so when it cannot write either
                    DraftRestoreReport()
                }
                ProjectSession(reference, project, scope, journal, settings, editors, live, tokenizer, projectSettings, restored)
            } catch (failed: Throwable) {
                scope.cancel()
                throw failed
            }
        }

        /** The project's settings file; a missing or unreadable one is no settings (upstream: custom snippets vanish, nothing else). */
        private suspend fun readProjectSettings(store: ProjectStore, mainPath: String): ProjectSettings {
            val path = ProjectSettings.pathFor(mainPath)
            return try {
                if (store.stat(path) == null) ProjectSettings.EMPTY else ProjectSettings.parse(store.read(path))
            } catch (unreadable: IOException) {
                ProjectSettings.EMPTY
            }
        }

        private fun describe(error: IOException): String = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }
}
