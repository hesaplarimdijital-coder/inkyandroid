package com.inkyandroid.core.settings

import com.inkyandroid.core.snippets.SnippetNode

/**
 * Something wrong with a project settings file. The file is still used as far as it can be; the app
 * tells the user (upstream: an error box for a file that cannot be read as JSON, silence otherwise).
 *
 * [location] is where in the file, as a JSON path (`$.customInkSnippets[2].submenu[0]`).
 */
sealed interface SettingsProblem {
    val location: String

    /** The file is empty (upstream: "Project settings file appeared to be empty"). Nothing is used. */
    data object Empty : SettingsProblem {
        override val location: String get() = "$"
    }

    /** The file is not JSON (upstream: "…appeared to be invalid JSON"). [message] is the parser's. Nothing is used. */
    data class InvalidJson(val message: String) : SettingsProblem {
        override val location: String get() = "$"
    }

    /** A value has the wrong type, for example a `customInkSnippets` that is not an array; it is ignored. */
    data class WrongType(override val location: String, val expected: String) : SettingsProblem

    /**
     * A snippet entry that is neither a separator, nor a named snippet with ink, nor a named submenu; upstream
     * leaves it out of the menu, and so do we.
     */
    data class InvalidSnippet(override val location: String) : SettingsProblem
}

/**
 * A project's settings file, `<main file without .ink>.settings.json` beside the main file (see the README's
 * "Project settings file").
 */
data class ProjectSettings(
    /** The custom snippets for the Ink menu, any depth. */
    val customInkSnippets: List<SnippetNode> = emptyList(),
    /** The `instructionPrefix` (a JavaScript regular expression source), or null. */
    val instructionPrefix: String? = null,
    /** What was wrong with the file; empty for a good file. */
    val problems: List<SettingsProblem> = emptyList(),
) {
    companion object {
        /** No settings file, or nothing in it. */
        val EMPTY = ProjectSettings()

        /**
         * Reads a settings file's [text]. Never throws: anything wrong is in [problems]. Lenient where it
         * costs nothing: comments and trailing commas are accepted (the README's own example has one).
         */
        fun parse(text: String): ProjectSettings = TODO()

        /**
         * The settings file's root-relative path for the main ink file at [mainInkPath]: its path without a
         * final `.ink`, plus `.settings.json`.
         */
        fun pathFor(mainInkPath: String): String =
            (if (hasInkExtension(mainInkPath)) mainInkPath.dropLast(4) else mainInkPath) + ".settings.json"

        /** Node's `path.extname(p) == ".ink"`: a final `.ink` that is not the whole (dot-file) name. */
        private fun hasInkExtension(path: String): Boolean =
            path.endsWith(".ink") && path.substringAfterLast("/").length > 4
    }
}
