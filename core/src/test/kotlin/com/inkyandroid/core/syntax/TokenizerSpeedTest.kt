package com.inkyandroid.core.syntax

import com.inkyandroid.core.golden.Goldens
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How fast the tokenizer is on upstream's longest sample, after warming up (about 90,000 lines a second
 * on the build machine when it is quiet). The bound only catches a catastrophic regression, since builds
 * share machines; the measured numbers are printed.
 */
class TokenizerSpeedTest {

    @Test
    fun `tokenizes the intercept fast enough for the editor`() {
        val tokenizer = InkTokenizer()
        val lines = InkLines.split(Goldens.input("snippets/theintercept.ink"))
        repeat(20) { tokenizer.tokenizeLines(lines) }

        val rounds = 30
        var tokens = 0L
        val start = System.nanoTime()
        repeat(rounds) { tokens += tokenizer.tokenizeLines(lines).sumOf { it.tokens.size } }
        val seconds = (System.nanoTime() - start) / 1e9
        val chars = lines.sumOf { it.length } * rounds.toLong()
        val linesPerSecond = lines.size * rounds / seconds
        println(
            "Tokenizer: %.0f tokens/s, %.0f lines/s, %.1f MB/s of text, %.2f ms per pass over %d lines"
                .format(tokens / seconds, linesPerSecond, chars / seconds / 1e6, seconds * 1000 / rounds, lines.size),
        )
        assertTrue("at least 5,000 lines per second", linesPerSecond > 5_000)
    }
}
