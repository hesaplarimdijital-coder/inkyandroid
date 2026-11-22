package com.inkyandroid.storage

/**
 * Which ink files of a folder could be its project's main file: the `.ink` files directly in the
 * folder that no other `.ink` file of the folder includes. Includes are resolved against the
 * folder, as Inky and inklecate resolve them against the main file's folder, so a file in a
 * sub-folder is never a candidate (it would make another folder the project's root).
 *
 * The `INCLUDE` lines are found with a cheap line scan, not the real parser (that lives in
 * `:core`): a line whose first word is `INCLUDE`, the path being the rest of the line with
 * trailing spaces and tabs removed, as inklecate reads it. Lines in block comments are not told
 * apart; a commented-out include then only removes a candidate, and the user can still pick the
 * file when nothing else remains.
 */
internal object MainFileCandidates {

    private val INCLUDE = Regex("""^[ \t]*INCLUDE[ \t]+(.+)$""")

    /**
     * @param inkFiles the root-relative paths of the folder's `.ink` files, at any depth, with a way
     *   to read each.
     * @return the candidates, sorted by name; every top-level `.ink` file when each of them is
     *   included by another (an include cycle), so that the user still has a choice.
     */
    fun find(inkFiles: Collection<String>, read: (String) -> String): List<String> {
        val included = HashSet<String>()
        for (path in inkFiles) {
            for (target in includesIn(read(path))) {
                if (target != path) included += target
            }
        }
        val topLevel = inkFiles.filter { '/' !in it }.sorted()
        val candidates = topLevel.filter { it !in included }
        return candidates.ifEmpty { topLevel }
    }

    /** The include paths named in [text], in order, normalised to root-relative form. */
    fun includesIn(text: String): List<String> = text.lineSequence()
        .mapNotNull { line -> INCLUDE.find(line.removeSuffix("\r"))?.groupValues?.get(1) }
        .map { normalise(it.trimEnd(' ', '\t')) }
        .filter { it.isNotEmpty() }
        .toList()

    /** `./a//b.ink` → `a/b.ink`; backslashes count as separators, as authors on Windows write them. */
    private fun normalise(path: String): String =
        path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")

    /** Whether [path] names an ink file: the extension is exactly `ink`, as upstream's file watcher checks it. */
    fun isInk(path: String): Boolean = path.endsWith(".ink") && path.length > 4
}
