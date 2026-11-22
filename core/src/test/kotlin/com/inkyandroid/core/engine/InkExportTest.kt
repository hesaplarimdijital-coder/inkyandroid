package com.inkyandroid.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InkExportTest {
    private val json = Stories.compiled(Stories.twoFiles).toJson()

    @Test
    fun storyJsIsTheJsonInAVariable() {
        assertEquals("var storyContent = {\"a\":1};", InkExport.storyJs("{\"a\":1}"))
    }

    @Test
    fun theScriptIsNamedAfterTheMainFileButNeverMainJs() {
        assertEquals("tale.js", InkExport.jsFileName("tale.ink"))
        assertEquals("tale.js", InkExport.jsFileName("stories/tale.ink"))
        assertEquals("story.js", InkExport.jsFileName("main.ink"))
        // upstream only strips ".ink"
        assertEquals("notes.txt.js", InkExport.jsFileName("notes.txt"))
        assertEquals("tale.INK.js", InkExport.jsFileName("tale.INK"))
        assertEquals("my.story.js", InkExport.jsFileName("my.story.ink"))
        // Node's extname gives a dot-file no extension
        assertEquals(".ink.js", InkExport.jsFileName(".ink"))
        assertEquals("story.js", InkExport.jsFileName("main"))
    }

    @Test
    fun theTitleIsTheTitleTagOrElseTheFoldersName() {
        assertEquals("My Story", InkExport.webTitle("My Story", "export"))
        assertEquals("export", InkExport.webTitle(null, "export"))
        assertEquals("export", InkExport.webTitle("", "export"))
    }

    @Test
    fun theWebPlayerIsUpstreamsFiveFiles() {
        val files = InkExport.webPlayer(json, "tale.ink", "Barış & the \$1 <b>Tap</b>")
        assertEquals(listOf("tale.js", "index.html", "ink.js", "style.css", "main.js"), files.map { it.name })
        val byName = files.associate { it.name to it.content }
        assertEquals(InkExport.storyJs(json), byName["tale.js"])

        val page = byName.getValue("index.html")
        assertTrue(page.contains("<title>Barış & the \$1 <b>Tap</b></title>"))
        assertTrue(page.contains("<h1>Barış & the \$1 <b>Tap</b></h1>"))
        assertTrue(page.contains("<script src=\"ink.js\"></script>\n    <script src=\"tale.js\"></script>\n    <script src=\"main.js\"></script>"))
        assertFalse(page.contains("##"))

        // the template's own files and the runtime are copied as they are
        assertTrue(byName.getValue("main.js").contains("new inkjs.Story(storyContent)"))
        assertTrue(byName.getValue("style.css").contains(".written-in-ink"))
        assertTrue(byName.getValue("ink.js").contains("inkVersionCurrent=21"))
        assertTrue(json.startsWith("{\"inkVersion\":21,"))
    }

    @Test
    fun theBundledFilesAreTheUnchangedOriginals() {
        // SHA-256 of app/export-for-web-template/* in inkle/inky (3d46aa9) and of dist/ink.js in the npm package inkjs 2.4.0
        val originals = mapOf(
            "index.html" to "007bf87ed41a07e2dc299d0fcbd695c727c1bb94eeb41fee137db3c9b5f94493",
            "main.js" to "3cabbc7b5fb02b5b12ce10c7d4be5c17b81e30fb87b6f7eff5a2d26731b1283b",
            "style.css" to "964ff9960ee41aa7f865013ef5aedc307450dff50297a547700626e8249b1265",
            "ink.js" to "0c106f9a283b8e1e5d23beba7f7bd797e03fca92aa0d33fc69c77072b90c0aa6",
        )
        for ((name, expected) in originals) {
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bundled(name)).joinToString("") { "%02x".format(it) }
            assertEquals(name, expected, sha)
        }
        // and the export hands them on unchanged (index.html apart from its two marks)
        val files = InkExport.webPlayer(json, "main.ink", "##STORY TITLE##").associate { it.name to it.content }
        for (name in listOf("main.js", "style.css", "ink.js")) {
            assertTrue(name, bundled(name).contentEquals(files.getValue(name).toByteArray(Charsets.UTF_8)))
        }
        assertEquals(bundled("index.html").toString(Charsets.UTF_8).replace("##JAVASCRIPT FILENAME##", "story.js"), files["index.html"])
    }

    private fun bundled(name: String): ByteArray =
        checkNotNull(InkExport::class.java.getResourceAsStream("web/$name")).use { it.readBytes() }

    /**
     * Writes exported web folders where `core/src/test/golden/engine/check-web-export.js` looks
     * for them: that script plays each exported story in the exported `ink.js` under Node.
     */
    @Test
    fun exportedFoldersAreWrittenForTheNodeCheck() {
        val out = File(System.getProperty("inky.webExportDir") ?: "build/web-export")
        for (name in listOf("theintercept", "murder_scene", "storylets", "came_from")) {
            val text = checkNotNull(javaClass.getResourceAsStream("/com/inkyandroid/core/snippets/$name.ink")).use { it.readBytes().toString(Charsets.UTF_8) }
            val story = Stories.compiled(InkSources("$name.ink", mapOf("$name.ink" to text)))
            val folder = File(out, name).apply { mkdirs() }
            for (file in InkExport.webPlayer(story.toJson(), "$name.ink", name)) File(folder, file.name).writeText(file.content)
            assertTrue(File(folder, "$name.js").length() > 100)
            assertEquals(5, folder.list()!!.size)
        }
    }
}
