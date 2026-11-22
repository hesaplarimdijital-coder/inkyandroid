package com.inkyandroid.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The numeric functions blade-ink 1.3.3 gets wrong and the engine repairs (see
 * `InkyPackageAccess.kt`). The values are the reference inklecate's; only the way a float is
 * printed is the library's (`2.0` where the reference prints `2`).
 */
class NativeFunctionsTest {
    private fun line(ink: String): String {
        val turn = Stories.play(ink).continueToChoice()
        assertEquals(emptyList<InkIssue>(), turn.allIssues)
        assertEquals(null, turn.stop)
        return turn.lines.single().text.trim()
    }

    @Test
    fun floorAndCeilingOfAFloat() {
        assertEquals("2.0 3.0 -3.0 -2.0", line("{FLOOR(2.7)} {CEILING(2.2)} {FLOOR(-2.5)} {CEILING(-2.5)}"))
        assertEquals("4.0 4.0", line("{FLOOR(4.0)} {CEILING(4.0)}"))
    }

    @Test
    fun intOfAFloatDropsTheFraction() {
        assertEquals("2 -2 0 7", line("{INT(2.7)} {INT(-2.7)} {INT(0.99)} {INT(7.0)}"))
        // and the result is an int: integer division follows
        assertEquals("3", line("{INT(7.9) / 2}"))
    }

    @Test
    fun floatOfAnIntIsAFloat() {
        assertEquals("1.5", line("{FLOAT(3) / 2}"))
        assertEquals("1", line("{3 / 2}"))
    }

    @Test
    fun theOnesThatWereRightStayRight() {
        assertEquals("3 3 3 2.5", line("{FLOOR(3)} {CEILING(3)} {INT(3)} {FLOAT(2.5)}"))
        assertEquals("3 3.5 1 1.5 -2.5 true 3.5 a1.5", line("{7 / 2} {7.0 / 2} {7 % 2} {7.5 % 2} {-(2.5)} {2.5 == 2.5} {1 + 2.5} {\"a\" + 1.5}"))
        assertEquals("1.4142135 1 2", line("{POW(2.0, 0.5)} {MIN(1, 2)} {MAX(1, 2)}"))
    }

    @Test
    fun aRoundedPercentageWorksInAWatchToo() {
        val play = Stories.play("VAR done = 7\nVAR all = 9\nProgress.\n* [On]\n- End.")
        play.continueToChoice()
        assertEquals(Evaluation.Value("77"), play.evaluate("INT(FLOOR(FLOAT(done) / all * 100))"))
        assertEquals(Evaluation.Value("77 percent"), play.evaluateWatch("{INT(FLOAT(done) / all * 100)} percent"))
    }
}
