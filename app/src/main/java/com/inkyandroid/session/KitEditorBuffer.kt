package com.inkyandroid.session

import androidx.compose.runtime.snapshotFlow
import desktopkit.editor.CodeEditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * The editor buffer of one open file over the kit's [CodeEditorState]: [state] is what the screens
 * give `CodeEditor`, the rest is what the project session reads and changes.
 *
 * **Line breaks.** The kit's editor knows only `\n`: it turns every `\r\n` and `\r` into `\n`. A
 * file keeps its own line breaks all the same, as in upstream's Ace editor ("auto" mode): the
 * first line break of the text the file was read with decides, [text] gives the editor's text back
 * with it, and the offsets of [edit] count it as stored. (A file that mixes line breaks is written
 * with its first one throughout once it is saved, as upstream does.)
 *
 * **Changes** are noticed through [CodeEditorState.version], which is snapshot state: [onTextChanged]
 * runs on the main thread once Compose has applied the change (within the frame), never for
 * [load]. [text] is always the text as it is now.
 *
 * Main thread only.
 *
 * @param watchContext where changes are watched: the main thread.
 */
class KitEditorBuffer(
    text: String,
    watchContext: CoroutineContext = Dispatchers.Main.immediate,
) : EditorBuffer {

    /** The document for `CodeEditor`. */
    val state = CodeEditorState(text)

    private var lineBreak = LineBreak.of(text)

    /** The version last reported through [onTextChanged], or left by [load] (which is never reported). */
    private var reportedVersion = state.version
    private var cachedVersion = -1
    private var cached = ""
    private var viewRow = 0
    private val watcher = CoroutineScope(SupervisorJob() + watchContext)

    init {
        watcher.launch {
            snapshotFlow { state.version }.collect { version ->
                if (version != reportedVersion) {
                    reportedVersion = version
                    onTextChanged?.invoke()
                }
            }
        }
    }

    override val hasUnreportedChanges: Boolean get() = state.version != reportedVersion

    override val text: CharSequence
        get() {
            val version = state.version
            if (version != cachedVersion) {
                // once per change, and only when someone asks (parse, compile, draft, save)
                cached = lineBreak.restore(state.text)
                cachedVersion = version
            }
            return cached
        }

    override fun load(text: String) {
        val at = state.cursorPosition
        lineBreak = LineBreak.of(text)
        state.setText(text)
        reportedVersion = state.version
        // the kit puts the cursor at the start; the writer stays where they were as far as the new text allows
        state.select(state.offsetOf(at.line.coerceAtMost(state.lineCount - 1), at.column))
        state.reveal(state.cursor)
    }

    override fun edit(start: Int, end: Int, replacement: String) {
        val stored = text
        state.replace(lineBreak.toEditor(stored, start), lineBreak.toEditor(stored, end), replacement)
    }

    override var cursor: TextPosition
        get() = state.cursorPosition.let { TextPosition(it.line, it.column) }
        set(value) = state.select(state.offsetOf(value.row, value.column))

    /**
     * The kit's editor does not tell its scroll position yet (requested): the value set here is
     * kept and read back, and setting it brings the cursor into view when the editor shows the file,
     * so a restored window returns to the cursor's line.
     */
    override var firstVisibleRow: Int
        get() = viewRow
        set(value) {
            viewRow = value
            state.reveal(state.cursor)
        }

    override var onTextChanged: (() -> Unit)? = null

    override fun close() {
        watcher.cancel()
        onTextChanged = null
    }

    companion object {
        /** The app's editors: one kit document per opened file. */
        val Factory = EditorBufferFactory { _, text -> KitEditorBuffer(text) }
    }
}

/** The line break a file is written with, as upstream's Ace editor finds it: the first one in the text. */
internal enum class LineBreak(private val chars: String) {
    Lf("\n"),
    CrLf("\r\n"),
    Cr("\r"),
    ;

    /** The editor's text, whose line breaks are all `\n`, with this line break. */
    fun restore(editorText: String): String = if (this == Lf) editorText else editorText.replace("\n", chars)

    /**
     * [offset] into [fileText] (the editor's text as [restore] gives it) as an offset into the
     * editor's text. An offset inside a `\r\n` lands before the line break.
     */
    fun toEditor(fileText: CharSequence, offset: Int): Int {
        if (this != CrLf) return offset
        var pairs = 0
        var index = fileText.indexOf("\r\n")
        while (index in 0 until offset) {
            pairs++
            index = fileText.indexOf("\r\n", index + 2)
        }
        return offset - pairs
    }

    companion object {
        fun of(text: CharSequence): LineBreak {
            val index = text.indexOfFirst { it == '\r' || it == '\n' }
            return when {
                index < 0 || text[index] == '\n' -> Lf
                index + 1 < text.length && text[index + 1] == '\n' -> CrLf
                else -> Cr
            }
        }
    }
}
