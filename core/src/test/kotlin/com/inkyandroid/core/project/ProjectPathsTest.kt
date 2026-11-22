package com.inkyandroid.core.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectPathsTest {

    @Test
    fun `a path is root-relative with single slashes and no dot segments`() {
        assertTrue(ProjectPaths.isValid("story.ink"))
        assertTrue(ProjectPaths.isValid("chapters/part one/one.ink"))
        for (bad in listOf("", "/story.ink", "story.ink/", "a//b.ink", "./a.ink", "a/../b.ink", "a\\b.ink", "..")) {
            assertFalse(bad, ProjectPaths.isValid(bad))
        }
        assertThrows(IllegalArgumentException::class.java) { ProjectPaths.requireValid("../outside.ink") }
    }

    @Test
    fun `name and folder split a path at its last slash`() {
        assertEquals("one.ink", ProjectPaths.name("chapters/one.ink"))
        assertEquals("chapters", ProjectPaths.folder("chapters/one.ink"))
        assertEquals("a/b", ProjectPaths.folder("a/b/c.ink"))
        assertEquals("", ProjectPaths.folder("main.ink"))
    }

    @Test
    fun `an ink file is one whose text after the last dot is exactly ink`() {
        assertTrue(ProjectPaths.isInkFile("main.ink"))
        assertTrue(ProjectPaths.isInkFile("a/notes.txt.ink"))
        assertFalse(ProjectPaths.isInkFile("main.INK"))
        assertFalse(ProjectPaths.isInkFile("main.ink.bak"))
        assertFalse(ProjectPaths.isInkFile("main.settings.json"))
        assertFalse(ProjectPaths.isInkFile("ink"))
    }

    @Test
    fun `an include path is read as the compiler reads it`() {
        assertEquals("a.ink", ProjectPaths.normalizeInclude("a.ink"))
        assertEquals("a.ink", ProjectPaths.normalizeInclude("a.ink  \t"))
        assertEquals("a.ink", ProjectPaths.normalizeInclude("a.ink // the first chapter"))
        assertEquals("my file.ink", ProjectPaths.normalizeInclude("my file.ink"))
        assertEquals("a.ink", ProjectPaths.normalizeInclude("a.ink\r"))
        // two slashes start a comment anywhere on an ink line, in a path too
        assertEquals("sub", ProjectPaths.normalizeInclude("sub//a.ink"))
    }

    @Test
    fun `an include path is resolved against the root like a file system would`() {
        assertEquals("sub/a.ink", ProjectPaths.normalizeInclude("sub\\a.ink"))
        assertEquals("a.ink", ProjectPaths.normalizeInclude("./a.ink"))
        assertEquals("b.ink", ProjectPaths.normalizeInclude("sub/../b.ink"))
        assertEquals("sub/a.ink", ProjectPaths.normalizeInclude("sub/./a.ink"))
    }

    @Test
    fun `an include that names nothing inside the project is no path`() {
        assertNull(ProjectPaths.normalizeInclude(""))
        assertNull(ProjectPaths.normalizeInclude("   "))
        assertNull(ProjectPaths.normalizeInclude("// only a comment"))
        assertNull(ProjectPaths.normalizeInclude("../outside.ink"))
        assertNull(ProjectPaths.normalizeInclude("/etc/passwd"))
        assertNull(ProjectPaths.normalizeInclude("."))
    }

    @Test
    fun `a new include gets ink appended unless the name ends with it`() {
        // controller.js addInclude: "if they write my_file.txt then it will turn into my_file.txt.ink"
        assertEquals("chapter.ink", ProjectPaths.newIncludePath("chapter"))
        assertEquals("chapter.ink", ProjectPaths.newIncludePath("chapter.ink"))
        assertEquals("my_file.txt.ink", ProjectPaths.newIncludePath("my_file.txt"))
        assertEquals("Folder/inkFileName.ink", ProjectPaths.newIncludePath("  Folder/inkFileName.ink "))
        assertEquals("Folder/a.ink", ProjectPaths.newIncludePath("Folder\\a"))
        // Node's extname of a dot-file is empty
        assertEquals(".ink.ink", ProjectPaths.newIncludePath(".ink"))
    }

    @Test
    fun `a new include name that cannot be a project path is refused`() {
        assertNull(ProjectPaths.newIncludePath(""))
        assertNull(ProjectPaths.newIncludePath("   "))
        assertNull(ProjectPaths.newIncludePath("../a"))
        assertNull(ProjectPaths.newIncludePath("/a"))
        assertNull(ProjectPaths.newIncludePath("a//b"))
    }

    @Test
    fun `the end of a row is found for every kind of line break`() {
        val text = "one\ntwo\r\nthree\rfour"
        assertEquals(3, TextLines.endOfRow(text, 0))
        assertEquals(7, TextLines.endOfRow(text, 1))
        assertEquals(14, TextLines.endOfRow(text, 2))
        assertEquals(text.length, TextLines.endOfRow(text, 3))
        assertEquals(text.length, TextLines.endOfRow(text, 9))
        assertEquals(0, TextLines.endOfRow("", 0))
    }

    @Test
    fun `the paths of INCLUDE lines are found without their comment and trailing blanks`() {
        val text = "INCLUDE a.ink\n  INCLUDE sub/b.ink  // second\r\nNot an INCLUDE c.ink\nINCLUDE\nINCLUDE d.ink"
        val found = TextLines.includePathRanges(text)
        assertEquals(listOf("a.ink", "sub/b.ink", "d.ink"), found.map { it.second })
        for ((range, path) in found) assertEquals(path, text.substring(range.first, range.last + 1))
    }

    @Test
    fun `the hash of a text is its SHA-256 in UTF-8`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", TextHash.of(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", TextHash.of("abc"))
        assertFalse(TextHash.of("ş") == TextHash.of("s"))
    }
}
