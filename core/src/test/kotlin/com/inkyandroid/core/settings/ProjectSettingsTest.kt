package com.inkyandroid.core.settings

import com.inkyandroid.core.snippets.SnippetNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The project settings file, read as upstream reads it (`projectWindow.js`, `appmenus.js`), leniently. */
class ProjectSettingsTest {

    @Test
    fun `the README's example, trailing comma and all`() {
        val settings = ProjectSettings.parse(
            """
            {
                "customInkSnippets": [
                    {
                        "name": "Heaven's Vault",
                        "submenu": [
                            {
                                "name": "Camera",
                                "ink": ">>> CAMERA: Wide shot"
                            },
                            {
                                "separator": true
                            },
                            {
                                "name": "Walk",
                                "ink": ">>> WALK: TheInscription"
                            },
                            {
                                "name": "More snippets",
                                "submenu": [
                                    {
                                        "name": "A snippet in a submenu",
                                        "ink": "This snippet of ink came from a submenu."
                                    },
                                ]
                            }
                        ]
                    }
                ],

                "instructionPrefix": ">>>"
            }
            """.trimIndent(),
        )
        assertEquals(emptyList<SettingsProblem>(), settings.problems)
        assertEquals(">>>", settings.instructionPrefix)
        val expected = listOf(
            SnippetNode.Category(
                null, "Heaven's Vault",
                listOf(
                    SnippetNode.Snippet(null, "Camera", ">>> CAMERA: Wide shot"),
                    SnippetNode.Separator,
                    SnippetNode.Snippet(null, "Walk", ">>> WALK: TheInscription"),
                    SnippetNode.Category(null, "More snippets", listOf(SnippetNode.Snippet(null, "A snippet in a submenu", "This snippet of ink came from a submenu."))),
                ),
            ),
        )
        assertEquals(expected, settings.customInkSnippets)
    }

    @Test
    fun `empty and invalid files are reported and give nothing`() {
        assertEquals(listOf(SettingsProblem.Empty), ProjectSettings.parse("").problems)
        assertEquals(listOf(SettingsProblem.Empty), ProjectSettings.parse("﻿").problems)
        val invalid = ProjectSettings.parse("{ \"instructionPrefix\": ")
        assertTrue(invalid.problems.single() is SettingsProblem.InvalidJson)
        assertNull(invalid.instructionPrefix)
        assertEquals(listOf(SettingsProblem.WrongType("$", "object")), ProjectSettings.parse("[1, 2]").problems)
        assertEquals(ProjectSettings.EMPTY, ProjectSettings.parse("{}"))
        assertEquals(">", ProjectSettings.parse("﻿// a comment\n{\"instructionPrefix\": \">\"}").instructionPrefix)
    }

    @Test
    fun `wrong types are reported and ignored`() {
        val settings = ProjectSettings.parse("""{"customInkSnippets": {"name": "x"}, "instructionPrefix": 5}""")
        assertEquals(
            listOf(SettingsProblem.WrongType("$.customInkSnippets", "array"), SettingsProblem.WrongType("$.instructionPrefix", "string")),
            settings.problems,
        )
        assertEquals(emptyList<SnippetNode>(), settings.customInkSnippets)
        assertNull(settings.instructionPrefix)
        assertNull(ProjectSettings.parse("""{"instructionPrefix": ""}""").instructionPrefix)
    }

    @Test
    fun `snippet entries follow upstream's rules`() {
        val settings = ProjectSettings.parse(
            """
            {"customInkSnippets": [
                {"separator": 1, "name": "ignored, the separator wins"},
                {"name": "With accelerator", "ink": "a", "accelerator": "CmdOrCtrl+1", "shortcut": "Alt+2"},
                {"name": "With shortcut", "ink": "b", "shortcut": "Alt+2"},
                {"name": "Empty submenu", "submenu": []},
                {"name": "", "ink": "no name"},
                {"name": "No ink"},
                {"name": "Empty ink", "ink": ""},
                {"name": 7, "ink": "x"},
                {"name": "Ink not text", "ink": 7},
                "a string",
                null,
                {"separator": false, "name": "Not a separator", "ink": "c"}
            ]}
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                SnippetNode.Separator,
                SnippetNode.Snippet(null, "With accelerator", "a", "CmdOrCtrl+1"),
                SnippetNode.Snippet(null, "With shortcut", "b", "Alt+2"),
                SnippetNode.Category(null, "Empty submenu", emptyList()),
                SnippetNode.Snippet(null, "Not a separator", "c"),
            ),
            settings.customInkSnippets,
        )
        assertEquals(
            listOf(
                SettingsProblem.InvalidSnippet("$.customInkSnippets[4]"),
                SettingsProblem.InvalidSnippet("$.customInkSnippets[5]"),
                SettingsProblem.InvalidSnippet("$.customInkSnippets[6]"),
                SettingsProblem.WrongType("$.customInkSnippets[7].name", "string"),
                SettingsProblem.WrongType("$.customInkSnippets[8].ink", "string"),
                SettingsProblem.InvalidSnippet("$.customInkSnippets[9]"),
                SettingsProblem.InvalidSnippet("$.customInkSnippets[10]"),
            ),
            settings.problems,
        )
    }

    @Test
    fun `the settings file sits beside the main file`() {
        assertEquals("story.settings.json", ProjectSettings.pathFor("story.ink"))
        assertEquals("dir/main.settings.json", ProjectSettings.pathFor("dir/main.ink"))
        assertEquals("story.txt.settings.json", ProjectSettings.pathFor("story.txt"))
        assertEquals("dir/.ink.settings.json", ProjectSettings.pathFor("dir/.ink"))
    }
}
