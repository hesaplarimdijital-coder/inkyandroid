package com.inkyandroid.storage

/**
 * Project names as folder and file names: what the user types becomes `<name>/<name>.ink`, in the
 * app's storage or in a folder of any provider (FAT on an SD card included).
 */
object ProjectNames {

    /** Longest name kept, in characters; providers and FAT allow 255 bytes for `<name>.settings.json`. */
    const val MAX_LENGTH = 100

    private val FORBIDDEN = Regex("""[/\\:*?"<>|\u0000-\u001F\u007F]""")
    private val SPACES = Regex("""\s+""")

    /**
     * [typed] as a folder name: characters no file system accepts replaced by spaces, runs of
     * white space made one space, spaces and dots trimmed at both ends (a leading dot would make
     * the project hidden), cut to [MAX_LENGTH]. `null` when nothing is left: the UI keeps its
     * "Create" button disabled.
     */
    fun sanitize(typed: String): String? {
        val cleaned = typed.replace(FORBIDDEN, " ").replace(SPACES, " ").trim(' ', '.')
        if (cleaned.isEmpty()) return null
        return cleaned.take(MAX_LENGTH).trimEnd(' ', '.')
    }

    /** [name], or `"<name> 2"`, `"<name> 3"` … whichever [taken] does not contain (compared without case, as most file systems do). */
    fun unique(name: String, taken: Collection<String>): String {
        val lower = taken.mapTo(HashSet()) { it.lowercase() }
        if (name.lowercase() !in lower) return name
        var n = 2
        while ("$name $n".lowercase() in lower) n++
        return "$name $n"
    }

    /** The main file of a new project named [name]. */
    fun mainFileOf(name: String): String = "$name.ink"
}
