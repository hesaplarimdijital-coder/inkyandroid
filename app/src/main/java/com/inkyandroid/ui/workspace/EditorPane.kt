package com.inkyandroid.ui.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.inkyandroid.R
import com.inkyandroid.core.engine.InkIssue
import com.inkyandroid.core.engine.IssueKind
import com.inkyandroid.core.project.ProjectSnapshot
import com.inkyandroid.ink.InkEditorTokenizer
import com.inkyandroid.ink.InkLineCache
import com.inkyandroid.ink.InkLink
import com.inkyandroid.ink.InkLinks
import com.inkyandroid.session.ProjectSession
import com.inkyandroid.session.SessionState
import com.inkyandroid.session.TextPosition
import com.inkyandroid.settings.AppSettings
import com.inkyandroid.settings.EditorFace
import com.inkyandroid.ui.WindowActions
import com.inkyandroid.ui.WindowUi
import desktopkit.components.MenuEntry
import desktopkit.editor.CodeEditor
import desktopkit.editor.CodeEditorState
import desktopkit.editor.CodeEditorZoom
import desktopkit.editor.CodeIndent
import desktopkit.editor.CodeLinks
import desktopkit.editor.LineMarker
import desktopkit.editor.MarkerSeverity
import desktopkit.input.hasHardwareKeyboard
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The editor of the file the session has active: the kit's code editor on that file's own state
 * (each file keeps its text, cursor, scroll and undo history), coloured by `:core`'s port of
 * upstream's ink mode, with the file's issues as gutter marks and line tints.
 *
 * As upstream's Alt+click: a divert's target and an `INCLUDE` path are links (Ctrl+click or
 * Alt+click with a mouse); "Go to definition" is in the context menu and in the selection menu of
 * touch, where there are no modifier keys. A plain click that moves the cursor is a step of the
 * navigation history, as upstream adds one.
 *
 * On a phone with the on-screen keyboard up, a strip of ink's symbols sits right above the keyboard.
 *
 * @param contentPadding room at the text's ends: on a phone the shell's bars, which the text runs under.
 */
@Composable
fun EditorPane(
    session: ProjectSession,
    state: SessionState,
    project: ProjectSnapshot,
    settings: AppSettings,
    ui: WindowUi,
    actions: WindowActions,
    zoom: CodeEditorZoom,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val path = state.activePath
    val document = remember(session, path) { session.document(path) } ?: return
    val editor = document.editorState ?: return
    val tokenizer = remember(state.tokenizer) { InkEditorTokenizer(state.tokenizer) }
    val lines = remember(document) { EditorLines(InkLineCache(state.tokenizer)) }
    lines.tokenizer = state.tokenizer
    val markers = remember(state.issues, path) { markersOf(state.issuesOf(path)) }
    val context = LocalContext.current
    val goTo = stringResource(R.string.action_goto_definition)
    val label = stringResource(R.string.editor_label, document.path.substringAfterLast('/'))
    val placeholder = stringResource(R.string.editor_placeholder)
    val follow: (InkLink) -> Unit = { link -> actions.follow(link, document.path, editor.cursorPosition.line) }
    val links = remember(lines) {
        CodeLinks { doc, offset -> lines.linkAt(doc, offset)?.let { link -> lines.rangeOf(doc, link, offset) } }
    }

    // "show this file at this place": the session asks, the editor that shows the file does it
    val request = state.showRequest
    LaunchedEffect(request, path) {
        if (request != null && request.path == path) {
            request.position?.let { position -> editor.reveal(editor.offsetOf(position.row, position.column), focus = true) }
            session.showHandled(request)
        }
    }

    // the menus, the palette and the keys act on this editor while it is on screen
    val active = remember(editor, document) { KitActiveEditor(editor, { document.path }, lines, context) }
    DisposableEffect(active) {
        ui.editor = active
        onDispose { if (ui.editor === active) ui.editor = null }
    }

    var focused by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val keyboardUp = WindowInsets.ime.getBottom(density) > 0
    val strip = focused && keyboardUp && !hasHardwareKeyboard()
    // a click that followed a link may have shown another file meanwhile: the step is only this file's
    val navigated by rememberUpdatedState {
        if (session.state.value.activePath == document.path) session.navigated(TextPosition(editor.cursorPosition.line, editor.cursorPosition.column))
    }

    Column(modifier.fillMaxSize()) {
        // the banners stand under the phone's top bar; without them the text runs under it
        val banners = hasBanners(state, project, ui.singleFile)
        if (banners) {
            ProjectBanners(
                state = state,
                project = project,
                singleFile = ui.singleFile,
                actions = rememberBannerActions(session, ui, actions),
                modifier = Modifier.padding(top = contentPadding.calculateTopPadding()),
            )
        }
        val padding = if (banners) PaddingValues(bottom = contentPadding.calculateBottomPadding()) else contentPadding
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onFocusChanged { focused = it.hasFocus }
                .plainClickSteps { navigated() },
        ) {
            CodeEditor(
                state = editor,
                modifier = Modifier.fillMaxSize(),
                tokenizer = tokenizer,
                markers = markers,
                links = links,
                onLinkClick = { offset -> lines.linkAt(editor, offset)?.let(follow) },
                contextMenu = { offset ->
                    val link = lines.linkAt(editor, offset)
                    if (link == null) emptyList() else listOf(MenuEntry.Item(goTo, { follow(link) }))
                },
                zoom = zoom,
                softWrap = settings.softWrap,
                lineNumbers = settings.lineNumbers,
                proportional = settings.editorFace == EditorFace.Proportional,
                autoCorrect = settings.keyboardSuggestions,
                capitalizeSentences = settings.keyboardSuggestions,
                indent = INK_INDENT,
                placeholder = placeholder,
                contentPadding = if (strip) PaddingValues(top = padding.calculateTopPadding()) else padding,
                label = label,
            )
        }
        if (strip) InkKeysStrip(editor, INK_INDENT)
    }
}

/** Upstream's editor: soft tabs of four spaces (Ace's default). */
private val INK_INDENT = CodeIndent.spaces(4)

/**
 * Calls [onStep] after a plain tap or click on the editor has placed the cursor (upstream adds a
 * navigation step for every editor click). A drag that scrolled is no click. The events are only
 * watched, never taken from the editor.
 */
private fun Modifier.plainClickSteps(onStep: () -> Unit): Modifier = pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    coroutineScope {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var moved = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (abs(change.position.x - down.position.x) > slop || abs(change.position.y - down.position.y) > slop) moved = true
                if (event.changes.size > 1) moved = true
                if (!change.pressed) break
            }
            if (!moved) {
                // the editor moves its cursor when it handles the same release: read it a frame later
                launch {
                    withFrameNanos { }
                    onStep()
                }
            }
        }
    }
}

/** The file's issues as the editor's gutter marks, the gravest of a line first. Upstream draws a runtime warning as an error. */
fun markersOf(issues: List<InkIssue>): List<LineMarker> =
    issues.filter { (it.line ?: 0) > 0 }
        .sortedWith(compareBy({ it.line }, { severityOf(it.kind).ordinal }))
        .map { issue -> LineMarker(issue.line!! - 1, severityOf(issue.kind), issue.message) }

private fun severityOf(kind: IssueKind): MarkerSeverity = when (kind) {
    IssueKind.Error, IssueKind.RuntimeError, IssueKind.RuntimeWarning -> MarkerSeverity.Error
    IssueKind.Warning -> MarkerSeverity.Warning
    IssueKind.Todo -> MarkerSeverity.Info
}

/**
 * The tokens of a document's lines, kept for what upstream decides by token type around a place:
 * the links. Brought up to date only when asked, and only when the text changed since.
 */
internal class EditorLines(private val cache: InkLineCache) {
    private var version = -1

    var tokenizer
        get() = cache.tokenizer
        set(value) {
            if (cache.tokenizer !== value) {
                cache.tokenizer = value
                version = -1
            }
        }

    private fun sync(doc: CodeEditorState) {
        if (doc.version != version) {
            cache.update(doc.text)
            version = doc.version
        }
    }

    /** The link at [offset] of [doc] (Ace's rule: the token that holds the place or ends at it), or null. */
    fun linkAt(doc: CodeEditorState, offset: Int): InkLink? {
        sync(doc)
        val line = doc.lineOf(offset)
        val tokens = cache.tokens(line) ?: return null
        return InkLinks.linkAt(cache.line(line), tokens, offset - doc.lineStart(line))
    }

    /** The link under the cursor of [doc]. */
    fun linkAtCursor(doc: CodeEditorState): InkLink? = linkAt(doc, doc.cursor)

    /** The document range of [link], found on the line of [offset]. */
    fun rangeOf(doc: CodeEditorState, link: InkLink, offset: Int): TextRange {
        val start = doc.lineStart(doc.lineOf(offset))
        return TextRange(start + link.start, start + link.end)
    }
}

/** The editor on screen as the window's commands use it. */
private class KitActiveEditor(
    private val editor: CodeEditorState,
    private val currentPath: () -> String,
    private val lines: EditorLines,
    private val context: Context,
) : ActiveEditor {
    override val path: String get() = currentPath()
    override val canUndo: Boolean get() = editor.canUndo
    override val canRedo: Boolean get() = editor.canRedo

    override fun undo() {
        editor.undo()
    }

    override fun redo() {
        editor.redo()
    }

    private val clipboard: ClipboardManager? get() = context.getSystemService(ClipboardManager::class.java)

    override fun cut() {
        val selected = editor.selectedText
        if (selected.isEmpty()) return
        clipboard?.setPrimaryClip(ClipData.newPlainText(null, selected))
        editor.replaceSelection("")
    }

    override fun copy() {
        val selected = editor.selectedText
        if (selected.isNotEmpty()) clipboard?.setPrimaryClip(ClipData.newPlainText(null, selected))
    }

    override fun paste() {
        val clip = clipboard?.primaryClip ?: return
        val text = (0 until clip.itemCount).joinToString("") { clip.getItemAt(it).coerceToText(context).toString() }
        if (text.isNotEmpty()) editor.insert(text)
    }

    override fun selectAll() = editor.selectAll()

    override fun insert(text: String, cursorBack: Int) {
        editor.insert(text)
        if (cursorBack > 0) editor.select(editor.cursor - cursorBack)
    }

    override fun focus() = editor.requestFocus()

    override val cursor: TextPosition get() = editor.cursorPosition.let { TextPosition(it.line, it.column) }

    override fun linkAtCursor(): InkLink? = lines.linkAtCursor(editor)
}
