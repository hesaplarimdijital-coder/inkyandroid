package com.inkyandroid.core.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Holds the engine to the reference inklecate 1.2.0.
 *
 * The expected files (`golden/engine/expected`) are what the reference tool printed for every
 * bundled sample and for the purpose-written projects in `golden/engine/cases`; they are produced
 * by `core/src/test/golden/engine/generate.py`, which also documents their format. Where the engine
 * is known to differ, the difference is listed with its reason in
 * `golden/engine/known-differences.json` (and in prose in `KNOWN-DIFFERENCES.md` beside it): the
 * tests accept exactly what is listed there, and fail when a listed difference no longer occurs.
 */
class EngineGoldenTest {
    private val engine = InkEngine.Default
    private val cases: List<Case> = Json.parseToJsonElement(resource("expected/index.json")).jsonArray.map { entry ->
        val o = entry.jsonObject
        val name = o.string("name")
        val isSnippet = o.string("kind") == "snippet"
        val texts = o.getValue("files").jsonArray.map { it.jsonPrimitive.content }.associateWith { file ->
            if (isSnippet) text("/com/inkyandroid/core/snippets/$file") else resource("cases/$name/$file")
        }
        Case(name, InkSources(o.string("main"), texts), Json.parseToJsonElement(resource("expected/$name.json")).jsonObject)
    }
    private val known = Json.parseToJsonElement(resource("known-differences.json")).jsonObject

    private class Case(val name: String, val sources: InkSources, val golden: JsonObject) {
        val issueText = IssueText(IncludePaths(sources.mainPath))
        val compile: JsonObject get() = golden.getValue("compile").jsonObject
        val referenceCompiled: Boolean get() = compile.getValue("success").jsonPrimitive.boolean
        val referenceCrashed: Boolean get() = "crash" in compile

        fun issues(raw: JsonElement): List<InkIssue> =
            raw.jsonArray.map { issueText.parse(it.jsonPrimitive.content, IssueKind.Error) }
    }

    @Test
    fun theGoldensCoverEverySampleAndCase() {
        assertEquals(25, cases.count { it.name in SNIPPETS })
        assertEquals(SNIPPETS.size, 25)
        assertTrue(cases.size >= 47)
    }

    @Test
    fun compileMessagesAreTheReferences() {
        for (case in cases) {
            val result = engine.compile(case.sources)
            if (case.referenceCrashed) {
                // the reference dies; listed with what the engine says instead
                val entry = knownList("compile").single { it.string("case") == case.name }
                assertTrue(case.name, result is CompileResult.Failed)
                assertEquals(case.name, entry.getValue("port").jsonArray.map { it.jsonPrimitive.content }, result.issues.map { "${it.kind}: ${it.message}" })
                assertTrue(case.name, result.issues.all { it.file == null && it.line == null })
                continue
            }
            assertEquals(case.name, case.referenceCompiled, result is CompileResult.Success)
            assertTrue(case.name, result !is CompileResult.Crashed)
            // inklecate -j lists author's notes, then warnings, then errors; the engine keeps the compiler's order
            assertEquals(case.name, sorted(case.issues(case.compile.getValue("issues"))), sorted(result.issues))
        }
        assertEquals("cases the reference cannot compile at all", knownList("compile").size, cases.count { it.referenceCrashed })
    }

    @Test
    fun compiledStoriesAreTheReferencesAsTrees() {
        var compared = 0
        for (case in cases.filter { it.referenceCompiled }) {
            val ours = (engine.compile(case.sources) as CompileResult.Success).story.toJson()
            val theirs = resource("expected/${case.name}.story.json")
            val difference = firstDifference(Json.parseToJsonElement(theirs), Json.parseToJsonElement(ours), "$")
            assertNull("${case.name}: the compiled story differs at $difference", difference)
            compared++
        }
        assertEquals(cases.count { it.referenceCompiled }, compared)
        assertTrue(compared >= 40)
    }

    @Test
    fun statisticsAreTheReferences() {
        for (case in cases) {
            val theirs = (case.golden.getValue("statistics") as? JsonObject)?.let {
                InkStatistics(it.int("words"), it.int("knots"), it.int("stitches"), it.int("functions"), it.int("choices"), it.int("gathers"), it.int("diverts"))
            }
            assertEquals(case.name, theirs, engine.statistics(case.sources))
        }
    }

    @Test
    fun transcriptsAreTheReferences() {
        val random = known.getValue("random").jsonObject.getValue("cases").jsonArray.map { it.jsonPrimitive.content }.toSet()
        val textDifferences = knownList("text")
        val usedTextDifferences = HashSet<JsonObject>()
        val randomCasesThatDiffer = HashSet<String>()
        var compared = 0
        for (case in cases.filter { it.referenceCompiled }) {
            val story = (engine.compile(case.sources) as CompileResult.Success).story
            val replacements = textDifferences.filter { it.string("case") == case.name }
            for ((script, events) in case.golden.getValue("transcripts").jsonObject) {
                val theirs = events.jsonArray.map { describeReference(case, it.jsonObject, replacements, usedTextDifferences) }
                val ours = play(case, story, events.jsonArray)
                if (case.name in random) {
                    if (theirs != ours) randomCasesThatDiffer += case.name
                    continue
                }
                assertEquals("${case.name}, script $script", theirs.joinToString("\n"), ours.joinToString("\n"))
                compared++
            }
        }
        assertEquals("cases listed as random whose transcripts are in fact the reference's", emptySet<String>(), random - randomCasesThatDiffer)
        assertEquals("listed text differences that no longer occur", emptyList<JsonObject>(), textDifferences - usedTextDifferences)
        assertTrue("transcripts compared: $compared", compared >= 90)
    }

    @Test
    fun evaluationsAreTheReferences() {
        val differences = knownList("evaluate")
        val used = HashSet<JsonObject>()
        var compared = 0
        for (case in cases.filter { "evaluate" in it.golden }) {
            val asked = case.golden.getValue("evaluate").jsonObject
            val story = (engine.compile(case.sources) as CompileResult.Success).story
            for (result in asked.getValue("results").jsonArray.map { it.jsonObject }) {
                val input = result.string("input")
                val session = story.play()
                session.continueToChoice()
                for (choice in asked.getValue("after").jsonArray) {
                    assertTrue(session.choose(choice.jsonPrimitive.int))
                    session.continueToChoice()
                }
                // Inky sends a watch as the text between quotes
                val evaluation = if (input.length >= 2 && input.startsWith("\"") && input.endsWith("\"")) {
                    session.evaluateWatch(input.substring(1, input.length - 1))
                } else {
                    session.evaluate(input)
                }
                val ours = when (evaluation) {
                    is Evaluation.Value -> "value: ${evaluation.text}"
                    is Evaluation.Failed -> "error: ${evaluation.message}"
                    is Evaluation.Diverted -> "diverted: ${evaluation.path}"
                }
                val difference = differences.firstOrNull { it.string("case") == case.name && it.string("input") == input }
                val theirs = when {
                    difference != null -> {
                        used += difference
                        assertEquals("$input: what the reference did", difference.string("reference"), referenceOutcome(result))
                        difference.string("port")
                    }
                    "output" in result -> "value: ${result.string("output")}"
                    "issues" in result -> "error: ${case.issues(result.getValue("issues")).first().message}"
                    else -> fail("${case.name}: the reference gave nothing for '$input' and no difference is listed").let { "" }
                }
                assertEquals("${case.name}: $input", theirs, ours)
                compared++
            }
        }
        assertEquals("listed evaluation differences that were not met", emptyList<JsonObject>(), differences - used)
        assertTrue(compared >= 15)
    }

    // --- playing a golden's script with the engine ----------------------------------------------

    /** Plays [story] taking the choices the reference transcript took, and describes what came out in the transcript's terms. */
    private fun play(case: Case, story: CompiledStory, events: JsonArray): List<String> {
        val chosen = ArrayDeque(events.map { it.jsonObject }.filter { "chose" in it }.map { it.int("chose") })
        val maxLines = (case.golden["maxLines"] as? JsonPrimitive)?.int
        // a story that never stops is cut where the generator stopped reading; one that goes silent is given a short while
        val limits = PlayLimits(maxLinesPerTurn = maxLines ?: 5_000, turnBudgetMillis = if (events.any { "timeout" in it.jsonObject }) 400 else 20_000)
        val session = story.play(PlayOptions(limits))
        val out = ArrayList<String>()
        while (true) {
            val turn = session.continueToChoice()
            for (line in turn.lines) {
                out += "text: ${line.text}"
                if (line.tags.isNotEmpty()) out += "tags: ${line.tags}"
                if (line.issues.isNotEmpty()) out += "issues: ${sorted(line.issues)}"
            }
            when (turn.stop) {
                // the reference prints nothing more: it is dead, cut off or silent
                StopReason.EngineFailure -> out += "crash"
                StopReason.LineLimit -> out += "cut"
                StopReason.TimeLimit -> out += "timeout"
                // no case recurses without end: the reference would fill the memory
                StopReason.DepthLimit -> out += "depth"
                null -> {
                    if (turn.issues.isNotEmpty()) out += "issues: ${sorted(turn.issues)}"
                    if (turn.ended) out += "end"
                }
            }
            if (turn.choices.isEmpty()) break
            out += "choices: ${turn.choices.map { it.text to it.tags }}"
            val choice = chosen.removeFirstOrNull() ?: break
            out += "chose: $choice"
            if (!session.choose(choice)) {
                // only where the stories have already parted (random cases)
                out += "choice $choice is not offered"
                break
            }
        }
        return out
    }

    private fun describeReference(case: Case, event: JsonObject, replacements: List<JsonObject>, used: MutableSet<JsonObject>): String = when {
        "text" in event -> {
            val text = event.string("text")
            val replacement = replacements.firstOrNull { it.string("reference") == text }
            if (replacement != null) used += replacement
            "text: ${replacement?.string("port") ?: text}"
        }
        "tags" in event -> "tags: ${event.getValue("tags").jsonArray.map { it.jsonPrimitive.content }}"
        "issues" in event -> "issues: ${sorted(case.issues(event.getValue("issues")))}"
        "choices" in event -> "choices: " + event.getValue("choices").jsonArray.map { choice ->
            choice.jsonArray[0].jsonPrimitive.content to choice.jsonArray[1].jsonArray.map { it.jsonPrimitive.content }
        }
        "chose" in event -> "chose: ${event.int("chose")}"
        "end" in event -> "end"
        // the tool ended by itself without saying "end" (a missing EXTERNAL): Inky shows "End of story" for it
        "exit" in event -> "end"
        "cut" in event -> "cut"
        "timeout" in event -> "timeout"
        "crash" in event -> "crash"
        else -> error("unknown event $event")
    }

    private fun referenceOutcome(result: JsonObject): String = when {
        "hang" in result -> "hang"
        "crash" in result -> "crash"
        "output" in result -> "value: ${result.string("output")}"
        "issues" in result -> "error"
        else -> "nothing"
    }

    // --- comparing compiled stories -------------------------------------------------------------

    /**
     * Compares two ink story files as trees: the order of an object's keys does not matter (the
     * library writes named content in hash order, the reference in the order of the ink), and a
     * float is the same float however it is written (`100000000.0`, `1.0E8`). Whether a number is
     * an int or a float does matter: ink tells them apart by the way they are written.
     * Returns the path of the first difference, or null.
     */
    private fun firstDifference(theirs: JsonElement, ours: JsonElement, path: String): String? = when {
        theirs is JsonObject && ours is JsonObject ->
            if (theirs.keys != ours.keys) {
                "$path: keys ${theirs.keys - ours.keys} only in the reference, ${ours.keys - theirs.keys} only here"
            } else {
                theirs.keys.firstNotNullOfOrNull { key ->
                    val (a, b) = theirs.getValue(key) to ours.getValue(key)
                    if (key == GLOBAL_DECLARATIONS && a is JsonArray && b is JsonArray) {
                        firstDifference(byVariable(a), byVariable(b), "$path.$key")
                    } else {
                        firstDifference(a, b, "$path.$key")
                    }
                }
            }
        theirs is JsonArray && ours is JsonArray ->
            if (theirs.size != ours.size) {
                "$path: ${theirs.size} elements in the reference, ${ours.size} here"
            } else {
                theirs.indices.firstNotNullOfOrNull { firstDifference(theirs[it], ours[it], "$path[$it]") }
            }
        theirs is JsonNull && ours is JsonNull -> null
        theirs is JsonPrimitive && ours is JsonPrimitive ->
            if (canonical(theirs) == canonical(ours)) null else "$path: reference ${canonical(theirs)}, here ${canonical(ours)}"
        else -> "$path: different kinds of value"
    }

    /**
     * The global variables' declarations, `[ev, value…, {"VAR=": name}, value…, {"VAR=": name}, …, /ev, end]`,
     * as an object from each name to its value's instructions. The library writes the declarations
     * in hash order, the reference in the order of the ink; they do not depend on one another (ink
     * refuses a global whose initial value refers to another variable), so the order means nothing.
     */
    private fun byVariable(declarations: JsonArray): JsonElement {
        val values = LinkedHashMap<String, JsonElement>()
        var pending = ArrayList<JsonElement>()
        for (element in declarations) {
            val name = (element as? JsonObject)?.get("VAR=")
            if (name != null) {
                values["VAR= ${name.jsonPrimitive.content}"] = JsonArray(pending + element)
                pending = ArrayList()
            } else {
                pending += element
            }
        }
        // what is left is the frame: "ev" at the start (now in the first value) and "/ev", "end", null at the end
        values["(rest)"] = JsonArray(pending)
        val first = values.keys.first()
        val frame = (values.getValue(first) as JsonArray).first()
        if (first != "(rest)") {
            values[first] = JsonArray((values.getValue(first) as JsonArray).drop(1))
            values["(start)"] = frame
        }
        return JsonObject(values)
    }

    private fun canonical(value: JsonPrimitive): String = when {
        value.isString -> "s:${value.content}"
        value.content == "true" || value.content == "false" -> "b:${value.content}"
        value.content.any { it == '.' || it == 'e' || it == 'E' } -> "f:${value.content.toFloat().toRawBits()}"
        else -> "i:${value.content.toLong()}"
    }

    // --- helpers --------------------------------------------------------------------------------

    private fun knownList(key: String): List<JsonObject> = known.getValue(key).jsonArray.map { it.jsonObject }

    private fun sorted(issues: List<InkIssue>): List<String> =
        issues.map { "${it.kind} ${it.file}:${it.line} ${it.runtimePath.orEmpty()} ${it.message}" }.sorted()

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun resource(path: String): String = text("/com/inkyandroid/core/golden/engine/$path")

    private fun text(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) { "missing test resource $path" }.use { it.readBytes().toString(Charsets.UTF_8) }

    private companion object {
        const val GLOBAL_DECLARATIONS = "global decl"

        /** Inky's bundled "longer ink snippets". */
        val SNIPPETS = setOf(
            "a_or_an", "came_from", "listToNumber", "list_item_is_member_of", "list_pop", "list_pop_random", "list_prev_next",
            "list_random_subset", "list_random_subset_of_size", "list_with_commas", "maybe", "murder_scene", "pontoon_example",
            "print_number", "seen_more_recently_than", "seen_this_scene", "seen_very_recently", "storylets", "string_to_list",
            "swindlestones", "swing_variables", "theintercept", "thread_in_tunnel", "type_of", "uppercase",
        )
    }
}
