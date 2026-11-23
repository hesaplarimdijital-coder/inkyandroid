package com.inkyandroid.ui.extras.docs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import desktopkit.components.MenuEntry
import desktopkit.input.ContextMenuArea
import com.inkyandroid.ink.InkSpan
import desktopkit.theme.DeskColors
import desktopkit.theme.DeskDimens
import desktopkit.theme.DeskSyntax
import desktopkit.theme.DeskTheme
import desktopkit.theme.DeskType

/**
 * The manual's text styles at the reader's zoom: the theme's styles, every size (and line height)
 * multiplied by [scale], so the manual grows as a whole and keeps the theme's proportions.
 */
@Immutable
internal class ManualStyles(type: DeskType, val colors: DeskColors, val dimens: DeskDimens, val scale: Float) {
    val body: TextStyle = type.body.scaled(scale)
    val code: TextStyle = type.mono.copy(fontSize = type.body.fontSize, lineHeight = type.body.lineHeight).scaled(scale)
    private val headings = listOf(type.display, type.headline, type.title, type.bodyStrong).map { it.scaled(scale) }

    fun heading(level: Int): TextStyle = headings[(level - 1).coerceIn(0, headings.lastIndex)]

    /** The space above a heading: parts stand apart most, sub-sections least. */
    fun headingGap(level: Int): Dp = when (level) {
        1 -> dimens.gapXl * 2
        2 -> dimens.gapXl + dimens.gapS
        3 -> dimens.gapXl
        else -> dimens.gapL
    }

    val inlineCode = SpanStyle(fontFamily = type.mono.fontFamily, background = colors.hover)
    val emphasis = SpanStyle(fontStyle = FontStyle.Italic)
    val strong = SpanStyle(fontWeight = FontWeight.SemiBold)
    val link = TextLinkStyles(style = SpanStyle(color = colors.accentText, textDecoration = TextDecoration.Underline))

    /** Every match of find, and the one find is at. */
    val match = SpanStyle(background = colors.accent.copy(alpha = MATCH_ALPHA))
    val currentMatch = SpanStyle(background = colors.accent, color = colors.onAccent)

    private companion object {
        const val MATCH_ALPHA = 0.28f
    }
}

private fun TextStyle.scaled(scale: Float): TextStyle =
    if (scale == 1f) this else copy(fontSize = fontSize.times(scale), lineHeight = lineHeight.timesIfSpecified(scale))

private fun TextUnit.timesIfSpecified(scale: Float): TextUnit = if (isSpecified) times(scale) else this

/** The find matches inside one block: all of them, and which one (if any) is the current. */
@Immutable
internal data class BlockMatches(val ranges: List<IntRange>, val current: IntRange?) {
    companion object {
        val NONE = BlockMatches(emptyList(), null)
    }
}

/** What a code example offers: copying it always, inserting it into the open file when there is one. */
internal class CodeActions(val copyLabel: String, val copy: (String) -> Unit, val insertLabel: String?, val insert: ((String) -> Unit)?)

/**
 * One block of the manual. [colourCode] colours an example's display text (the ink tokenizer's
 * colours); [onLink] follows a link.
 */
@Composable
internal fun ManualBlockView(
    block: ManualBlock,
    styles: ManualStyles,
    matches: BlockMatches,
    onLink: (LinkSpan) -> Unit,
    colourCode: (ManualBlock.Code) -> AnnotatedString,
    codeActions: CodeActions,
    modifier: Modifier = Modifier,
) {
    val dimens = styles.dimens
    when (block) {
        is ManualBlock.Heading -> Text(
            block.text.annotated(styles, matches, onLink),
            style = styles.heading(block.level),
            color = styles.colors.text,
            modifier = modifier.fillMaxWidth().padding(top = styles.headingGap(block.level), bottom = dimens.gapXs).semantics { heading() },
        )
        is ManualBlock.Paragraph -> Nested(block, styles, modifier) {
            Text(block.text.annotated(styles, matches, onLink), style = styles.body, color = styles.colors.text)
        }
        is ManualBlock.Code -> Nested(block, styles, modifier) {
            CodeExample(block, styles, matches, colourCode, codeActions)
        }
        is ManualBlock.Rule -> Nested(block, styles, modifier) {
            Box(Modifier.fillMaxWidth().padding(vertical = dimens.gapS).height(1.dp).background(styles.colors.border))
        }
    }
}

/** A block inside lists and quotes: indented by its depth, with its item's marker, a line for each quote. */
@Composable
private fun Nested(block: ManualBlock, styles: ManualStyles, modifier: Modifier, content: @Composable () -> Unit) {
    val dimens = styles.dimens
    val marker = when (block) {
        is ManualBlock.Paragraph -> block.marker
        is ManualBlock.Code -> block.marker
        else -> null
    }
    // items of a list follow each other closely; a list's first item and other blocks are a paragraph apart
    val top = if (marker != null || block.listDepth > 0) dimens.gapS else dimens.gapM
    val indent = dimens.gapXl * block.listDepth
    Row(modifier.fillMaxWidth().padding(top = top)) {
        repeat(block.quoteDepth) {
            Box(Modifier.width(dimens.gapXs / 2).height(dimens.gapXl).background(styles.colors.borderStrong))
            Box(Modifier.width(dimens.gapM))
        }
        if (marker != null) {
            Box(Modifier.width((indent - dimens.gapXl).coerceAtLeast(0.dp)))
            Box(Modifier.width(dimens.gapXl), contentAlignment = Alignment.TopStart) {
                Text(marker, style = styles.body, color = styles.colors.textSecondary)
            }
        } else {
            Box(Modifier.width(indent))
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun CodeExample(
    block: ManualBlock.Code,
    styles: ManualStyles,
    matches: BlockMatches,
    colourCode: (ManualBlock.Code) -> AnnotatedString,
    actions: CodeActions,
) {
    val dimens = styles.dimens
    val shape = RoundedCornerShape(dimens.radiusControl)
    val entries = {
        listOfNotNull(
            MenuEntry.Item(actions.copyLabel, { actions.copy(block.code) }),
            actions.insert?.let { insert -> MenuEntry.Item(actions.insertLabel.orEmpty(), { insert(block.code) }) },
        )
    }
    ContextMenuArea(entries, accessibilityLabel = actions.copyLabel) {
        // the tint of the editor's current line: every syntax colour is made to read on it
        Row(
            Modifier
                .fillMaxWidth()
                .background(DeskTheme.syntax.currentLine, shape)
                .border(1.dp, styles.colors.border, shape),
            horizontalArrangement = Arrangement.spacedBy(dimens.gapXs),
        ) {
            Text(
                highlight(colourCode(block), matches, styles),
                style = styles.code,
                color = styles.colors.text,
                modifier = Modifier.weight(1f).padding(horizontal = dimens.gapM, vertical = dimens.gapS),
            )
            CodeActionButtons(block, actions, styles)
        }
    }
}

/** The example's own buttons: "copy" and, with an open project on a wide window, "insert". */
@Composable
private fun CodeActionButtons(block: ManualBlock.Code, actions: CodeActions, styles: ManualStyles) {
    Row(Modifier.padding(styles.dimens.gapXs)) {
        desktopkit.components.DeskIconButton(
            icon = CopyIcon,
            contentDescription = actions.copyLabel,
            onClick = { actions.copy(block.code) },
        )
        val insert = actions.insert
        if (insert != null) {
            desktopkit.components.DeskIconButton(
                icon = InsertIcon,
                contentDescription = actions.insertLabel.orEmpty(),
                onClick = { insert(block.code) },
            )
        }
    }
}

/** [this] text with its inline styles, links, and find's marks. */
internal fun RichText.annotated(styles: ManualStyles, matches: BlockMatches, onLink: (LinkSpan) -> Unit): AnnotatedString {
    val plain = this
    val built = buildAnnotatedString {
        append(plain.text)
        for (span in plain.styles) {
            val style = when (span.style) {
                InlineStyle.Code -> styles.inlineCode
                InlineStyle.Emphasis -> styles.emphasis
                InlineStyle.Strong -> styles.strong
            }
            addStyle(style, span.start, span.end)
        }
        for (link in plain.links) {
            addLink(LinkAnnotation.Clickable(link.target, styles.link) { onLink(link) }, link.start, link.end)
        }
    }
    return highlight(built, matches, styles)
}

/** [text] with find's matches marked. */
internal fun highlight(text: AnnotatedString, matches: BlockMatches, styles: ManualStyles): AnnotatedString {
    if (matches.ranges.isEmpty()) return text
    return buildAnnotatedString {
        append(text)
        for (range in matches.ranges) {
            addStyle(if (range == matches.current) styles.currentMatch else styles.match, range.first, range.last + 1)
        }
    }
}

/**
 * An example's display text in the editor's colours: [spans] are the ink highlighter's, on the
 * example's source; they land on the display text through its tab expansion.
 */
internal fun colourCode(block: ManualBlock.Code, spans: List<InkSpan>, syntax: DeskSyntax): AnnotatedString = buildAnnotatedString {
    pushStyle(SpanStyle(color = syntax.plain))
    append(block.display)
    pop()
    for (span in spans) {
        val style = syntax[span.role]
        addStyle(
            SpanStyle(
                color = style.color,
                fontWeight = if (style.bold) FontWeight.Bold else null,
                fontStyle = if (style.italic) FontStyle.Italic else null,
            ),
            block.expansion.map(span.start),
            block.expansion.map(span.end),
        )
    }
}
