package com.inkyandroid.core.golden

import com.inkyandroid.core.syntax.InkLineState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Access to the goldens written by `core/src/test/golden/generate.js` (upstream's code running in Ace
 * 1.2.6) and to their inputs.
 */
object Goldens {
    private const val ROOT = "/com/inkyandroid/core/golden/"

    fun text(resource: String): String =
        (Goldens::class.java.getResourceAsStream(resource) ?: error("Missing resource $resource"))
            .use { it.readBytes().toString(Charsets.UTF_8) }

    fun json(relative: String): JsonElement = Json.parseToJsonElement(text(ROOT + relative))

    /** An input file: `snippets/x.ink` (bundled snippets) or a file under `golden/input/`. */
    fun input(id: String): String =
        if (id.startsWith("snippets/")) text("/com/inkyandroid/core/$id") else text(ROOT + "input/$id")

    /** Every input the goldens cover, as `generate.js` lists them. */
    val inputIds: List<String> by lazy {
        val snippets = json("snippets.json").jsonArray.flatMap { category ->
            category.jsonObject["snippets"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["file"]?.jsonPrimitive?.content }
        }.toSortedSet().map { "snippets/$it" }
        snippets + listOf("test.ink", "torture.ink", "project/main.ink", "project/second.ink")
    }

    /** One golden line: Ace's end state and the tokens as (type, text). */
    data class Line(val state: List<String>, val tokens: List<Pair<String, String>>)

    /** Decodes a `tokenizeText` result of `generate.js`. */
    fun lines(tokens: JsonElement): List<Line> {
        val nodes = tokens.jsonObject.getValue("stackNodes").jsonArray.map { it.jsonArray }
        fun stack(index: Int): List<String> {
            val names = ArrayList<String>()
            var i = index
            while (i >= 0) {
                names.add(nodes[i][0].jsonPrimitive.content)
                i = nodes[i][1].jsonPrimitive.int
            }
            return names
        }
        return tokens.jsonObject.getValue("lines").jsonArray.map { line ->
            val items = line.jsonArray
            val state = items[0].jsonPrimitive
            Line(
                state = if (state.isString) listOf(state.content) else stack(state.int),
                tokens = items.drop(1).map { val t = it.jsonArray; t[0].jsonPrimitive.content to t[1].jsonPrimitive.content },
            )
        }
    }

    fun stateOf(names: List<String>, plain: Boolean): InkLineState =
        if (plain) InkLineState.plain(names.single()) else InkLineState.stack(names)

    fun JsonElement.strings(): List<String> = jsonArray.map { (it as JsonPrimitive).content }

    fun JsonArray.strings(): List<String> = map { (it as JsonPrimitive).content }
}
