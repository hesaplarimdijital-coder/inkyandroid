package com.inkyandroid.ui.workspace

import com.inkyandroid.ink.InkLink
import com.inkyandroid.session.TextPosition

/**
 * The editor that is on screen, as the window's commands see it: the menu bar, the "⋯" sheet, the
 * palette and the keys act on it through this, whatever has the focus when they are used.
 * The editor pane registers itself while it is shown.
 */
interface ActiveEditor {
    /** The project path of the file being edited. */
    val path: String

    val canUndo: Boolean
    val canRedo: Boolean

    fun undo()

    fun redo()

    fun cut()

    fun copy()

    fun paste()

    fun selectAll()

    /**
     * Inserts [text] at the cursor as if it were typed: the selection is replaced, the cursor ends
     * [cursorBack] characters before the text's end (0: behind it), and it is one step to undo.
     */
    fun insert(text: String, cursorBack: Int = 0)

    /** Gives the editor the keyboard focus. */
    fun focus()

    /** Opens the find bar over the editor, with the replace row when [replace]; a selection on one line is what is looked for. */
    fun find(replace: Boolean)

    /** Opens the list of suggestions at the cursor, as Ctrl+Space does in the editor. */
    fun showCompletion()

    /** Where the cursor is. */
    val cursor: TextPosition

    /** The divert target or include path the cursor is on, if it is on one. */
    fun linkAtCursor(): InkLink?
}
