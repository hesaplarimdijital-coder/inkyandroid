package com.inkyandroid.ui.workspace

import android.content.res.Resources
import androidx.compose.ui.graphics.vector.ImageVector
import com.inkyandroid.R
import com.inkyandroid.core.completion.CompletionKind
import com.inkyandroid.core.completion.CompletionResult
import com.inkyandroid.core.completion.InkCompleter
import com.inkyandroid.core.symbols.InkFileSymbols
import com.inkyandroid.ui.InkIcons
import desktopkit.editor.CodeCompleter
import desktopkit.editor.CodeCompletionItem
import desktopkit.editor.CodeCompletions
import desktopkit.editor.CodeEditorState

/**
 * Upstream's auto-complete (Ace's live auto-completion with `inkCompleter.js`) for the kit's editor:
 * the ink keywords first, then by the token at the cursor the divert targets, the variables and the
 * words of the project's files, each with what it is.
 *
 * The suggestions are upstream's, for the word before the cursor as Ace delimits it (`-` and `$` are
 * part of a word there); the kit's list narrows them further as the user types on. [symbols] are
 * every file's parsed symbols in project order, read when the list opens.
 */
internal class InkCodeCompleter(
    private val lines: EditorLines,
    private val symbols: () -> List<InkFileSymbols>,
    private val labels: CompletionLabels,
) : CodeCompleter {
    override fun complete(state: CodeEditorState, cursor: Int): CodeCompletions? {
        val line = state.lineOf(cursor)
        val start = state.lineStart(line)
        val found = lines.completionAt(state, cursor, symbols()) ?: return null
        if (found.items.isEmpty()) return null
        return CodeCompletions(
            start = start + found.replaceStart,
            end = cursor,
            items = found.items.map { item -> CodeCompletionItem(item.value, labels.of(item.kind), iconOf(item.kind)) },
        )
    }
}

/** What each kind of suggestion is called in the list (upstream's "meta" text), in the user's language. */
internal class CompletionLabels(private val keyword: String, private val target: String, private val variable: String, private val word: String) {
    fun of(kind: CompletionKind): String = when (kind) {
        CompletionKind.Keyword -> keyword
        CompletionKind.DivertTarget -> target
        CompletionKind.Variable -> variable
        CompletionKind.Vocabulary -> word
    }

    companion object {
        fun of(resources: Resources) = CompletionLabels(
            keyword = resources.getString(R.string.completion_keyword),
            target = resources.getString(R.string.completion_divert),
            variable = resources.getString(R.string.completion_variable),
            word = resources.getString(R.string.completion_vocabulary),
        )
    }
}

private fun iconOf(kind: CompletionKind): ImageVector = when (kind) {
    CompletionKind.Keyword -> InkIcons.Keyword
    CompletionKind.DivertTarget -> InkIcons.ArrowRight
    CompletionKind.Variable -> InkIcons.Variable
    CompletionKind.Vocabulary -> InkIcons.TextLine
}

/**
 * The suggestions at [cursor] of [doc] by upstream's rules, from the line's tokens. Always as an
 * explicit request: live completion only asks once a word has begun, and Ctrl+Space with no word
 * before the cursor lists everything, as Ace does.
 */
internal fun EditorLines.completionAt(doc: CodeEditorState, cursor: Int, files: List<InkFileSymbols>): CompletionResult? {
    val row = doc.lineOf(cursor)
    val tokens = tokensOf(doc, row) ?: return null
    return InkCompleter.complete(textOf(doc, row), tokens, cursor - doc.lineStart(row), files, explicit = true)
}
