package com.inkyandroid.ui.workspace

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.inkyandroid.R
import com.inkyandroid.core.engine.InkIssue
import com.inkyandroid.core.engine.IssueKind
import com.inkyandroid.core.engine.SourceLocation
import com.inkyandroid.core.engine.StopReason
import com.inkyandroid.core.live.CompileStatus
import com.inkyandroid.core.live.LiveState
import com.inkyandroid.core.live.PlayerChoice
import com.inkyandroid.core.live.TranscriptItem
import com.inkyandroid.ui.InkIcons
import desktopkit.components.BannerTone
import desktopkit.components.DeskButton
import desktopkit.components.DeskIcons
import desktopkit.components.DeskProgressBar
import desktopkit.components.HorizontalRule
import desktopkit.components.StatusBanner
import desktopkit.components.animatedStateBackground
import desktopkit.components.collectState
import desktopkit.components.deskClickable
import desktopkit.components.focusRing
import desktopkit.components.verticalScrollbar
import desktopkit.editor.CodeEditorZoom
import desktopkit.editor.textZoom
import desktopkit.screens.MessageScreen
import desktopkit.theme.DeskMotion
import desktopkit.theme.DeskTheme
import desktopkit.theme.LocalMotionTier
import desktopkit.theme.MotionTier
import kotlinx.coroutines.delay

/** What the player asks of whoever shows it. */
class PlayerActions(
    /** The reader picked a choice of the live turn. */
    val onChoose: (PlayerChoice) -> Unit,
    /** A paragraph was tapped: show the ink that produced it. */
    val onOpenSource: (SourceLocation) -> Unit,
    /** A runtime error's line was activated: show the place it names. */
    val onOpenIssue: (InkIssue) -> Unit,
    /** "Show the issues", from the note that says the ink has errors. */
    val onShowIssues: () -> Unit,
    /** The story from its start, from the button under "End of story". */
    val onRestart: () -> Unit,
)

/**
 * The play view: the story as the live session has it, read like a page.
 *
 * It draws the transcript's items in their order (paragraphs, their tags, runtime problems, watch
 * results, a rule between turns, the end), then the choices of the live turn as rows to tap. What a
 * choice appended fades in and is scrolled into view ([animate], and never with the system's
 * "Remove animations"); a transcript that replaced the playthrough (a recompile, a step back) is
 * swapped in place: the list keeps its position by the items' keys.
 *
 * The text keeps a comfortable measure in a wide pane, and is set in the theme's reading size
 * times [zoom], which has the editor's gestures (pinch, Ctrl with the wheel).
 *
 * A tap on a paragraph asks for its source; with a mouse that takes Ctrl or Alt, so a plain click
 * and a drag select text as they do everywhere.
 *
 * @param contentPadding room at the list's ends: on a phone the shell's bars, which the text runs under.
 */
@Composable
fun PlayerPane(
    state: LiveState,
    listState: LazyListState,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
    tagsVisible: Boolean = true,
    animate: Boolean = true,
    zoom: CodeEditorZoom? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val transcript = state.transcript
    val tier = LocalMotionTier.current
    val moving = animate && tier != MotionTier.Reduced
    val styles = rememberPlayerStyles(zoom?.scale ?: 1f)

    // A choice appends a turn and keeps the playthrough's generation; a recompile, a step back or a
    // rewind builds a new generation. So what a choice appended is new while the pair (generation,
    // live turn) differs from the one already shown: it fades in and is scrolled to, once; when it
    // has settled its rows are as solid as the rest, also a row that scrolls out of the list and back.
    val turn = transcript.generation to transcript.liveTurn
    var settled by remember { mutableStateOf(turn) }
    val appendedFrom = transcript.appendedFrom?.takeIf { moving && turn != settled }
    // the rows the list has before the transcript's items: the note that the ink has errors, the top space
    val leadingRows = if (state.compile == CompileStatus.Errors) 2 else 1
    LaunchedEffect(turn) {
        val first = transcript.appendedFrom
        if (first != null && turn != settled) {
            // the rule that closed the last turn is the first thing appended: the new turn begins under it
            val target = leadingRows + first.coerceIn(0, (transcript.items.size - 1).coerceAtLeast(0))
            if (moving) listState.animateScrollToItem(target) else listState.scrollToItem(target)
            if (moving) delay(FADE_MS + STAGGER_MS * MAX_STAGGERED)
        }
        settled = turn
    }

    // the editor's gestures for the player's text: Ctrl with the wheel, two fingers, the zoom keys while it has the focus
    Box(modifier.fillMaxSize().background(colors.background).then(if (zoom != null) Modifier.textZoom(zoom) else Modifier)) {
        when {
            // nothing has ever compiled: say why there is no story instead of an empty page
            transcript.items.isEmpty() && transcript.choices.isEmpty() -> EmptyPlayer(state, actions, contentPadding)
            else -> SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().verticalScrollbar(listState),
                    contentPadding = contentPadding,
                ) {
                    if (state.compile == CompileStatus.Errors) {
                        item(key = "stale") {
                            Measure {
                                StatusBanner(
                                    tone = BannerTone.Warning,
                                    text = stringResource(R.string.player_stale),
                                    modifier = Modifier.padding(top = dimens.gapM),
                                    actionLabel = stringResource(R.string.player_show_issues),
                                    onAction = actions.onShowIssues,
                                )
                            }
                        }
                    }
                    item(key = "top") { Spacer(Modifier.height(dimens.gapL)) }
                    itemsIndexed(transcript.items, key = { _, item -> item.key }, contentType = { _, item -> item::class }) { index, item ->
                        val order = if (appendedFrom != null && index >= appendedFrom) index - appendedFrom else null
                        Measure(Modifier.fadeIn(item.key, order)) {
                            TranscriptRow(item, styles, tagsVisible, actions)
                        }
                    }
                    itemsIndexed(transcript.choices, key = { _, choice -> "choice-${choice.turn}-${choice.index}" }) { index, choice ->
                        val order = if (appendedFrom != null) transcript.items.size - appendedFrom + index else null
                        Measure(Modifier.fadeIn("choice-${choice.turn}-${choice.index}", order)) {
                            ChoiceRow(choice, styles, tagsVisible, first = index == 0) { actions.onChoose(choice) }
                        }
                    }
                    item(key = "bottom") { Spacer(Modifier.height(dimens.gapXl)) }
                }
            }
        }
        BusyBar(state.busy, Modifier.align(Alignment.TopCenter).padding(top = contentPadding.calculateTopPadding()))
    }
}

/** The text styles of the player at one zoom. */
internal class PlayerStyles(val story: TextStyle, val instruction: TextStyle, val note: TextStyle, val link: SpanStyle)

/**
 * The player's styles: the story in the theme's title size at a regular weight, on lines one and a
 * half times as high (a page to read, not a list to scan); instruction lines and tags in the
 * monospace face. Everything times [scale].
 */
@Composable
internal fun rememberPlayerStyles(scale: Float): PlayerStyles {
    val type = DeskTheme.type
    val colors = DeskTheme.colors
    return remember(type, colors, scale) {
        val size = type.title.fontSize * scale
        val story = type.body.copy(fontSize = size, lineHeight = size * READING_LINE_RATIO, color = colors.text)
        val mono = type.mono.fontSize * scale
        PlayerStyles(
            story = story,
            instruction = type.mono.copy(fontSize = mono * INSTRUCTION_RATIO, lineHeight = mono * INSTRUCTION_RATIO * READING_LINE_RATIO, color = colors.textSecondary),
            note = type.mono.copy(fontSize = mono, lineHeight = mono * READING_LINE_RATIO, color = colors.textTertiary),
            link = SpanStyle(color = colors.accentText, textDecoration = TextDecoration.Underline),
        )
    }
}

/** Keeps a row's content to a line length that reads well, centred in a wide pane. */
@Composable
private fun Measure(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = MEASURE).fillMaxWidth().padding(horizontal = DeskTheme.dimens.pagePadding)) { content() }
    }
}

/**
 * Fades a row in when it is the [order]th thing a choice just appended (null: it is simply there).
 * The rows start a moment apart, as desktop Inky's do, the first few only: a long turn is not kept
 * waiting.
 */
@Composable
private fun Modifier.fadeIn(key: Any, order: Int?): Modifier {
    if (order == null) return this
    val tier = LocalMotionTier.current
    val alpha = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        delay(STAGGER_MS * order.coerceAtMost(MAX_STAGGERED))
        alpha.animateTo(1f, DeskMotion.fade(tier, tween(FADE_MS, easing = DeskMotion.StandardDecelerate)))
    }
    return graphicsLayer { this.alpha = alpha.value }
}

@Composable
private fun TranscriptRow(item: TranscriptItem, styles: PlayerStyles, tagsVisible: Boolean, actions: PlayerActions) {
    val dimens = DeskTheme.dimens
    when (item) {
        is TranscriptItem.Text -> Paragraph(item, styles, actions.onOpenSource)
        is TranscriptItem.Tags -> if (tagsVisible) {
            Text(tagLine(item.tags), style = styles.note, modifier = Modifier.padding(bottom = dimens.gapM))
        }
        is TranscriptItem.RuntimeIssue -> IssueLine(item.issue, styles) { actions.onOpenIssue(item.issue) }
        is TranscriptItem.Evaluation -> WatchResult(item, styles)
        is TranscriptItem.Divider -> HorizontalRule(Modifier.padding(vertical = dimens.gapL))
        is TranscriptItem.End -> EndOfStory(styles, actions.onRestart)
        is TranscriptItem.Stopped -> StoppedNote(item, styles)
        is TranscriptItem.CompilerCrash -> CrashBlock(item.message, item.diagnostic, styles)
    }
}

/** "# first, second": a line's tags as desktop Inky writes them. */
private fun tagLine(tags: List<String>): String = "# " + tags.joinToString(", ")

/**
 * One paragraph of the story. A tap asks for the ink behind the character that was hit; a mouse
 * click only with Ctrl or Alt held, and while one of them is held the word under the mouse is
 * underlined with a hand pointer, as upstream marks what an Alt+click would open. A link inside the
 * text keeps its own click.
 */
@Composable
private fun Paragraph(item: TranscriptItem.Text, styles: PlayerStyles, onOpenSource: (SourceLocation) -> Unit) {
    val dimens = DeskTheme.dimens
    val text = remember(item.text, styles.link) { StoryMarkup.render(item.text.trimEnd('\n'), styles.link) }
    // offsets into the drawn text are offsets into the ink's output only while no markup was taken out
    val plain = text.text.length == item.text.trimEnd('\n').length
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var pointedWord by remember { mutableStateOf<TextRange?>(null) }
    val shown = remember(text, pointedWord) {
        val word = pointedWord
        if (word == null) text else buildAnnotatedString {
            append(text)
            addStyle(SpanStyle(textDecoration = TextDecoration.Underline), word.start, word.end)
        }
    }
    val open by rememberUpdatedState(onOpenSource)
    val label = stringResource(R.string.player_open_source)
    val sourceAt: (Int?) -> SourceLocation? = { offset -> (if (plain && offset != null) item.sourceAt(offset) else null) ?: item.source }
    Text(
        text = shown,
        style = if (item.isInstruction) styles.instruction else styles.story,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = dimens.gapM)
            .semantics {
                if (item.source != null) onClick(label) { sourceAt(null)?.let(open); true }
            }
            .then(if (pointedWord != null) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .pointerInput(item.key) {
                // only watched, never taken: the mouse with Ctrl or Alt held over a word of a paragraph that has a source
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        val held = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isAltPressed
                        pointedWord = if (change.type != PointerType.Mouse || event.type == PointerEventType.Exit || !held || item.source == null) {
                            null
                        } else {
                            layout?.let { laid ->
                                val offset = laid.getOffsetForPosition(change.position)
                                laid.getWordBoundary(offset.coerceAtMost((laid.layoutInput.text.length - 1).coerceAtLeast(0)))
                                    .takeIf { !it.collapsed && !text.hasLinkAt(offset) }
                            }
                        }
                    }
                }
            }
            .pointerInput(item.key) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val modifiers = currentEvent.keyboardModifiers
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    if (down.type == PointerType.Mouse && !(modifiers.isCtrlPressed || modifiers.isAltPressed)) return@awaitEachGesture
                    val offset = layout?.getOffsetForPosition(up.position)
                    if (offset != null && text.hasLinkAt(offset)) return@awaitEachGesture
                    sourceAt(offset)?.let { place ->
                        up.consume()
                        open(place)
                    }
                }
            },
    )
}

private fun AnnotatedString.hasLinkAt(offset: Int): Boolean =
    getLinkAnnotations(offset, offset).isNotEmpty()

/** One choice of the live turn: a row as wide as the text column, the whole of it the target. */
@Composable
private fun ChoiceRow(choice: PlayerChoice, styles: PlayerStyles, tagsVisible: Boolean, first: Boolean, onClick: () -> Unit) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val interaction = remember { MutableInteractionSource() }
    val visual = interaction.collectState()
    val shape = RoundedCornerShape(dimens.radiusControl)
    val text = remember(choice.text, styles.link) { StoryMarkup.render(choice.text.trim(), styles.link) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = if (first) dimens.gapS else 0.dp, bottom = dimens.gapS)
            .heightIn(min = dimens.minTarget)
            .focusRing(visual, shape)
            .background(animatedStateBackground(visual, rest = colors.surface).value, shape)
            .border(1.dp, colors.borderStrong, shape)
            .deskClickable(interaction, onClick, role = Role.Button)
            .padding(horizontal = dimens.gapL, vertical = dimens.gapM),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, style = styles.story.copy(color = colors.accentText))
        if (tagsVisible && choice.tags.isNotEmpty()) {
            Text(choice.tags.joinToString(" ") { "# $it" }, style = styles.note, modifier = Modifier.padding(top = dimens.gapXs))
        }
    }
}

/** A runtime error or warning where the story raised it: "Line 12: …", leading to that line. */
@Composable
private fun IssueLine(issue: InkIssue, styles: PlayerStyles, onClick: () -> Unit) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val interaction = remember { MutableInteractionSource() }
    val visual = interaction.collectState()
    val shape = RoundedCornerShape(dimens.radiusSmall)
    val warning = issue.kind == IssueKind.RuntimeWarning || issue.kind == IssueKind.Warning
    val tone = if (warning) colors.warning else colors.danger
    val line = issue.line
    val text = if (line != null && line > 0) stringResource(R.string.issue_at_line, line, issue.message) else issue.message
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = dimens.gapM)
            .focusRing(visual, shape)
            .background(animatedStateBackground(visual).value, shape)
            .deskClickable(interaction, onClick, role = Role.Button)
            .padding(vertical = dimens.gapXs),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
    ) {
        Icon(if (warning) DeskIcons.Warning else DeskIcons.Error, contentDescription = null, tint = tone, modifier = Modifier.size(dimens.icon))
        Text(text, style = styles.story.copy(color = tone, textDecoration = TextDecoration.Underline), modifier = Modifier.weight(1f))
    }
}

/** What a watch expression came to after the turn: the text it made, or why it could not. */
@Composable
private fun WatchResult(item: TranscriptItem.Evaluation, styles: PlayerStyles) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val shape = RoundedCornerShape(dimens.radiusPill)
    val failed = item.result == null
    val text = item.result ?: item.error.orEmpty()
    val described = if (failed) stringResource(R.string.watch_failed, item.expression, text) else stringResource(R.string.watch_result, item.expression, text)
    Box(Modifier.fillMaxWidth().padding(bottom = dimens.gapS)) {
        Text(
            text = text.ifEmpty { item.expression },
            style = styles.note.copy(color = if (failed) colors.danger else colors.textSecondary),
            modifier = Modifier
                .background(colors.surface, shape)
                .border(1.dp, if (failed) colors.danger else colors.border, shape)
                .padding(horizontal = dimens.gapM, vertical = dimens.gapXs)
                .semantics { contentDescription = described },
        )
    }
}

@Composable
private fun EndOfStory(styles: PlayerStyles, onRestart: () -> Unit) {
    val dimens = DeskTheme.dimens
    Column(Modifier.fillMaxWidth().padding(top = dimens.gapL, bottom = dimens.gapM), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.player_end),
            style = styles.story.copy(color = DeskTheme.colors.textSecondary, fontWeight = DeskTheme.type.bodyStrong.fontWeight),
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(dimens.gapM))
        DeskButton(stringResource(R.string.action_restart), onRestart, icon = InkIcons.Restart)
    }
}

/** The story was cut off: it would not stop producing text, or the engine failed in it. */
@Composable
private fun StoppedNote(item: TranscriptItem.Stopped, styles: PlayerStyles) {
    val text = when (item.reason) {
        StopReason.LineLimit -> stringResource(R.string.player_stopped_lines)
        StopReason.TimeLimit -> stringResource(R.string.player_stopped_time)
        StopReason.EngineFailure -> stringResource(R.string.player_stopped_failure)
        StopReason.DepthLimit -> stringResource(R.string.player_stopped_depth)
    }
    StatusBanner(
        tone = BannerTone.Error,
        text = item.detail?.takeIf { it.isNotBlank() } ?: text,
        title = if (item.detail.isNullOrBlank()) null else text,
        modifier = Modifier.padding(vertical = DeskTheme.dimens.gapM),
    )
}

/** The compiler itself failed on the text: said plainly, with what it reported for whoever wants to pass it on. */
@Composable
private fun CrashBlock(message: String, diagnostic: String, styles: PlayerStyles) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    Column(Modifier.fillMaxWidth().padding(vertical = dimens.gapM), verticalArrangement = Arrangement.spacedBy(dimens.gapS)) {
        StatusBanner(tone = BannerTone.Error, title = stringResource(R.string.player_crash_title), text = message.ifBlank { stringResource(R.string.player_crash_text) })
        if (diagnostic.isNotBlank()) {
            Text(stringResource(R.string.player_crash_diagnostic), style = DeskTheme.type.label, color = colors.textSecondary)
            Text(
                diagnostic,
                style = styles.note.copy(color = colors.textSecondary),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(dimens.radiusControl))
                    .border(1.dp, colors.border, RoundedCornerShape(dimens.radiusControl))
                    .padding(dimens.gapM),
            )
        }
    }
}

/** No story to show: it is still being compiled for the first time, or its ink has never compiled. */
@Composable
private fun EmptyPlayer(state: LiveState, actions: PlayerActions, contentPadding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        when (state.compile) {
            CompileStatus.Errors -> {
                val errors = state.issues.count { it.isError }
                MessageScreen(
                    title = stringResource(R.string.player_errors_title),
                    icon = DeskIcons.Error,
                    description = pluralStringResource(R.plurals.player_errors_description, errors, errors),
                    actionLabel = stringResource(R.string.player_show_issues),
                    onAction = actions.onShowIssues,
                )
            }
            CompileStatus.Crashed -> MessageScreen(
                title = stringResource(R.string.player_crash_title),
                icon = DeskIcons.Error,
                description = stringResource(R.string.player_crash_text),
            )
            // compiled, and the story says nothing at all
            CompileStatus.Ok -> MessageScreen(
                title = stringResource(R.string.player_silent_title),
                icon = DeskIcons.Play,
                description = stringResource(R.string.player_silent_description),
            )
            // the first compile is on its way; the busy bar says so if it takes long
            CompileStatus.None -> Unit
        }
    }
}

/**
 * The sign that the story is being compiled: a thin bar at the top of the pane, shown only once
 * the work has taken long enough to be noticed, so it does not flicker with every pause in typing.
 */
@Composable
private fun BusyBar(busy: Boolean, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        if (busy) {
            delay(BUSY_AFTER_MS)
            shown = true
        } else {
            shown = false
        }
    }
    if (shown) {
        DeskProgressBar(fraction = null, modifier = modifier.fillMaxWidth(), contentDescription = stringResource(R.string.player_compiling))
    }
}

/** The longest line of story text that still reads well. */
private val MEASURE = 640.dp

/** A story line's height as a multiple of its text size. */
private const val READING_LINE_RATIO = 1.5f

/** Instruction lines are set in the monospace face, which the theme keeps smaller than its text. */
private const val INSTRUCTION_RATIO = 1.2f

/** How long a new row takes to appear, and how far apart the rows of a turn start (desktop Inky: 1000 and 200 ms). */
private const val FADE_MS = 400
private const val STAGGER_MS = 60L
private const val MAX_STAGGERED = 8

/** A compile shorter than this shows no busy bar. */
private const val BUSY_AFTER_MS = 400L
