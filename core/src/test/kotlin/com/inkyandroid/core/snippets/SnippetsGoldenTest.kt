package com.inkyandroid.core.snippets

import com.inkyandroid.core.golden.Goldens
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The built-in tree against what upstream's `inkSnippets.js` evaluates to (`golden/snippets.json`). */
class SnippetsGoldenTest {

    private val golden = Goldens.json("snippets.json").jsonArray

    @Test
    fun `the tree has upstream's categories, snippets, separators and texts in upstream's order`() {
        assertEquals(golden.size, InkSnippets.builtIn.size)
        for ((index, expected) in golden.withIndex()) {
            val actual = InkSnippets.builtIn[index]
            val e = expected.jsonObject
            if (e["separator"] != null) {
                assertSame(SnippetNode.Separator, actual)
                continue
            }
            actual as SnippetNode.Category
            assertEquals(e.getValue("categoryName").jsonPrimitive.content, actual.name)
            val snippets = e.getValue("snippets").jsonArray
            assertEquals(actual.name, snippets.size, actual.children.size)
            for ((k, s) in snippets.withIndex()) {
                val child = actual.children[k]
                val so = s.jsonObject
                if (so["separator"] != null) {
                    assertSame(SnippetNode.Separator, child)
                    continue
                }
                child as SnippetNode.Snippet
                assertEquals(so.getValue("name").jsonPrimitive.content, child.name)
                assertEquals(child.name, so.getValue("ink").jsonPrimitive.content, child.ink)
                assertNull(child.accelerator)
            }
        }
    }

    @Test
    fun `every built-in node has a unique id of lower-case letters and underscores, found by byId`() {
        val ids = ArrayList<String>()
        fun visit(node: SnippetNode) {
            when (node) {
                is SnippetNode.Category -> { ids.add(node.id!!); node.children.forEach(::visit) }
                is SnippetNode.Snippet -> ids.add(node.id!!)
                SnippetNode.Separator -> Unit
            }
        }
        InkSnippets.builtIn.forEach(::visit)
        assertEquals(ids.size, ids.toSet().size)
        for (id in ids) {
            assertTrue(id, Regex("[a-z_]+").matches(id))
            assertEquals(id, (InkSnippets.byId(id) as? SnippetNode.Category)?.id ?: (InkSnippets.byId(id) as SnippetNode.Snippet).id)
        }
        assertEquals("10 categories and 42 snippets", 10 + 42, ids.size)
        assertNull(InkSnippets.byId("no_such_snippet"))
    }

    @Test
    fun `the menu adds a separator and the custom entries only when there are custom entries`() {
        assertSame(InkSnippets.builtIn, InkSnippets.menu(emptyList()))
        val custom = SnippetNode.Snippet(null, "Camera", ">>> CAMERA: Wide shot")
        assertEquals(InkSnippets.builtIn + SnippetNode.Separator + custom, InkSnippets.menu(listOf(custom)))
    }
}
