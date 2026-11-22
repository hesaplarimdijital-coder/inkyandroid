package com.inkyandroid.core.syntax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InkLineStateTest {

    @Test
    fun `equal states are the same object with a stable id`() {
        val a = InkLineState.stack(listOf("#tmp", "start", "choice.weaveBracket", "choice"))
        val b = InkLineState.stack(listOf("#tmp", "start", "choice.weaveBracket", "choice"))
        assertSame(a, b)
        assertSame(a, InkLineState.fromId(a.id))
        assertEquals(listOf("#tmp", "start", "choice.weaveBracket", "choice"), a.names)
        assertSame(InkLineState.initial, InkLineState.plain("start"))
        assertEquals("start", InkLineState.initial.toString())
        // the plain form and a one-name stack are different Ace values
        assertNotEquals(InkLineState.plain("x"), InkLineState.stack(listOf("x")))
        assertNull(InkLineState.fromId(-1))
    }

    @Test
    fun `interning is safe from several threads`() {
        val pool = Executors.newFixedThreadPool(4)
        val results = (0 until 4).map { pool.submit<List<InkLineState>> { (0 until 500).map { InkLineState.stack(listOf("s$it", "t", "start")) } } }
        val lists = results.map { it.get() }
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)
        for (list in lists) for (i in list.indices) assertSame(lists[0][i], list[i])
    }

    @Test
    fun `lines split as Ace's document splits them`() {
        assertEquals(listOf("a", "b", "c", "d", ""), InkLines.split("a\r\nb\rc\nd\n"))
        assertEquals(listOf(""), InkLines.split(""))
        assertEquals(listOf("a b"), InkLines.split("a b"))
    }

    @Test
    fun `tokenAt picks the token before a boundary, as Ace's getTokenAt`() {
        val line = "-> knot"
        val tokens = InkTokenizer().tokenizeLine(line)
        assertEquals(listOf("divert.operator", "divert", "divert.target"), tokens.tokens.map { it.type })
        assertEquals("divert.operator", tokens.tokenAt(0)?.type)
        assertEquals("divert.operator", tokens.tokenAt(2)?.type)
        assertEquals("divert", tokens.tokenAt(3)?.type)
        assertEquals("divert.target", tokens.tokenAt(7)?.type)
        assertNull(tokens.tokenAt(8))
        assertNull(InkTokenizer().tokenizeLine("").tokenAt(0))
        assertEquals("knot", tokens.tokens[2].text(line))
    }
}
