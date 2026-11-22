package com.inkyandroid.core.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of upstream's `navHistory.js`. */
class NavigationHistoryTest {

    private val history = NavigationHistory()

    @Test
    fun `an empty history has no step and goes nowhere`() {
        assertNull(history.current)
        assertFalse(history.canGoBack)
        assertFalse(history.canGoForward)
        assertNull(history.back())
        assertNull(history.forward())
    }

    @Test
    fun `a step is added after the current one and becomes current`() {
        assertTrue(history.add(NavStep("main.ink", 0)))
        assertTrue(history.add(NavStep("main.ink", 12, 4)))
        assertEquals(NavStep("main.ink", 12, 4), history.current)
        assertTrue(history.canGoBack)
        assertFalse(history.canGoForward)
    }

    @Test
    fun `a step in the same file on the same row is not added again`() {
        history.add(NavStep("main.ink", 3, 0))
        assertFalse(history.add(NavStep("main.ink", 3, 17)))
        assertEquals(NavStep("main.ink", 3, 0), history.current)
        assertFalse(history.canGoBack)
        // the same row in another file is another place
        assertTrue(history.add(NavStep("other.ink", 3, 0)))
    }

    @Test
    fun `back and forward walk the steps and stop at the ends`() {
        history.add(NavStep("a.ink", 0))
        history.add(NavStep("b.ink", 5))
        history.add(NavStep("c.ink", 9))

        assertEquals(NavStep("b.ink", 5), history.back())
        assertEquals(NavStep("a.ink", 0), history.back())
        assertNull("clamped at the oldest step", history.back())
        assertEquals(NavStep("a.ink", 0), history.current)

        assertEquals(NavStep("b.ink", 5), history.forward())
        assertEquals(NavStep("c.ink", 9), history.forward())
        assertNull("clamped at the newest step", history.forward())
    }

    @Test
    fun `a step added after going back drops the steps ahead`() {
        history.add(NavStep("a.ink", 0))
        history.add(NavStep("b.ink", 5))
        history.add(NavStep("c.ink", 9))
        history.back()
        history.back()

        history.add(NavStep("d.ink", 1))

        assertFalse(history.canGoForward)
        assertEquals(NavStep("a.ink", 0), history.back())
        assertEquals(NavStep("d.ink", 1), history.forward())
    }

    @Test
    fun `reset forgets everything`() {
        history.add(NavStep("a.ink", 0))
        history.add(NavStep("b.ink", 5))
        history.reset()
        assertNull(history.current)
        assertFalse(history.canGoBack)
    }

    @Test
    fun `the steps of a renamed file follow it`() {
        history.add(NavStep("a.ink", 0))
        history.add(NavStep("old.ink", 5))
        history.renamed("old.ink", "new.ink")
        assertEquals(NavStep("new.ink", 5), history.current)
    }

    @Test
    fun `the steps of a removed file are dropped and the current step moves to the nearest older one`() {
        history.add(NavStep("a.ink", 0))
        history.add(NavStep("gone.ink", 5))
        history.add(NavStep("b.ink", 2))
        history.add(NavStep("gone.ink", 8))

        history.removed("gone.ink")

        assertEquals(NavStep("b.ink", 2), history.current)
        assertEquals(NavStep("a.ink", 0), history.back())
        assertFalse(history.canGoBack)
    }

    @Test
    fun `removing the only file's steps empties the history`() {
        history.add(NavStep("gone.ink", 5))
        history.removed("gone.ink")
        assertNull(history.current)
        assertTrue(history.add(NavStep("a.ink", 0)))
        assertEquals(NavStep("a.ink", 0), history.current)
    }

    @Test
    fun `the oldest steps are dropped beyond the limit`() {
        for (row in 0..NavigationHistory.MAX_STEPS + 10) history.add(NavStep("a.ink", row))
        assertEquals(NavStep("a.ink", NavigationHistory.MAX_STEPS + 10), history.current)
        var steps = 0
        while (history.back() != null) steps++
        assertEquals(NavigationHistory.MAX_STEPS - 1, steps)
        assertEquals(NavStep("a.ink", 11), history.current)
    }
}
