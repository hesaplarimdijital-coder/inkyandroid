package com.inkyandroid.session

import com.inkyandroid.core.engine.InkIssue
import com.inkyandroid.core.project.DraftRestoreReport
import com.inkyandroid.core.settings.ProjectSettings
import com.inkyandroid.core.syntax.InkTokenizer
import java.io.IOException

/**
 * A request to bring a place into view: make [path] the file the editor shows and, when [position]
 * is given, move the cursor there and scroll it into view (upstream's `showInkFile` + `gotoLine`).
 * Without a position the file comes back as it was left.
 *
 * It stays in [SessionState.showRequest] until the screen that executed it says so with
 * [ProjectSession.showHandled], so a request made while no editor is on screen is not lost.
 */
data class ShowRequest(val id: Long, val path: String, val position: TextPosition?)

/**
 * The files the last save could not write, with the reason for each: the data of the "could not
 * save" banner. [errors] has the failures themselves, for a sentence in the user's language
 * (`StorageProblem.of(error)` of the storage package).
 */
data class SaveProblem(val failed: Map<String, String>, val errors: Map<String, IOException> = emptyMap())

/** What [ProjectSession.restoreKept] did. */
enum class KeptRestore {
    /** The kept text is its file's text again, as an edit Undo takes out; the text it replaced is kept in turn. */
    Restored,

    /** The file is no longer in the project: nothing changed (the text is still in the version). */
    FileGone,

    /** The file's text could not be kept first, so nothing was replaced; [SessionState.draftProblem] says why. */
    NotKept,
}

/** The three answers to upstream's "Would you like to save changes before exiting?". */
enum class CloseAnswer { Save, DontSave, Cancel }

/**
 * What a window shows of its open project besides the project's own snapshot
 * ([ProjectSession.project]`.state`). Immutable.
 *
 * @property activePath the file the editor shows.
 * @property showRequest the place to bring into view, until it is handled.
 * @property canGoBack whether [ProjectSession.back] leads somewhere; likewise [canGoForward].
 * @property saving a save is under way.
 * @property saveProblem the last save failed for these files; cleared by a save that succeeds.
 *   The files are still unsaved and their drafts are kept.
 * @property storageProblem the last look at storage failed (the folder is unreachable, permission
 *   was withdrawn): the reason. Cleared by a look that succeeds. [storageError] is the failure
 *   itself, for a sentence in the user's language (`StorageProblem.of(error)`).
 * @property draftProblem drafts could not be written (the device is full): the reason; [draftError]
 *   the failure itself.
 * @property restoredDrafts unsaved changes that were brought back when the project was opened,
 *   until the notice is dismissed with [ProjectSession.dismissRestoredNotice]; null when there were none.
 * @property closeQuestion the user has to answer the save / don't save / cancel question
 *   ([ProjectSession.answerClose]).
 * @property issues the problems of the latest compile and run, in upstream's order
 *   (`InkIssues.upstreamOrder`: errors, runtime errors, warnings, runtime warnings, TODOs; by line
 *   within each).
 * @property watches the watch expressions.
 * @property projectSettings the project's settings file (custom snippets, instruction prefix).
 * @property tokenizer the tokenizer for the project's instruction prefix: the editor colours with it.
 * @property closed the session is over; the window shows no project.
 */
data class SessionState(
    val activePath: String,
    val tokenizer: InkTokenizer,
    val projectSettings: ProjectSettings = ProjectSettings.EMPTY,
    val showRequest: ShowRequest? = null,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val saving: Boolean = false,
    val saveProblem: SaveProblem? = null,
    val storageProblem: String? = null,
    val draftProblem: String? = null,
    val restoredDrafts: DraftRestoreReport? = null,
    val closeQuestion: Boolean = false,
    val issues: List<InkIssue> = emptyList(),
    val watches: List<String> = emptyList(),
    val closed: Boolean = false,
    val storageError: IOException? = null,
    val draftError: IOException? = null,
) {
    /** The issues of one file: the editor's markers ([InkIssue.line] is one-based). */
    fun issuesOf(path: String): List<InkIssue> = issues.filter { it.file == path }

    /** How many issues each file has, for the files view's badges. */
    val issueCounts: Map<String, Int> get() = issues.mapNotNull { it.file }.groupingBy { it }.eachCount()
}
