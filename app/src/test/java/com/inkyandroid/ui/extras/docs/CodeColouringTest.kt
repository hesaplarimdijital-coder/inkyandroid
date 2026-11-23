package com.inkyandroid.ui.extras.docs

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkyandroid.ink.InkHighlight
import com.inkyandroid.testPrefs
import desktopkit.DesktopKitRoot
import desktopkit.theme.DeskSyntax
import desktopkit.theme.DeskTheme
import desktopkit.theme.SyntaxRole
import desktopkit.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The manual's examples in the editor's colours, on the right characters after the tabs are expanded. */
@RunWith(AndroidJUnit4::class)
class CodeColouringTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rolesLandOnTheDisplayedCharacters() {
        val block = ManualBlock.Code("=== knot ===\n\t* [Go] -> other // note")
        lateinit var syntax: DeskSyntax
        lateinit var coloured: AnnotatedString
        compose.setContent {
            DesktopKitRoot(testPrefs(ThemeMode.Dark)) {
                syntax = DeskTheme.syntax
                coloured = colourCode(block, InkHighlight.spans(block.code), syntax)
            }
        }
        compose.waitForIdle()
        assertEquals("=== knot ===\n    * [Go] -> other // note", coloured.text)
        fun styleAt(text: String) = coloured.text.indexOf(text).let { at ->
            coloured.spanStyles.filter { it.start <= at && at < it.end }.last().item
        }
        // the knot's line is a heading, the divert target a link, the comment a comment: each where it is shown
        assertEquals(syntax[SyntaxRole.Heading].color, styleAt("knot").color)
        assertEquals(syntax[SyntaxRole.Link].color, styleAt("other").color)
        assertEquals(syntax[SyntaxRole.Comment].color, styleAt("// note").color)
        assertEquals(FontStyle.Italic, styleAt("// note").fontStyle)
        assertTrue(coloured.spanStyles.first().item.color == syntax.plain)
    }
}
