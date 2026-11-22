package com.inkyandroid.core.symbols

import com.inkyandroid.core.syntax.InkTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlowSymbolTest {

    private val symbols = InkFileSymbols.parse(
        "=== function f(x)\n~ return x\n=== knot\n= 2\n* (a) A\n= stitch\n- (b) B\n= 2\n",
        InkTokenizer(),
    )

    @Test
    fun `paths, ancestors, functions and JavaScript name order`() {
        val knot = symbols.topLevelSymbols.getValue("knot")
        assertEquals(true, symbols.topLevelSymbols.getValue("f").isFunction)
        assertEquals(false, knot.isFunction)
        val label = symbols.symbolAt(6)!!
        assertEquals("knot.stitch.b", label.path)
        assertEquals(listOf("knot", "stitch"), label.ancestorNames)
        // "2" is declared twice: children keep both, the name map keeps the later one, first as JS orders it
        assertEquals(listOf("2", "stitch", "2"), knot.children.map { it.name })
        assertEquals(listOf("2", "stitch"), knot.innerSymbols.keys.toList())
        assertEquals(7, knot.innerSymbols.getValue("2").row)
        assertEquals(FlowPath(knot = knot, stitch = knot.children[1], gather = label), symbols.flowAt(6))
        assertNull(symbols.flowAt(0).stitch)
    }

    @Test
    fun `a file without symbols`() {
        val empty = InkFileSymbols.parse("Just text.\n", InkTokenizer())
        assertNull(empty.symbolAt(0))
        assertEquals(FlowPath(), empty.flowAt(0))
        assertEquals(listOf("Just", "text"), empty.vocabulary.toList())
        val files = listOf(FileSymbols("a.ink", empty), FileSymbols("b.ink", symbols))
        assertEquals(FoundSymbol("b.ink", symbols.topLevelSymbols.getValue("knot")), InkProjectSymbols.findSymbol("knot", "a.ink", 0, files))
        assertNull(InkProjectSymbols.findSymbol("knot", "missing.ink", 0, files))
    }
}
