package com.inkyandroid.core.engine

/** One file of an export: its name inside the export folder and its text (written as UTF-8). */
data class ExportFile(val name: String, val content: String)

/**
 * Upstream's three exports (`inkProject.js: export, buildForWeb, convertJSONToJS, jsFilename`).
 * All of them start from the compiled story's JSON, [CompiledStory.toJson]; "Export to JSON" writes
 * that text as it is. Nothing here touches storage: the caller writes the texts where the user chose.
 */
object InkExport {
    /** "Export story.js only": `var storyContent = <json>;`, no newline at the end. */
    fun storyJs(json: String): String = "var storyContent = $json;"

    /**
     * The name of the story's JavaScript file: the main file's name with a final `.ink` replaced by
     * `.js`. `main.ink` gives `story.js`, because the web player's own script is `main.js`.
     *
     * @param mainPath the main file's project path or bare name.
     */
    fun jsFileName(mainPath: String): String = TODO()

    /**
     * The title of the exported web page: the main file's global tag `# title: …` when it has one
     * that is not empty, otherwise the name of the folder the user exports to.
     */
    fun webTitle(titleTag: String?, folderName: String): String = TODO()

    /**
     * "Export for web": the files of the playable web page, to be written into one folder
     * (existing files of these names are replaced, others left alone):
     *
     * - [jsFileName] with [storyJs];
     * - `index.html`, upstream's template with the title (in `<title>` and `<h1>`) and the script
     *   name filled in, as plain text substitution like upstream's;
     * - `ink.js`, the inkjs runtime that reads the story format this compiler writes;
     * - `style.css` and `main.js`, the template's files unchanged.
     */
    fun webPlayer(json: String, mainPath: String, title: String): List<ExportFile> = TODO()
}
