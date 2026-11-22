package com.inkyandroid.core.syntax

import com.inkyandroid.core.golden.Goldens
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule tables the port runs against the ones Ace builds from upstream's unmodified `ace-ink.js`
 * (the files in `golden/rules`): every state with Ace's name, in Ace's order, every rule with its regex source,
 * token or token list, next state or push/pop, the split regex, and each state's combined regex. This proves
 * the transcription of the rules and the port of `normalizeRules` and of the `Tokenizer` constructor.
 */
class RulesGoldenTest {

    @Test
    fun `standard rules are Ace's`() = check(InkTokenizer(), "rules/standard.json")

    @Test
    fun `rules with an instruction prefix are Ace's`() = check(InkTokenizer(">>>"), "rules/prefix-gt.json")

    private fun check(tokenizer: InkTokenizer, golden: String) {
        val expected = Goldens.json(golden).jsonObject
        val ace = tokenizer.ace
        assertEquals("state names and order", expected.keys.toList(), ace.ruleSet.keys.toList())
        for ((name, stateJson) in expected) {
            val state = stateJson.jsonObject
            assertEquals("combined regex of $name", state.getValue("regExp").jsonPrimitive.content, ace.combinedSources[name])
            val rules = state.getValue("rules").jsonArray
            val actual = ace.ruleSet.getValue(name)
            assertEquals("rule count of $name", rules.size, actual.size)
            for ((i, ruleJson) in rules.withIndex()) {
                assertEquals("rule $i of $name", describe(ruleJson.jsonObject), describe(actual[i]))
            }
        }
        assertEquals(emptyList<String>(), ace.reportedErrors)
    }

    private fun describe(rule: JsonObject): Map<String, String> =
        rule.mapValues { (_, v) -> render(v) }.toSortedMap().let { LinkedHashMap(it) }

    private fun render(v: JsonElement): String = when (v) {
        JsonNull -> "null"
        is JsonPrimitive -> v.content
        else -> v.jsonArray.joinToString("|") { it.jsonPrimitive.content }
    }

    private fun describe(rule: AceRule): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        rule.regex?.let { m["regex"] = it }
        when (val t = rule.token) {
            is String -> m["token"] = t
            is List<*> -> m["token"] = t.joinToString("|")
            null -> if (rule.tokenArray != null) m["token"] = "null"
        }
        rule.tokenArray?.let { m["tokenArray"] = it.joinToString("|") }
        when (val n = rule.next) {
            AceNext.Push -> m["next"] = "pushState"
            AceNext.Pop -> m["next"] = "popState"
            is String -> m["next"] = n
        }
        rule.nextState?.let { m["nextState"] = it }
        rule.include?.let { m["include"] = it }
        rule.defaultToken?.let { m["defaultToken"] = it }
        rule.splitSource?.let { m["splitRegex"] = it }
        return m.toSortedMap().let { sorted -> LinkedHashMap(sorted) }
    }
}
