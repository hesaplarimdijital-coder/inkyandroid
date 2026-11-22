package com.inkyandroid.core.symbols

import com.inkyandroid.core.golden.Goldens
import com.inkyandroid.core.syntax.InkLines
import com.inkyandroid.core.syntax.InkTokenizer
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upstream re-parses a file 200 ms after typing stops; the parse (tokenizing included) should be well
 * under that for a 5,000-line file (about 85 ms on the build machine when it is quiet). The bound only
 * catches a catastrophic regression, since builds share machines; the numbers are printed.
 */
class SymbolsSpeedTest {

    @Test
    fun `parses a 5000-line file in a fraction of a second`() {
        val intercept = Goldens.input("snippets/theintercept.ink")
        val text = buildString { while (InkLines.split(this).size < 5000) append(intercept).append('\n') }
        val lines = InkLines.split(text)
        val tokenizer = InkTokenizer()
        repeat(5) { InkFileSymbols.parse(text, tokenizer) }

        val rounds = 10
        var start = System.nanoTime()
        repeat(rounds) { InkFileSymbols.parse(text, tokenizer) }
        val withTokenizing = (System.nanoTime() - start) / 1e6 / rounds

        val tokens = tokenizer.tokenizeLines(lines)
        start = System.nanoTime()
        repeat(rounds) { InkFileSymbols.parse(lines, tokens) }
        val fromTokens = (System.nanoTime() - start) / 1e6 / rounds

        println("Symbols of %d lines: %.1f ms with tokenizing, %.1f ms from tokens".format(lines.size, withTokenizing, fromTokens))
        assertTrue("under a second", withTokenizing < 1000)
    }
}
