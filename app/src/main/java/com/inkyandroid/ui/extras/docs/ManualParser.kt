package com.inkyandroid.ui.extras.docs

import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.commonmark.node.Link as MarkdownLink

/**
 * Turns the manual's Markdown (CommonMark) into [Manual] blocks.
 *
 * The manual opens with a `<details>` table of contents written by hand for GitHub; it is dropped,
 * since the screen builds its own from the headings. Any other raw HTML is kept as its text, which is
 * how a reader would want to see it in an example. Images (the manual has none) show their
 * description.
 */
object ManualParser {

    private val parser: Parser = Parser.builder().build()

    /** Parses [markdown]; line endings may be `\n`, `\r\n` or `\r`. */
    fun parse(markdown: String): Manual {
        val document = parser.parse(markdown.replace("\r\n", "\n").replace('\r', '\n'))
        val builder = Builder()
        builder.blocks(document, listDepth = 0, quoteDepth = 0)
        return Manual(builder.result)
    }

    private class Builder {
        val result = ArrayList<ManualBlock>()
        private val slugs = GitHubSlugger()

        // inside the hand-written <details> table of contents: everything up to </details> is skipped
        private var inDetails = false

        // the marker the next block takes: set by a list item for its first block only
        private var pendingMarker: String? = null

        fun blocks(parent: Node, listDepth: Int, quoteDepth: Int) {
            var child = parent.firstChild
            while (child != null) {
                block(child, listDepth, quoteDepth)
                child = child.next
            }
        }

        private fun takeMarker(): String? = pendingMarker.also { pendingMarker = null }

        private fun block(node: Node, listDepth: Int, quoteDepth: Int) {
            if (inDetails) {
                if (node is HtmlBlock && DETAILS_END in node.literal) inDetails = false
                return
            }
            when (node) {
                is HtmlBlock -> {
                    val html = node.literal
                    if (html.trimStart().startsWith(DETAILS_START)) {
                        inDetails = DETAILS_END !in html
                    } else if (html.isNotBlank()) {
                        result += ManualBlock.Paragraph(RichText(html.trimEnd()), listDepth, quoteDepth, takeMarker())
                    }
                }
                is Heading -> {
                    val text = inline(node)
                    result += ManualBlock.Heading(node.level, text, slugs.slug(text.text))
                }
                is Paragraph -> result += ManualBlock.Paragraph(inline(node), listDepth, quoteDepth, takeMarker())
                is IndentedCodeBlock -> result += ManualBlock.Code(node.literal.trimEnd('\n'), listDepth, quoteDepth, takeMarker())
                is FencedCodeBlock -> result += ManualBlock.Code(node.literal.trimEnd('\n'), listDepth, quoteDepth, takeMarker())
                is ThematicBreak -> result += ManualBlock.Rule(listDepth, quoteDepth)
                is BlockQuote -> blocks(node, listDepth, quoteDepth + 1)
                is BulletList -> {
                    var item = node.firstChild
                    while (item != null) {
                        listItem(item, BULLET, listDepth, quoteDepth)
                        item = item.next
                    }
                }
                is OrderedList -> {
                    var number = node.markerStartNumber ?: 1
                    val delimiter = node.markerDelimiter ?: "."
                    var item = node.firstChild
                    while (item != null) {
                        listItem(item, "$number$delimiter", listDepth, quoteDepth)
                        number++
                        item = item.next
                    }
                }
                is LinkReferenceDefinition -> Unit // used by links, shows nothing
                is Document -> blocks(node, listDepth, quoteDepth)
                else -> error("ManualParser: unhandled block ${node::class.simpleName}")
            }
        }

        private fun listItem(item: Node, marker: String, listDepth: Int, quoteDepth: Int) {
            check(item is ListItem)
            pendingMarker = marker
            if (item.firstChild == null) {
                // an empty item still shows its marker
                result += ManualBlock.Paragraph(RichText(""), listDepth + 1, quoteDepth, takeMarker())
            } else {
                blocks(item, listDepth + 1, quoteDepth)
            }
            pendingMarker = null
        }

        /** The inline content of [node] as one [RichText]. */
        private fun inline(node: Node): RichText {
            val text = StringBuilder()
            val styles = ArrayList<InlineSpan>()
            val links = ArrayList<LinkSpan>()
            fun walk(parent: Node) {
                var child = parent.firstChild
                while (child != null) {
                    when (child) {
                        is Text -> text.append(child.literal)
                        is Code -> {
                            val start = text.length
                            text.append(child.literal)
                            styles += InlineSpan(start, text.length, InlineStyle.Code)
                        }
                        is Emphasis, is StrongEmphasis -> {
                            val start = text.length
                            walk(child)
                            val style = if (child is Emphasis) InlineStyle.Emphasis else InlineStyle.Strong
                            if (text.length > start) styles += InlineSpan(start, text.length, style)
                        }
                        is MarkdownLink -> {
                            val start = text.length
                            walk(child)
                            if (text.length > start) links += LinkSpan(start, text.length, child.destination)
                        }
                        is Image -> walk(child)
                        is SoftLineBreak -> text.append(' ')
                        is HardLineBreak -> text.append('\n')
                        is HtmlInline -> text.append(child.literal)
                        else -> error("ManualParser: unhandled inline ${child::class.simpleName}")
                    }
                    child = child.next
                }
            }
            walk(node)
            return RichText(text.toString(), styles, links)
        }
    }

    private const val DETAILS_START = "<details"
    private const val DETAILS_END = "</details>"
    private const val BULLET = "•"
}

/**
 * Heading ids as GitHub makes them (github-slugger), which is what the manual's own links use
 * (`#3-conditional-blocks-ifelse`): lower case; everything but letters, digits, marks, `-`, `_` and
 * spaces removed; each space a `-`; a repeated id gets `-1`, `-2`, … in order of appearance.
 */
class GitHubSlugger {
    private val seen = HashMap<String, Int>()

    fun slug(heading: String): String {
        val base = buildString {
            for (c in heading.lowercase()) {
                when {
                    c == ' ' -> append('-')
                    c == '-' || c == '_' || Character.isLetterOrDigit(c) -> append(c)
                    Character.getType(c).let { it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() } -> append(c)
                }
            }
        }
        var slug = base
        val count = seen[base]
        if (count != null) {
            var n = count
            do {
                n++
                slug = "$base-$n"
            } while (slug in seen)
            seen[base] = n
        }
        seen.putIfAbsent(slug, 0)
        return slug
    }
}
