package com.inkyandroid.ui

import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import com.inkyandroid.core.snippets.Accelerator
import com.inkyandroid.core.snippets.SnippetNode
import desktopkit.input.KeyChord

/**
 * The key of an Electron accelerator as the kit's [KeyChord]: a custom snippet's `"accelerator"` in
 * the project's settings file (upstream's menus take Electron's names; `inky-spec.md` 14.3). Null
 * for a key Android keyboards do not have.
 */
fun Accelerator.keyChord(): KeyChord? {
    val code = keyCode(key) ?: return null
    val char = key.singleOrNull()?.takeIf { !it.isLetterOrDigit() } ?: if (key == "Plus") '+' else null
    return KeyChord(Key(code), ctrl = ctrl, shift = shift, alt = alt, meta = meta, char = char)
}

private fun keyCode(name: String): Int? {
    name.singleOrNull()?.let { char ->
        return when (char) {
            in 'A'..'Z' -> KeyEvent.KEYCODE_A + (char - 'A')
            in 'a'..'z' -> KeyEvent.KEYCODE_A + (char - 'a')
            in '0'..'9' -> KeyEvent.KEYCODE_0 + (char - '0')
            '-' -> KeyEvent.KEYCODE_MINUS
            '=' -> KeyEvent.KEYCODE_EQUALS
            ',' -> KeyEvent.KEYCODE_COMMA
            '.' -> KeyEvent.KEYCODE_PERIOD
            '/' -> KeyEvent.KEYCODE_SLASH
            ';' -> KeyEvent.KEYCODE_SEMICOLON
            '\'' -> KeyEvent.KEYCODE_APOSTROPHE
            '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
            ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
            '\\' -> KeyEvent.KEYCODE_BACKSLASH
            '`' -> KeyEvent.KEYCODE_GRAVE
            else -> null
        }
    }
    if (name.length in 2..3 && name[0] == 'F') {
        val number = name.substring(1).toIntOrNull()
        if (number != null && number in 1..12) return KeyEvent.KEYCODE_F1 + number - 1
    }
    return when (name) {
        "Plus" -> KeyEvent.KEYCODE_PLUS
        "Space" -> KeyEvent.KEYCODE_SPACE
        "Tab" -> KeyEvent.KEYCODE_TAB
        "Backspace" -> KeyEvent.KEYCODE_DEL
        "Delete" -> KeyEvent.KEYCODE_FORWARD_DEL
        "Insert" -> KeyEvent.KEYCODE_INSERT
        "Return", "Enter" -> KeyEvent.KEYCODE_ENTER
        "Up" -> KeyEvent.KEYCODE_DPAD_UP
        "Down" -> KeyEvent.KEYCODE_DPAD_DOWN
        "Left" -> KeyEvent.KEYCODE_DPAD_LEFT
        "Right" -> KeyEvent.KEYCODE_DPAD_RIGHT
        "Home" -> KeyEvent.KEYCODE_MOVE_HOME
        "End" -> KeyEvent.KEYCODE_MOVE_END
        "PageUp" -> KeyEvent.KEYCODE_PAGE_UP
        "PageDown" -> KeyEvent.KEYCODE_PAGE_DOWN
        "Escape", "Esc" -> KeyEvent.KEYCODE_ESCAPE
        else -> null
    }
}

/** A custom snippet with a key of its own. */
data class SnippetKey(val chord: KeyChord, val snippet: SnippetNode.Snippet)

/**
 * The custom snippets of [nodes] (any depth) that have a usable accelerator, in menu order. Keys
 * without Ctrl, Alt or Meta are left out unless they are function keys: they would take a letter
 * from the text being typed (the kit's rule for the activity's shortcuts).
 */
fun snippetKeys(nodes: List<SnippetNode>): List<SnippetKey> = buildList {
    fun walk(level: List<SnippetNode>) {
        level.forEach { node ->
            when (node) {
                is SnippetNode.Category -> walk(node.children)
                is SnippetNode.Snippet -> node.accelerator?.let(Accelerator::parse)?.keyChord()
                    ?.takeIf { it.hasModifier || it.isFunctionKey }
                    ?.let { add(SnippetKey(it, node)) }
                SnippetNode.Separator -> Unit
            }
        }
    }
    walk(nodes)
}
