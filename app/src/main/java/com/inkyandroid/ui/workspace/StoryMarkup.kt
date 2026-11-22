package com.inkyandroid.ui.workspace

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml

/**
 * The little HTML a story's text may carry. Desktop Inky and ink's web player insert story and
 * choice text into the page as HTML, so stories written for the web say `<b>`, `<i>` or `<br>`
 * where they mean it. The player here draws exactly that much: bold, italic, underline, a line
 * break and links. Every other tag is shown as the text it is.
 */
object StoryMarkup {

    /** The tags that are markup; anything else between angle brackets is text. */
    private val allowedTag = Regex("""</?(?:b|strong|i|em|u|a)(?:\s[^<>]*)?>|<br\s*/?>""", RegexOption.IGNORE_CASE)

    /** A character reference (`&amp;`, `&#233;`): markup too, as in a web page. */
    private val reference = Regex("""&(?:[a-zA-Z][a-zA-Z0-9]*|#[0-9]+|#[xX][0-9a-fA-F]+);""")

    /** Whether [text] has anything in it that the player draws as markup. Most lines have none and are shown as they are. */
    fun hasMarkup(text: String): Boolean = allowedTag.containsMatchIn(text) || reference.containsMatchIn(text)

    /**
     * [text] as HTML in which only the allowed tags and the character references are markup: every
     * other `<` and `&` is escaped, so `a < b` and `<script>` come out as written.
     */
    fun sanitize(text: String): String {
        val out = StringBuilder(text.length + 16)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when (char) {
                '<' -> {
                    val tag = allowedTag.matchAt(text, index)
                    if (tag != null) {
                        out.append(tag.value)
                        index = tag.range.last + 1
                        continue
                    }
                    out.append("&lt;")
                }
                '&' -> {
                    val entity = reference.matchAt(text, index)
                    if (entity != null) {
                        out.append(entity.value)
                        index = entity.range.last + 1
                        continue
                    }
                    out.append("&amp;")
                }
                '>' -> out.append("&gt;")
                else -> out.append(char)
            }
            index++
        }
        return out.toString()
    }

    /**
     * [text] for the player: as it is when it has no markup, else with its markup applied. Links
     * are drawn with [link].
     */
    fun render(text: String, link: SpanStyle): AnnotatedString =
        if (hasMarkup(text)) AnnotatedString.fromHtml(sanitize(text), linkStyles = TextLinkStyles(style = link)) else AnnotatedString(text)
}
