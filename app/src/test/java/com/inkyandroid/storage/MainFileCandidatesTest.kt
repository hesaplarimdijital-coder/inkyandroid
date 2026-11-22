package com.inkyandroid.storage

import org.junit.Assert.assertEquals
import org.junit.Test

/** The cheap include scan that decides the main file of a picked folder (pure JVM). */
class MainFileCandidatesTest {

    private fun candidates(vararg files: Pair<String, String>): List<String> {
        val texts = files.toMap()
        return MainFileCandidates.find(texts.keys) { texts.getValue(it) }
    }

    @Test
    fun filesIncludedByOthersAreNotCandidates() {
        assertEquals(
            listOf("main.ink"),
            candidates(
                "main.ink" to "INCLUDE intro.ink\n  INCLUDE parts/one.ink // first\n",
                "intro.ink" to "",
                "parts/one.ink" to "INCLUDE parts/two.ink\r\n",
                "parts/two.ink" to "",
            ),
        )
    }

    @Test
    fun filesInSubFoldersAreNeverCandidates() {
        assertEquals(listOf("a.ink", "b.ink"), candidates("b.ink" to "", "a.ink" to "", "sub/c.ink" to ""))
    }

    @Test
    fun aFileIncludingItselfStaysACandidate() {
        assertEquals(listOf("self.ink"), candidates("self.ink" to "INCLUDE self.ink"))
    }

    @Test
    fun includePathsAreReadAsTheCompilerReadsThem() {
        assertEquals(
            listOf("a.ink", "chapters/b.ink", "c.ink"),
            MainFileCandidates.includesIn("INCLUDE a.ink   \nINCLUDE ./chapters/b.ink\n\tINCLUDE c.ink\t// note\nnot an INCLUDE x.ink\nINCLUDEx.ink\n"),
        )
    }

    @Test
    fun aDoubleSlashStartsAComment() {
        assertEquals(listOf("a"), MainFileCandidates.includesIn("INCLUDE a//b.ink"))
    }

    @Test
    fun onlyExactlyDotInkCounts() {
        assertEquals(true, MainFileCandidates.isInk("a/b.ink"))
        assertEquals(false, MainFileCandidates.isInk("b.INK"))
        assertEquals(false, MainFileCandidates.isInk("b.ink.txt"))
    }
}
