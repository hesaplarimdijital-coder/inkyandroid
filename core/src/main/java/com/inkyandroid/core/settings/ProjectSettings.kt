package com.inkyandroid.core.settings

import com.inkyandroid.core.snippets.SnippetNode
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Something wrong with a project settings file. The file is still used as far as it can be; the app
 * tells the user (upstream: an error box for a file that cannot be read as JSON, silence otherwise).
 *
 * [location] is where in the file, as a JSON path (`$.customInkSnippets[2].submenu[0]`).
 */
sealed interface SettingsProblem {
    val location: String

    /** The file is empty (upstream: "Project settings file appeared to be empty"). Nothing is used. */
    data object Empty : SettingsProblem {
        override val location: String get() = "$"
    }

    /** The file is not JSON (upstream: "…appeared to be invalid JSON"). [message] is the parser's. Nothing is used. */
    data class InvalidJson(val message: String) : SettingsProblem {
        override val location: String get() = "$"
    }

    /** A value has the wrong type, for example a `customInkSnippets` that is not an array; it is ignored. */
    data class WrongType(override val location: String, val expected: String) : SettingsProblem

    /**
     * A snippet entry that is neither a separator, nor a named snippet with ink, nor a named submenu; upstream
     * leaves it out of the menu, and so do we.
     */
    data class InvalidSnippet(override val location: String) : SettingsProblem
}

/**
 * A project's settings file, `<main file without .ink>.settings.json` beside the main file (see the README's
 * "Project settings file").
 */
data class ProjectSettings(
    /** The custom snippets for the Ink menu, any depth. */
    val customInkSnippets: List<SnippetNode> = emptyList(),
    /** The `instructionPrefix` (a JavaScript regular expression source), or null. */
    val instructionPrefix: String? = null,
    /** What was wrong with the file; empty for a good file. */
    val problems: List<SettingsProblem> = emptyList(),
) {
    companion object {
        /** No settings file, or nothing in it. */
        val EMPTY = ProjectSettings()

        /**
         * Reads a settings file's [text]. Never throws: anything wrong is in [problems]. Lenient where it
         * costs nothing: comments and trailing commas are accepted (the README's own example has one).
         */
        fun parse(text: String): ProjectSettings {
            // upstream: `if (!fileContent)`; a byte-order mark, which JSON.parse would reject, is skipped
            val content = text.removePrefix("\uFEFF")
            if (content.isEmpty()) return ProjectSettings(problems = listOf(SettingsProblem.Empty))
            val root = try {
                LENIENT_JSON.parseToJsonElement(content)
            } catch (e: SerializationException) {
                return ProjectSettings(problems = listOf(SettingsProblem.InvalidJson(e.message.orEmpty())))
            } catch (e: IllegalArgumentException) {
                return ProjectSettings(problems = listOf(SettingsProblem.InvalidJson(e.message.orEmpty())))
            }
            if (root !is JsonObject) {
                return ProjectSettings(problems = listOf(SettingsProblem.WrongType("$", "object")))
            }

            val problems = ArrayList<SettingsProblem>()
            val snippets = when (val value = root["customInkSnippets"]) {
                null, JsonNull -> emptyList()
                is JsonArray -> snippetItems(value, "$.customInkSnippets", problems)
                else -> {
                    problems.add(SettingsProblem.WrongType("$.customInkSnippets", "array"))
                    emptyList()
                }
            }
            val prefix = when (val value = root["instructionPrefix"]) {
                null, JsonNull -> null
                is JsonPrimitive -> if (value.isString) value.content.ifEmpty { null } else {
                    problems.add(SettingsProblem.WrongType("$.instructionPrefix", "string"))
                    null
                }
                else -> {
                    problems.add(SettingsProblem.WrongType("$.instructionPrefix", "string"))
                    null
                }
            }
            return ProjectSettings(snippets, prefix, problems)
        }

        @OptIn(ExperimentalSerializationApi::class)
        private val LENIENT_JSON = Json {
            allowComments = true
            allowTrailingComma = true
        }

        /** Upstream's `createSnippetMenuItems`: separators, named snippets with ink, named submenus; nothing else. */
        private fun snippetItems(items: JsonArray, location: String, problems: MutableList<SettingsProblem>): List<SnippetNode> {
            val result = ArrayList<SnippetNode>()
            for ((index, item) in items.withIndex()) {
                val at = "$location[$index]"
                if (item !is JsonObject) {
                    problems.add(SettingsProblem.InvalidSnippet(at))
                    continue
                }
                if (truthy(item["separator"])) {
                    result.add(SnippetNode.Separator)
                    continue
                }
                val nameValue = item["name"]
                if (!truthy(nameValue)) {
                    problems.add(SettingsProblem.InvalidSnippet(at))
                    continue
                }
                val name = (nameValue as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (name == null) {
                    problems.add(SettingsProblem.WrongType("$at.name", "string"))
                    continue
                }
                val ink = item["ink"]
                val submenu = item["submenu"]
                when {
                    truthy(ink) -> {
                        val inkText = (ink as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (inkText == null) {
                            problems.add(SettingsProblem.WrongType("$at.ink", "string"))
                            continue
                        }
                        // upstream: `accelerator`, or else the older `shortcut`
                        val accelerator = listOf("accelerator", "shortcut").firstNotNullOfOrNull { key ->
                            val v = item[key]
                            if (truthy(v)) (v as? JsonPrimitive)?.takeIf { it.isString }?.content else null
                        }
                        result.add(SnippetNode.Snippet(null, name, inkText, accelerator))
                    }
                    submenu is JsonArray -> result.add(SnippetNode.Category(null, name, snippetItems(submenu, "$at.submenu", problems)))
                    else -> problems.add(SettingsProblem.InvalidSnippet(at))
                }
            }
            return result
        }

        /** JavaScript truthiness of a JSON value (missing is falsy). */
        private fun truthy(value: JsonElement?): Boolean = when (value) {
            null, JsonNull -> false
            is JsonPrimitive -> when {
                value.isString -> value.content.isNotEmpty()
                value.content == "true" -> true
                value.content == "false" -> false
                else -> value.content.toDoubleOrNull()?.let { it != 0.0 && !it.isNaN() } ?: true
            }
            else -> true
        }

        /**
         * The settings file's root-relative path for the main ink file at [mainInkPath]: its path without a
         * final `.ink`, plus `.settings.json`.
         */
        fun pathFor(mainInkPath: String): String =
            (if (hasInkExtension(mainInkPath)) mainInkPath.dropLast(4) else mainInkPath) + ".settings.json"

        /** Node's `path.extname(p) == ".ink"`: a final `.ink` that is not the whole (dot-file) name. */
        private fun hasInkExtension(path: String): Boolean =
            path.endsWith(".ink") && path.substringAfterLast("/").length > 4
    }
}
