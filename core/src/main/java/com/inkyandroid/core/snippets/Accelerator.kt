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
        fun parse(text: String): Accelerator? = TODO()
    }
}
