package com.inkyandroid.core.symbols

import com.inkyandroid.core.golden.Goldens
import com.inkyandroid.core.golden.Goldens.strings
import com.inkyandroid.core.syntax.InkLines
import com.inkyandroid.core.syntax.InkTokenizer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The symbols of every golden input against upstream's `InkFileSymbols` run over an Ace `EditSession`
 * (`golden/symbols/…`): the symbol tree with rows, columns and function flags, upstream's name maps and
 * their order, includes, tags, the word sets, and `symbolAtPos` / `flowAtPos` for every row; and
 * `InkProject.findSymbol` over the test project (`golden/find-symbol.json`).
 */
class SymbolsGoldenTest {

    private val tokenizer = InkTokenizer()

    private fun parseUpstream(text: String): InkFileSymbols {
        val lines = InkLines.split(text)
        return InkFileSymbols.parse(lines, tokenizer.tokenizeLines(lines), InkFileSymbols.WordSplit.UpstreamAscii)
    }

    private fun id(symbol: FlowSymbol?): String? = symbol?.let { "${it.row}:${it.column}:${it.name}" }

    private fun JsonElement.idOrNull(): String? = if (this is JsonNull) null else jsonPrimitive.content

    private fun checkTree(where: String, expected: List<JsonElement>, actual: List<FlowSymbol>) {
        assertEquals("$where: count", expected.size, actual.size)
        for ((i, e) in expected.withIndex()) {
            val o = e.jsonObject
            val symbol = actual[i]
            val at = "$where/${symbol.name}"
            assertEquals(at, o.getValue("name").jsonPrimitive.content, symbol.name)
            assertEquals(at, o.getValue("flowType").jsonPrimitive.content, symbol.flowType.name)
            assertEquals(at, o.getValue("isfunc").jsonPrimitive.boolean, symbol.isFunction)
            assertEquals(at, o.getValue("row").jsonPrimitive.int, symbol.row)
            assertEquals(at, o.getValue("column").jsonPrimitive.int, symbol.column)
            val children = o["children"]?.jsonArray.orEmpty()
            checkTree(at, children, symbol.children)
            assertEquals(at, o["innerKeys"]?.strings().orEmpty(), symbol.innerSymbols.keys.toList())
            assertEquals(at, o["innerValues"]?.jsonArray?.map { it.idOrNull() }.orEmpty(), symbol.innerSymbols.values.map { id(it) })
            for (child in symbol.children) assertEquals(at, symbol, child.parent)
        }
    }

    private fun check(inputId: String, golden: JsonObject, symbols: InkFileSymbols, rows: Int) {
        checkTree(inputId, golden.getValue("root").jsonArray, symbols.root)
        for (symbol in symbols.root) assertEquals(null, symbol.parent)
        assertEquals("$inputId topLevelKeys", golden.getValue("topLevelKeys").strings(), symbols.topLevelSymbols.keys.toList())
        assertEquals("$inputId topLevelValues", golden.getValue("topLevelValues").jsonArray.map { it.idOrNull() }, symbols.topLevelSymbols.values.map { id(it) })
        assertEquals("$inputId includes", golden.getValue("includes").strings(), symbols.includes)
        assertEquals("$inputId lastIncludeRow", golden.getValue("lastIncludeRow").jsonPrimitive.int, symbols.lastIncludeRow)
        assertEquals("$inputId globalTags", golden.getValue("globalTags").strings(), symbols.globalTags)
        assertEquals(
            "$inputId dictionaryTags",
            golden.getValue("dictionaryTags").jsonArray.map { val p = it.jsonArray; p[0].jsonPrimitive.content to p[1].jsonPrimitive.content },
            symbols.dictionaryTags.entries.map { it.key to it.value },
        )
        assertEquals("$inputId divertTargets", golden.getValue("divertTargets").strings(), symbols.divertTargets.toList())
        assertEquals("$inputId variables", golden.getValue("variables").strings(), symbols.variables.toList())
        assertEquals("$inputId externals", golden.getValue("externals").strings(), symbols.externals.toList())
        assertEquals("$inputId vocabWords", golden.getValue("vocabWords").strings(), symbols.vocabulary.toList())

        val symbolAtRow = golden.getValue("symbolAtRow").jsonArray
        val flowAtRow = golden.getValue("flowAtRow").jsonArray
        assertEquals("$inputId rows", rows, symbolAtRow.size)
        for (row in 0 until rows) {
            assertEquals("$inputId symbolAt($row)", symbolAtRow[row].idOrNull(), id(symbols.symbolAt(row)))
            val flow = flowAtRow[row].jsonObject
            val path = symbols.flowAt(row)
            val actual = linkedMapOf("Knot" to path.knot, "Stitch" to path.stitch, "Choice" to path.choice, "Gather" to path.gather)
                .filterValues { it != null }.mapValues { id(it.value) }
            assertEquals("$inputId flowAt($row)", flow.mapValues { it.value.idOrNull() }.toSortedMap(), actual.toSortedMap())
        }
    }

    @Test
    fun `every input's symbols are upstream's`() {
        for (inputId in Goldens.inputIds) {
            val text = Goldens.input(inputId)
            val golden = Goldens.json("symbols/" + inputId.removeSuffix(".ink") + ".json").jsonObject
            check(inputId, golden, parseUpstream(text), InkLines.split(text).size)
        }
    }

    @Test
    fun `the default word split is upstream's for ASCII text and keeps non-English words whole`() {
        for (inputId in Goldens.inputIds) {
            val text = Goldens.input(inputId)
            if (text.any { it.code > 127 }) continue
            assertEquals(inputId, parseUpstream(text).vocabulary, InkFileSymbols.parse(text, tokenizer).vocabulary)
        }
        val turkish = InkFileSymbols.parse("Çocuk ışığı gördü, sonra güldü.\n", tokenizer).vocabulary
        assertEquals(listOf("Çocuk", "ışığı", "gördü", "sonra", "güldü"), turkish.toList())
        val upstream = parseUpstream("Çocuk ışığı gördü, sonra güldü.\n").vocabulary
        assertEquals(listOf("ocuk", "sonra"), upstream.toList()) // what upstream offers: fragments
    }

    @Test
    fun `findSymbol follows upstream's search order`() {
        val golden = Goldens.json("find-symbol.json").jsonObject
        val files = golden.getValue("files").strings().map { FileSymbols(it, parseUpstream(Goldens.input("project/$it"))) }
        for (result in golden.getValue("results").jsonArray.map { it.jsonObject }) {
            val name = result.getValue("name").jsonPrimitive.content
            val active = result.getValue("activeFile").jsonPrimitive.content
            val row = result.getValue("row").jsonPrimitive.int
            val found = InkProjectSymbols.findSymbol(name, active, row, files)
            val expected = result.getValue("found").let { if (it is JsonNull) null else it.jsonObject }
            val what = "findSymbol($name) in $active at $row"
            if (expected == null) {
                assertEquals(what, null, found)
            } else {
                assertEquals(what, expected.getValue("file").jsonPrimitive.content, found?.path)
                assertEquals(what, expected.getValue("id").jsonPrimitive.content, id(found?.symbol))
            }
        }
    }
}
