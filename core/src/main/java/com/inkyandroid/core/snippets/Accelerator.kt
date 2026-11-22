package com.inkyandroid.core.snippets

/**
 * A keyboard shortcut written as an Electron accelerator (`"CmdOrCtrl+Shift+K"`, `"Alt+F5"`), the format
 * of a custom snippet's `accelerator` in a project's settings file.
 *
 * On Android `Cmd`, `Command`, `CmdOrCtrl`, `CommandOrControl`, `Ctrl` and `Control` all mean Ctrl,
 * `Super` and `Meta` mean Meta, `Option`, `Alt` and `AltGr` mean Alt. [key] is the key's name
 * as Electron spells it, upper-cased for single letters (`"K"`, `"F5"`, `"Plus"`, `"Space"`, `"1"`).
 */
data class Accelerator(
    val key: String,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
) {
    companion object {
        /** The accelerator [text] describes, or null if it is not one (no key, an unknown modifier, two keys). */
        fun parse(text: String): Accelerator? {
            if (text.isBlank()) return null
            // "Ctrl++" names the plus key without "Plus": the empty part after the last "+" is that key
            val parts = text.trim().split('+').toMutableList()
            if (parts.size >= 2 && parts.last().isEmpty() && parts[parts.size - 2].isEmpty()) {
                parts.removeAt(parts.size - 1)
                parts[parts.size - 1] = "Plus"
            }
            var ctrl = false
            var alt = false
            var shift = false
            var meta = false
            var key: String? = null
            for (raw in parts) {
                val part = raw.trim()
                when (part.lowercase()) {
                    "cmd", "command", "cmdorctrl", "commandorcontrol", "ctrl", "control" -> ctrl = true
                    "alt", "option", "altgr" -> alt = true
                    "shift" -> shift = true
                    "super", "meta" -> meta = true
                    else -> {
                        if (key != null) return null
                        key = keyName(part) ?: return null
                    }
                }
            }
            return key?.let { Accelerator(it, ctrl, alt, shift, meta) }
        }

        private val NAMED_KEYS = listOf(
            "Plus", "Space", "Tab", "Capslock", "Numlock", "Scrolllock", "Backspace", "Delete", "Insert", "Return",
            "Enter", "Up", "Down", "Left", "Right", "Home", "End", "PageUp", "PageDown", "Escape", "Esc",
            "VolumeUp", "VolumeDown", "VolumeMute", "MediaNextTrack", "MediaPreviousTrack", "MediaStop",
            "MediaPlayPause", "PrintScreen", "numdec", "numadd", "numsub", "nummult", "numdiv",
        ).associateBy { it.lowercase() }

        private const val PUNCTUATION = ")!@#$%^&*(:;<=>?_,-./`~[]{}\\|'\""

        /** Electron's key code names, spelled canonically; null for an unknown key. */
        private fun keyName(part: String): String? {
            if (part.length == 1) {
                val c = part[0]
                return when {
                    c in 'a'..'z' || c in 'A'..'Z' -> c.uppercase()
                    c in '0'..'9' -> part
                    c in PUNCTUATION -> part
                    else -> null
                }
            }
            NAMED_KEYS[part.lowercase()]?.let { return it }
            val lower = part.lowercase()
            if (lower.startsWith("f") && lower.drop(1).toIntOrNull() in 1..24) return "F" + lower.drop(1).toInt()
            if (lower.startsWith("num") && lower.length == 4 && lower[3] in '0'..'9') return lower
            return null
        }
    }
}
