package com.inkyandroid.session

/** A place in a file's text. Rows and columns are zero-based; columns count UTF-16 units. */
data class TextPosition(val row: Int, val column: Int = 0)

/**
 * What the project session needs of the editor of one open file: the narrowest view of the kit's
 * `CodeEditorState`. The session keeps one buffer per opened file for as long as the project is
 * open, so every file keeps its text, cursor, scroll position and undo history.
 *
 * The seam exists so the session's rules (unsaved state, drafts, autosave, restore) are tested with
 * a plain buffer, and so they do not depend on how the editor component is built.
 *
 * All calls are made on the main thread.
 */
interface EditorBuffer {

    /** The text right now, as a value that does not change afterwards. Cheap: the session reads it for every parse, compile and draft. */
    val text: CharSequence

    /**
     * Storage's version replaces the text (an outside change, a revert): show exactly [text] and
     * forget the undo history. The cursor and the scroll position stay where they were as far as
     * the new text allows. This is not an edit: [onTextChanged] must not be called for it.
     */
    fun load(text: String)

    /**
     * An edit the app makes itself (an `INCLUDE` line inserted or rewritten): replaces the
     * characters from [start] to [end] by [replacement], as one step the user can undo.
     */
    fun edit(start: Int, end: Int, replacement: String)

    /** Where the cursor is; setting it moves the cursor without scrolling. */
    var cursor: TextPosition

    /** The first row that is visible, for returning to the same view after a restart. */
    var firstVisibleRow: Int

    /**
     * Called after every change of the text that the user made or that came through [edit]; never
     * for [load]. The session sets it once, when it creates the buffer.
     */
    var onTextChanged: (() -> Unit)?
}

/** Creates the editor buffer of a file the first time the file is opened. */
fun interface EditorBufferFactory {
    fun create(path: String, text: String): EditorBuffer
}

/**
 * A file that has been opened in this session, with its editor. The same object for as long as the
 * project is open; [path] follows a rename.
 */
class OpenDocument internal constructor(path: String, val buffer: EditorBuffer) {
    var path: String = path
        internal set
}
