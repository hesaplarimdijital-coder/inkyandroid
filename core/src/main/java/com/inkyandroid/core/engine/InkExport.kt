package com.inkyandroid.core.engine

import com.inkyandroid.core.project.ProjectPaths

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
    fun jsFileName(mainPath: String): String {
        val name = ProjectPaths.name(mainPath)
        // upstream: path.extname(name) == ".ink", which a file called just ".ink" does not have
        val base = if (ProjectPaths.hasInkExtension(name)) name.removeSuffix(INK_EXTENSION) else name
        return if (base == "main") "story.js" else "$base.js"
    }

    /**
     * The title of the exported web page: the main file's global tag `# title: …` when it has one
     * that is not empty, otherwise the name of the folder the user exports to.
     */
    fun webTitle(titleTag: String?, folderName: String): String = titleTag?.takeIf { it.isNotEmpty() } ?: folderName

    /**
     * "Export for web": the files of the playable web page, to be written into one folder
     * (existing files of these names are replaced, others left alone):
     *
     * - [jsFileName] with [storyJs];
     * - `index.html`, upstream's template with the title (in `<title>` and `<h1>`) and the script
     *   name filled in, as plain text substitution like upstream's;
     * - `ink.js`, the inkjs runtime that reads the story format this compiler writes;
     * - `style.css` and `main.js`, the template's files unchanged.
     *
     * The title and the name are put in as they are, without HTML escaping, as upstream does (a
     * title may hold markup). Unlike upstream's JavaScript `replace`, a `$` in them stays a `$`.
     */
    fun webPlayer(json: String, mainPath: String, title: String): List<ExportFile> {
        val script = jsFileName(mainPath)
        val page = template("index.html").replace(TITLE_MARK, title).replace(SCRIPT_MARK, script)
        return listOf(
            ExportFile(script, storyJs(json)),
            ExportFile("index.html", page),
            ExportFile("ink.js", template("ink.js")),
            ExportFile("style.css", template("style.css")),
            ExportFile("main.js", template("main.js")),
        )
    }

    /**
     * A file of the web template, bundled with this module (see `web/NOTICE.txt` next to them). The
     * path is absolute so that it does not depend on the package this class ends up in after shrinking.
     */
    private fun template(name: String): String {
        val path = "$TEMPLATE_FOLDER/$name"
        val stream = checkNotNull(InkExport::class.java.getResourceAsStream(path)) { "Missing bundled file: $path" }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private const val TEMPLATE_FOLDER = "/com/inkyandroid/core/engine/web"
    private const val INK_EXTENSION = ".ink"
    private const val TITLE_MARK = "##STORY TITLE##"
    private const val SCRIPT_MARK = "##JAVASCRIPT FILENAME##"
}
