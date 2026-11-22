package com.inkyandroid.core.syntax

import com.inkyandroid.core.golden.Goldens
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Token for token and state for state against real Ace (`golden/tokens/…`): every bundled snippet file,
 * upstream's test.ink, the torture file, the test project, and the torture file with several instruction
 * prefixes.
 */
class TokenizerGoldenTest {

    @Test
    fun `every input tokenizes exactly as in Ace`() {
        for (id in Goldens.inputIds) {
            compare(id, InkTokenizer(), Goldens.input(id), Goldens.lines(Goldens.json("tokens/" + id.removeSuffix(".ink") + ".json")))
        }
    }

    @Test
    fun `instruction prefixes tokenize exactly as in Ace`() {
        for (id in listOf("gt", "dollar", "at", "star", "group")) {
            val golden = Goldens.json("tokens/prefix/$id.json").jsonObject
            val prefix = golden.getValue("instructionPrefix").jsonPrimitive.content
            val tokenizer = InkTokenizer(prefix)
            assertNull(tokenizer.instructionPrefixProblem)
            compare("torture.ink with prefix $prefix", tokenizer, Goldens.input("torture.ink"), Goldens.lines(golden.getValue("tokens")))
        }
    }

    @Test
    fun `an invalid instruction prefix is reported and the standard rules are used`() {
        val golden = Goldens.json("tokens/prefix/invalid.json").jsonObject
        assertNotNull(golden["error"]) // Ace rejects it too
        val tokenizer = InkTokenizer(golden.getValue("instructionPrefix").jsonPrimitive.content)
        assertNotNull(tokenizer.instructionPrefixProblem)
        compare("torture.ink with an invalid prefix", tokenizer, Goldens.input("torture.ink"), Goldens.lines(Goldens.json("tokens/torture.json")))
    }

    private fun compare(what: String, tokenizer: InkTokenizer, text: String, expected: List<Goldens.Line>) {
        val lines = InkLines.split(text)
        assertEquals("$what: line count", expected.size, lines.size)
        var state = tokenizer.initialState
        for ((row, line) in lines.withIndex()) {
            val result = tokenizer.tokenizeLine(line, state)
            val actualTokens = result.tokens.map { it.type to it.text(line) }
            val golden = expected[row]
            if (actualTokens != golden.tokens || result.endState.names != golden.state) {
                fail(
                    "$what, row $row: ${line.take(120)}\n" +
                        "expected tokens ${golden.tokens}\n" +
                        "actual tokens   $actualTokens\n" +
                        "expected state ${golden.state.take(6)}… (${golden.state.size})\n" +
                        "actual state   ${result.endState.names.take(6)}… (${result.endState.names.size})",
                )
            }
            // The tokens are contiguous from the line's start
            var position = 0
            for (token in result.tokens) {
                assertEquals("$what, row $row: token start", position, token.start)
                position = token.end
            }
            state = result.endState
        }
    }
}
