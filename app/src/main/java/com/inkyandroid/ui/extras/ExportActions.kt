package com.inkyandroid.ui.extras

/**
 * Upstream's three exports, each run end to end: compile, choose the target, write, report.
 * Obtained from `rememberExportActions`.
 */
interface ExportActions {
    /** "Export to JSON…": the compiled story as ink's runtime JSON. */
    fun exportJson()

    /** "Export story.js only…": `var storyContent = <json>;`. */
    fun exportStoryJs()

    /** "Export for web…": a folder with the web player (index.html, the story's .js, ink.js, main.js, style.css). */
    fun exportForWeb()

    /** An export is running; the commands are disabled meanwhile. Compose state. */
    val busy: Boolean
}
