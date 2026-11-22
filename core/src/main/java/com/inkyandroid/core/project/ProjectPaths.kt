package com.inkyandroid.core.project

/** The rules for the root-relative paths of a project (see [ProjectStore]). */
object ProjectPaths {

    /** Whether [path] is a well-formed root-relative path: segments separated by single `/`, none of them empty, `.` or `..`. */
    fun isValid(path: String): Boolean =
        path.isNotEmpty() &&
            !path.contains('\\') &&
            !path.contains('\u0000') &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    /** Returns [path] when it [isValid]; otherwise throws the [IllegalArgumentException] a store answers a bad path with. */
    fun requireValid(path: String): String {
        require(isValid(path)) { "Not a root-relative project path: \"$path\"" }
        return path
    }

    /** The last segment of [path]: `"chapters/one.ink"` → `"one.ink"`. */
    fun name(path: String): String = path.substringAfterLast('/')

    /** The folder of [path], `""` for a file in the root: `"chapters/one.ink"` → `"chapters"`. */
    fun folder(path: String): String = path.substringBeforeLast('/', "")

    /**
     * Whether [path] names an ink file by upstream's rule for the files a project folder contributes
     * (`inkProject.js: isInkFile`): the text after the last dot is exactly `ink`.
     */
    fun isInkFile(path: String): Boolean = name(path).let { it.contains('.') && it.substringAfterLast('.') == "ink" }

    /**
     * The project path an `INCLUDE` line names, or null when it names nothing inside the project.
     *
     * [written] is the text after `INCLUDE` as the tokenizer gives it (everything to the end of the
     * line). Upstream Inky uses that text verbatim, so `INCLUDE a.ink  ` or `INCLUDE a.ink // intro`
     * names a file the compiler finds but Inky's file list does not. The compiler's reading is the one
     * that matters (the player shows what the compiler compiled), so this follows it: a `//` comment
     * and trailing spaces and tabs are dropped (ink removes comments before parsing and
     * `InkParser_Include.cs` trims the name). Beyond that the path is made canonical the way a file
     * system would resolve it against the root: `\` is a separator, `.` segments and doubled
     * separators vanish, `..` steps up. A path that is empty, absolute or leaves the root is null.
     */
    fun normalizeInclude(written: String): String? {
        val withoutComment = written.substringBefore("//")
        val trimmed = withoutComment.trimStart(' ', '\t').trimEnd(' ', '\t', '\r')
        if (trimmed.isEmpty() || trimmed.contains('\u0000')) return null
        val unified = trimmed.replace('\\', '/')
        if (unified.startsWith('/')) return null
        val segments = ArrayList<String>()
        for (segment in unified.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.size - 1)
                else -> segments.add(segment)
            }
        }
        return if (segments.isEmpty()) null else segments.joinToString("/")
    }

    /**
     * The path of a new include file for the [name] a person typed into "Add new include", or null
     * when it cannot be a project path. Upstream's rule (`controller.js: addInclude`): the name gets
     * `.ink` unless it already ends with that extension, so `notes.txt` becomes `notes.txt.ink`.
     * Surrounding blanks are dropped and `\` is read as `/`.
     */
    fun newIncludePath(name: String): String? {
        val typed = name.trim().replace('\\', '/')
        if (typed.isEmpty()) return null
        val path = if (hasInkExtension(typed)) typed else "$typed.ink"
        return path.takeIf(::isValid)
    }

    /** Node's `path.extname(p) == ".ink"`: a final `.ink` that is not the whole name of a dot-file. */
    fun hasInkExtension(path: String): Boolean = path.endsWith(".ink") && name(path).length > ".ink".length
}
