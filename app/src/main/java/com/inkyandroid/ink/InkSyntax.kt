package com.inkyandroid.ink

import androidx.compose.ui.graphics.lerp
import com.inkyandroid.core.syntax.InkLineState
import com.inkyandroid.core.syntax.InkLines
import com.inkyandroid.core.syntax.InkTokenizer
import desktopkit.editor.LineTokenizer
import desktopkit.editor.TokenSink
import desktopkit.theme.DeskSyntax
import desktopkit.theme.SyntaxRole
import desktopkit.theme.SyntaxStyle

/**
 * The kit's syntax role of one of upstream's token types (Ace's type strings, e.g.
 * `flow.knot.declaration.name`), or null for plain text.
 *
 * Upstream colours a token by the dot-separated parts of its type (Ace turns `a.b.c` into the CSS
 * classes `ace_a ace_b ace_c`; `inky-spec.md` 6.5). The roles follow what its themes make of them,
 * within the kit's palette:
 *
 * - **Prose recedes into the text colour**: story text, choice and gather text, the text inside
 *   logic, escapes, an `else:` the theme leaves alone. Plain.
 * - **The story's structure is loud**: a knot's or stitch's line is a section title ([SyntaxRole.Heading]);
 *   the marks that steer the reader, the bullets of choices and gathers, `[ ]`, `->` and its
 *   friends, glue, `END`/`DONE` and the keywords `VAR`, `CONST`, `LIST`, `INCLUDE`, `EXTERNAL`,
 *   `function`, are [SyntaxRole.Keyword].
 * - **What looks like a link is one**: a divert's target and an included file's path ([SyntaxRole.Link]),
 *   the two token types Alt+click follows.
 * - **Logic is code**: names, conditions and expressions as names ([SyntaxRole.Name]), braces and
 *   separators as punctuation, sequence marks as operators.
 * - Comments are comments, tags are tags (quieter than the kit's default: [inkSyntax]), the
 *   project's instruction lines are directives to the game ([SyntaxRole.Meta]), and a `TODO` stands out.
 */
fun inkSyntaxRole(tokenType: String): SyntaxRole? {
    if (tokenType.isEmpty()) return null
    val parts = tokenType.split('.')
    fun has(part: String) = part in parts
    return when {
        has("comment") || tokenType == COMMENT_PUNCTUATION -> SyntaxRole.Comment
        has("customInstruction") -> SyntaxRole.Meta
        has("todo") -> if (has("TODO")) SyntaxRole.Keyword else SyntaxRole.Emphasis
        tokenType == "tag" -> SyntaxRole.Tag
        has("innerContent") -> null
        has("flow") && has("declaration") -> when {
            has("function") -> SyntaxRole.Keyword
            has("parameters") -> SyntaxRole.Name
            else -> SyntaxRole.Heading
        }
        has("divert") -> when {
            has("target") -> SyntaxRole.Link
            has("parameter") -> if (has("operator")) SyntaxRole.Punctuation else SyntaxRole.Name
            tokenType == "divert" -> null
            else -> SyntaxRole.Keyword
        }
        has("include") -> when {
            has("filepath") -> SyntaxRole.Link
            has("keyword") -> SyntaxRole.Keyword
            else -> null
        }
        has("external") -> when {
            has("keyword") -> SyntaxRole.Keyword
            has("name") -> SyntaxRole.Function
            has("parameters") -> SyntaxRole.Name
            else -> null
        }
        tokenType == "glue" -> SyntaxRole.Keyword
        has("bullets") || has("weaveBracket") -> SyntaxRole.Keyword
        (has("choice") || has("gather")) && has("label") -> if (has("name")) SyntaxRole.Name else SyntaxRole.Punctuation
        has("var-decl") || has("list-decl") -> when {
            has("keyword") -> SyntaxRole.Keyword
            has("name") -> SyntaxRole.Name
            else -> SyntaxRole.Punctuation
        }
        tokenType == "conditional.multiline.else" -> SyntaxRole.Keyword
        has("logic") -> when {
            has("punctuation") -> SyntaxRole.Punctuation
            has("branch") && has("operator") -> SyntaxRole.Keyword
            has("operator") -> SyntaxRole.Operator
            // a branch's own token is the white space before its dash
            tokenType == "logic.multiline.branch" -> null
            else -> SyntaxRole.Name
        }
        else -> null
    }
}

private const val COMMENT_PUNCTUATION = "punctuation.definition.comment.json"

/**
 * The editor's colours for ink: the kit's, with tags drawn in the quiet grey of the line numbers
 * (upstream sets them in a light grey, below the text) and, when the player hides tags, faded
 * almost into the page, as upstream fades them when "Tags visible" is off.
 */
fun inkSyntax(base: DeskSyntax, tagsVisible: Boolean): DeskSyntax {
    val tag = if (tagsVisible) base.lineNumber else lerp(base.lineNumber, base.background, HIDDEN_TAG_FADE)
    return base.with(SyntaxRole.Tag, SyntaxStyle(tag))
}

/** How far hidden tags fade towards the page: still there to find, no longer read. */
private const val HIDDEN_TAG_FADE = 0.65f

/**
 * `:core`'s tokenizer (the port of Ace's ink mode) as the kit editor's [LineTokenizer]. The editor
 * carries one `Int` per line: the id of the [InkLineState] the line ended in, moved up by one, since
 * the editor starts the first line at 0, which here stands for the tokenizer's initial state.
 *
 * Give the editor the same object on every recomposition (a new tokenizer is a new language to
 * it): one per instruction prefix, which is what a project's [InkTokenizer] is.
 */
class InkEditorTokenizer(val tokenizer: InkTokenizer) : LineTokenizer {

    override fun tokenizeLine(line: CharSequence, state: Int, tokens: TokenSink): Int {
        val start = stateOf(state)
        val result = tokenizer.tokenizeLine(line, start)
        for (token in result.tokens) {
            val role = inkSyntaxRole(token.type) ?: continue
            tokens.add(token.start, token.end, role)
        }
        return result.endState.id + 1
    }

    /** The tokenizer's state for one of the editor's numbers. */
    fun stateOf(editorState: Int): InkLineState =
        if (editorState <= 0) tokenizer.initialState else InkLineState.fromId(editorState - 1) ?: tokenizer.initialState
}

/** A coloured piece of a text drawn outside the editor: [start] to [end] (exclusive) is [role]. */
data class InkSpan(val start: Int, val end: Int, val role: SyntaxRole)

/**
 * Ink coloured for text the app draws itself (the manual's examples), with exactly the editor's
 * roles. The spans are offsets into the text, in order and not overlapping; plain text has none.
 */
object InkHighlight {
    fun spans(text: CharSequence, tokenizer: InkTokenizer = InkTokenizer()): List<InkSpan> {
        val spans = ArrayList<InkSpan>()
        var state = tokenizer.initialState
        var lineStart = 0
        for (line in InkLines.split(text)) {
            val result = tokenizer.tokenizeLine(line, state)
            for (token in result.tokens) {
                val role = inkSyntaxRole(token.type) ?: continue
                spans += InkSpan(lineStart + token.start, lineStart + token.end, role)
            }
            state = result.endState
            lineStart += line.length + lineBreakAt(text, lineStart + line.length)
        }
        return spans
    }

    /** The length of the line break at [index] of [text]: 2 for `\r\n`, 1 for `\n` or `\r`, 0 at the end. */
    private fun lineBreakAt(text: CharSequence, index: Int): Int = when {
        index >= text.length -> 0
        text[index] == '\r' && index + 1 < text.length && text[index + 1] == '\n' -> 2
        else -> 1
    }
}
