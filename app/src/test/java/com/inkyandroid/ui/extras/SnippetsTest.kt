package com.inkyandroid.ui.extras

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkyandroid.R
import com.inkyandroid.core.snippets.SnippetNode
import com.inkyandroid.ui.extras.snippets.SnippetEntry
import com.inkyandroid.ui.extras.snippets.SnippetLocaliser
import com.inkyandroid.ui.extras.snippets.SnippetNames
import com.inkyandroid.ui.extras.snippets.flatten
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

/** The snippets' names in both languages, and the tree the menus and the palette are made of. */
@RunWith(AndroidJUnit4::class)
class SnippetsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun localised(locale: String): Context =
        context.createConfigurationContext(context.resources.configuration.apply { setLocale(Locale.forLanguageTag(locale)) })

    @Test
    fun everyBuiltInNameHasAnEnglishAndATurkishName() {
        // upstream's tree (inkSnippets.js): ten categories and 42 snippets
        assertEquals(10, SnippetNames.categories.size)
        assertEquals(42, SnippetNames.snippets.size)
        val english = localised("en")
        val turkish = localised("tr")
        for (id in SnippetNames.categories.values + SnippetNames.snippets.values) {
            assertTrue(english.getString(id).isNotBlank())
            assertTrue(turkish.getString(id).isNotBlank())
        }
        assertEquals("Sticky choice", english.getString(R.string.snippet_choice_sticky))
        assertEquals("Kalıcı seçenek", turkish.getString(R.string.snippet_choice_sticky))
        // the descriptive words are translated
        assertNotEquals(english.getString(R.string.snippet_category_useful_functions), turkish.getString(R.string.snippet_category_useful_functions))
        // ink's own names are kept
        assertTrue(turkish.getString(R.string.snippet_flow_came_from).endsWith("came_from"))
    }

    @Test
    fun builtInNodesTakeTheirLocalNameAndCustomOnesKeepTheirs() {
        val nodes = listOf(
            SnippetNode.Category(
                "choices", "Choices",
                listOf(SnippetNode.Snippet("choice_sticky", "Sticky choice", "+ text\n"), SnippetNode.Separator, SnippetNode.Snippet("unknown_id", "Upstream name", "x")),
            ),
            SnippetNode.Separator,
            SnippetNode.Category(null, "Heaven's Vault", listOf(SnippetNode.Snippet(null, "Camera", ">>> CAMERA: Wide shot", "CmdOrCtrl+Shift+1"))),
        )
        val entries = SnippetLocaliser.of(localised("tr").resources).localise(nodes)
        val choices = entries[0] as SnippetEntry.Category
        assertEquals("Seçenekler", choices.label)
        assertEquals("Choices", choices.englishName)
        assertEquals("Kalıcı seçenek", (choices.children[0] as SnippetEntry.Snippet).label)
        assertEquals(SnippetEntry.Separator, choices.children[1])
        // an id the app has no name for shows upstream's
        assertEquals("Upstream name", (choices.children[2] as SnippetEntry.Snippet).label)
        val custom = entries[2] as SnippetEntry.Category
        assertEquals("Heaven's Vault", custom.label)
        assertEquals("CmdOrCtrl+Shift+1", (custom.children[0] as SnippetEntry.Snippet).accelerator)

        val flat = flatten(entries)
        assertEquals(listOf("Kalıcı seçenek", "Upstream name", "Camera"), flat.map { it.snippet.label })
        assertEquals(listOf("Seçenekler"), flat[0].path)
        assertEquals(listOf("Choices"), flat[0].englishPath)
        assertEquals(flat.size, flat.map { it.key }.toSet().size)
    }

    @Test
    @Config(qualifiers = "tr")
    fun thePaletteListsSnippetsInTheirGroupWithTheirCategory() {
        val inserted = ArrayList<String>()
        val custom = listOf(SnippetNode.Category(null, "Mine", listOf(SnippetNode.Snippet(null, "Wave", "~ wave()\n"))))
        val builtIn = listOf(SnippetNode.Category("choices", "Choices", listOf(SnippetNode.Snippet("choice_sticky", "Sticky choice", "+ text\n"))))
        val items = paletteItems(builtIn + SnippetNode.Separator + custom, context.resources) { inserted += it }
        val sticky = items.single { it.title == "Kalıcı seçenek" }
        assertEquals("Seçenekler", sticky.subtitle)
        // the English names find it too
        assertTrue("Sticky choice" in sticky.keywords && "Choices" in sticky.keywords)
        val mine = items.single { it.title == "Wave" }
        assertEquals("Parçacıklar", mine.group)
        assertEquals("Mine", mine.subtitle)
        mine.onSelect()
        assertEquals(listOf("~ wave()\n"), inserted)
    }
}
