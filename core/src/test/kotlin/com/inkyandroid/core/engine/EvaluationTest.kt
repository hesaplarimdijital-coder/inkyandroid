package com.inkyandroid.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaluationTest {
    private val sources = Stories.single(
        """
        VAR x = 5
        LIST colours = red, (green), blue
        Start.
        * [A] -> some_knot
        * [B] B.
        - -> END
        == some_knot ==
        In some knot {x}.
        -> END
        == function double(n) ==
        ~ return n * 2
        == function noisy() ==
        Noisy text.
        ~ return 3
        == function forever() ==
        ~ x++
        ~ return forever()
        == function bump() ==
        ~ x = x + 100
        ~ return x
        """,
    )

    private fun atFirstChoice(): PlaySession {
        val play = Stories.play(sources)
        assertEquals(2, play.continueToChoice().choices.size)
        return play
    }

    private fun value(play: PlaySession, input: String): String {
        val result = play.evaluate(input)
        assertTrue("$input -> $result", result is Evaluation.Value)
        return (result as Evaluation.Value).text
    }

    @Test
    fun expressions() {
        val play = atFirstChoice()
        assertEquals("5", value(play, "x"))
        assertEquals("6", value(play, " x + 1 "))
        assertEquals("true", value(play, "x == 5"))
        // the library prints floats the Java way: "3.0" where the reference prints "3" (a known difference)
        assertEquals("3.0", value(play, "1.5 * 2"))
        assertEquals("green", value(play, "colours"))
        assertEquals("1", value(play, "LIST_COUNT(colours)"))
        assertEquals("0", value(play, "some_knot"))
        assertEquals("text", value(play, "\"text\""))
    }

    @Test
    fun functionCallsWhichHangTheReference() {
        val play = atFirstChoice()
        assertEquals("10", value(play, "double(x)"))
        // a function's own output comes first, as it would in the story
        assertEquals("Noisy text.3", value(play, "noisy()"))
    }

    @Test
    fun watchesAreStringTemplatesAsInInky() {
        val play = atFirstChoice()
        assertEquals(Evaluation.Value("x is 5"), play.evaluateWatch("x is {x}"))
        assertEquals(Evaluation.Value("6 and 4"), play.evaluateWatch("{x + 1} and {double(2)}"))
        assertEquals(Evaluation.Value("plain words"), play.evaluateWatch("plain words"))
        // a watch never diverts, whatever it looks like
        assertEquals("String expressions cannot contain diverts (->)", (play.evaluateWatch("-> some_knot") as Evaluation.Failed).message)
        assertEquals(2, play.choices.size)
        // as upstream: a quote ends the string early
        assertTrue(play.evaluateWatch("say \"x\"") is Evaluation.Failed)
        assertTrue(play.evaluateWatch("{nope}") is Evaluation.Failed)
    }

    @Test
    fun aDivertJumpsThere() {
        val play = atFirstChoice()
        assertEquals(Evaluation.Diverted("some_knot"), play.evaluate("-> some_knot"))
        assertEquals(listOf("In some knot 5.\n"), play.continueToChoice().lines.map { it.text })
        assertTrue(play.ended)
    }

    @Test
    fun problemsAreReportedAndThePlaythroughIsUntouched() {
        val play = atFirstChoice()
        val cases = mapOf(
            "nope" to "Unresolved variable: nope",
            "x +" to "Expected right side of '+' expression but saw end of line",
            "-> nowhere" to "Divert target not found: '-> nowhere'",
            "double(\"a\")" to "Cannot perform operation '*' on String",
            "1 / 0" to "/ by zero",
            "forever()" to "The evaluation took too long",
            "x = 3" to "Unexpected text after the expression: '= 3'",
            "" to "Not an ink expression",
            "~ x = 3" to "Not an ink expression",
        )
        for ((input, message) in cases) {
            val result = play.evaluate(input)
            assertTrue("$input -> $result", result is Evaluation.Failed)
            result as Evaluation.Failed
            assertTrue("$input -> $result", result.issues.any { it.message == message })
            assertTrue("$input -> $result", result.message.isNotEmpty())
            assertTrue("$input -> $result", result.issues.all { it.file == null && it.line == null })
            // whatever happened, the playthrough is where it was
            assertEquals(input, 2, play.choices.size)
            assertEquals(input, "5", value(play, "x"))
        }
        play.choose(0)
        assertEquals(listOf("In some knot 5.\n"), play.continueToChoice().lines.map { it.text })
    }

    @Test
    fun anEvaluationLeavesNoTrace() {
        val play = atFirstChoice()
        val before = play.saveState()
        value(play, "RANDOM(1, 1000)")
        value(play, "noisy()")
        // a function that assigns sees its own change, the story does not
        assertEquals("105", value(play, "bump()"))
        assertEquals("5", value(play, "x"))
        assertEquals(before, play.saveState())
    }

    @Test
    fun evaluationWorksBeforeTheFirstTurnAndAfterTheEnd() {
        val play = Stories.play(sources)
        assertEquals("5", value(play, "x"))
        assertEquals(listOf("Start.\n"), play.continueToChoice().lines.map { it.text })
        play.choose(1)
        assertTrue(play.continueToChoice().ended)
        assertEquals("10", value(play, "double(x)"))
        assertTrue(play.ended)
    }
}
