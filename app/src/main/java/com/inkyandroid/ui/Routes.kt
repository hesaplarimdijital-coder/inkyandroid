package com.inkyandroid.ui

import android.util.Base64

/**
 * The shell's routes. A route is also what a new window is opened at.
 *
 * A phone has Write, Play and Files in its bottom bar and the rest under "⋯"; a tablet's rail lists
 * them all, with Play only where editor and player do not fit side by side; a large window's
 * sidebar lists the project's files themselves, each a route of its own ([file]) that shows the
 * workspace with that file.
 */
object Routes {
    /** The editor; from tablet width up the workspace: the editor with the inspector beside it. */
    const val WRITE = "write"

    /** The player on its own, where it does not fit beside the editor. */
    const val PLAY = "play"

    /** The project's files and the outline of the open one; a phone's and a rail's way to them. */
    const val FILES = "files"
    const val ISSUES = "issues"
    const val PROJECTS = "projects"
    const val DOCS = "docs"
    const val SETTINGS = "settings"

    private const val FILE = "file/"

    /**
     * The workspace with the project file at [path] in the editor: an entry of the sidebar. The path
     * is carried in a form the navigation graph takes for a plain word, whatever it contains.
     */
    fun file(path: String): String =
        FILE + Base64.encodeToString(path.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** The project file a route shows, or null for any other route. */
    fun filePath(route: String?): String? {
        if (route == null || !route.startsWith(FILE)) return null
        return try {
            String(Base64.decode(route.substring(FILE.length), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
        } catch (malformed: IllegalArgumentException) {
            null
        }
    }

    /** Whether [route] shows the editor: [WRITE] or one of the sidebar's files. */
    fun isWorkspace(route: String?): Boolean = route == WRITE || filePath(route) != null
}
