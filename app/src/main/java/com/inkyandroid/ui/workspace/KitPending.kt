package com.inkyandroid.ui.workspace

import androidx.compose.runtime.snapshotFlow
import com.inkyandroid.session.EditorBuffer
import com.inkyandroid.session.EditorBufferFactory
import com.inkyandroid.session.TextPosition
import desktopkit.editor.CodeEditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

// BRANCH ONLY (port/app-ui), never integrated: app-session's KitEditorBuffer (requested in
// requests.md, 01:15) replaces this file; editorStateOf then reads `(buffer as KitEditorBuffer).state`.

private class TemporaryKitBuffer(initial: String) : EditorBuffer {
    val state = CodeEditorState(initial)
    private var loadedVersion = -1

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            snapshotFlow { state.version }.drop(1).collect { version -> if (version != loadedVersion) onTextChanged?.invoke() }
        }
    }

    override val text: CharSequence get() = state.text

    override fun load(text: String) {
        val cursor = state.cursorPosition
        state.setText(text)
        loadedVersion = state.version
        state.select(state.offsetOf(cursor.line, cursor.column))
    }

    override fun edit(start: Int, end: Int, replacement: String) = state.replace(start, end, replacement)

    override var cursor: TextPosition
        get() = state.cursorPosition.let { TextPosition(it.line, it.column) }
        set(value) = state.select(state.offsetOf(value.row, value.column))

    override var firstVisibleRow: Int = 0
    override var onTextChanged: (() -> Unit)? = null
}

fun editorBuffers(): EditorBufferFactory = EditorBufferFactory { _, text -> TemporaryKitBuffer(text) }

fun editorStateOf(buffer: EditorBuffer): CodeEditorState = (buffer as TemporaryKitBuffer).state
