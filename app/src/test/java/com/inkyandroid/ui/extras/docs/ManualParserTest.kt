package com.inkyandroid.ui.extras.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The manual's Markdown as blocks: the real manual first, then each construct on its own. */
class ManualParserTest {

    private val source = File("src/main/assets/docs/WritingWithInk.md").readText()
    private val manual = ManualParser.parse(source)

    @Test
    fun everyHeadingOfTheManualIsABlock() {
        // upstream's documentation window lists one entry per line that starts with '#': 170 in 0.15.2
        val headingLines = source.lines().count { it.startsWith("#") }
        assertEquals(170, headingLines)
        assertEquals(headingLines, manual.sections.size)
        assertEquals("Writing with ink", manual.sections.first().title)
    }

    @Test
    fun everyHeadingHasItsOwnAnchorAndTheContentsReachItsSections() {
        val anchors = manual.sections.map { it.anchor }
        assertEquals(anchors.size, anchors.toSet().size)
        for (section in manual.sections) assertEquals(section.block, manual.resolve(section.anchor))
        // parts, chapters and sections; the fourth level is reached by scrolling within its section
        assertTrue(manual.contents.all { it.level <= 3 })
        assertEquals(manual.sections.count { it.level <= 3 }, manual.contents.size)
        for (section in manual.sections) assertNotNull(section.title, manual.contentsEntryAt(section.block))
    }

    @Test
    fun everyLinkInsideTheManualResolves() {
        val links = manual.blocks.flatMap { block ->
            when (block) {
                is ManualBlock.Paragraph -> block.text.links
                is ManualBlock.Heading -> block.text.links
                else -> emptyList()
            }
        }
        assertTrue("the manual links to its own sections", links.size >= 5)
        for (link in links) {
            val anchor = link.anchor ?: continue
            assertNotNull("#$anchor", manual.resolve(anchor))
        }
    }

    @Test
    fun theHandWrittenTableOfContentsIsDropped() {
        assertTrue(manual.blocks.none { "Table of Contents" in it.plainText || "<details" in it.plainText || "</details>" in it.plainText })
        // the manual starts with its title and then the introduction
        assertEquals("Writing with ink", (manual.blocks[0] as ManualBlock.Heading).text.text)
        assertEquals("introduction", (manual.blocks[1] as ManualBlock.Heading).anchor)
    }

    @Test
    fun theExamplesAreCodeBlocksAsWritten() {
        val code = manual.blocks.filterIsInstance<ManualBlock.Code>()
        assertTrue(code.size > 200)
        assertTrue(code.any { it.code == "Hello, world!" })
        // a tab-indented example keeps its inner indentation and has no final line break
        // (blank lines inside an indented example do not end it)
        val comment = code.first { "/*" in it.code }
        assertTrue(comment.code.startsWith("\"What do you make of this?\" she asked."))
        assertTrue(comment.code.contains("/*\n\t... or an unlimited block of text"))
        assertFalse(code.any { it.code.endsWith("\n") || it.code.contains('\r') })
        // the one fenced example
        assertTrue(code.any { it.code.startsWith("=== the_kitchen\n- (top)") })
    }

    @Test
    fun theListsOfTheManualHaveMarkersAndDepth() {
        val items = manual.blocks.filterIsInstance<ManualBlock.Paragraph>().filter { it.marker != null }
        assertTrue(items.any { it.text.text == "cannot contain stitches" && it.marker == "•" && it.listDepth == 1 })
        assertTrue(items.any { it.text.text.startsWith("Use ") && it.text.styles.any { s -> s.style == InlineStyle.Code } })
    }

    @Test
    fun inlineStylesAndLinksAreSpansOfThePlainText() {
        val m = ManualParser.parse("Some **strong** and *slanted* `code`, a [link](#there) and <b>raw</b>.\nNext line.")
        val p = m.blocks.single() as ManualBlock.Paragraph
        assertEquals("Some strong and slanted code, a link and <b>raw</b>. Next line.", p.text.text)
        fun covered(style: InlineStyle) = p.text.styles.single { it.style == style }.let { p.text.text.substring(it.start, it.end) }
        assertEquals("strong", covered(InlineStyle.Strong))
        assertEquals("slanted", covered(InlineStyle.Emphasis))
        assertEquals("code", covered(InlineStyle.Code))
        val link = p.text.links.single()
        assertEquals("link", p.text.text.substring(link.start, link.end))
        assertEquals("there", link.anchor)
        assertNull(LinkSpan(0, 1, "https://www.inklestudios.com/ink").anchor)
    }

    @Test
    fun nestedListsQuotesRulesAndFencesAreFlattenedWithTheirDepth() {
        val m = ManualParser.parse(
            """
            |1. first
            |
            |   more of the first
            |2. second
            |   - inner
            |
            |         code in a list
            |
            |> quoted
            |
            |---
            |
            |```ink
            |-> END
            |```
            |
            |# Same
            |# Same
            """.trimMargin(),
        )
        val b = m.blocks
        assertEquals(ManualBlock.Paragraph(RichText("first"), 1, 0, "1."), b[0])
        assertEquals(ManualBlock.Paragraph(RichText("more of the first"), 1, 0, null), b[1])
        assertEquals(ManualBlock.Paragraph(RichText("second"), 1, 0, "2."), b[2])
        assertEquals(ManualBlock.Paragraph(RichText("inner"), 2, 0, "•"), b[3])
        assertEquals(ManualBlock.Code("code in a list", 2, 0), b[4])
        assertEquals(ManualBlock.Paragraph(RichText("quoted"), 0, 1), b[5])
        assertEquals(ManualBlock.Rule(), b[6])
        assertEquals(ManualBlock.Code("-> END"), b[7])
        assertEquals(listOf("same", "same-1"), m.sections.map { it.anchor })
    }

    @Test
    fun anchorsAreGitHubs() {
        val slugger = GitHubSlugger()
        assertEquals("3-conditional-blocks-ifelse", slugger.slug("3) Conditional blocks (if/else)"))
        assertEquals("part-one-the-basics", slugger.slug("Part One: The Basics"))
        assertEquals("advanced-a-knottier-hello-world", slugger.slug("Advanced: a knottier \"hello world\""))
        assertEquals("part-one-the-basics-1", slugger.slug("Part One: The Basics"))
        assertEquals("i̇stanbul-çay", slugger.slug("İstanbul Çay"))
    }

    @Test
    fun findLooksInEveryBlockIgnoringCase() {
        val matches = manual.find("STITCH")
        assertTrue(matches.size > 20)
        for (match in matches) {
            assertTrue(manual.blocks[match.block].plainText.substring(match.start, match.end).equals("stitch", ignoreCase = true))
        }
        assertEquals(matches.sortedWith(compareBy({ it.block }, { it.start })), matches)
        assertTrue(manual.find("  ").isEmpty())
        assertTrue(manual.find("no such words in the manual").isEmpty())
        // examples are searched too
        assertTrue(manual.find("-> END").any { manual.blocks[it.block] is ManualBlock.Code })
    }

    @Test
    fun theSectionOfABlockIsTheLastContentsEntryBeforeIt() {
        val content = manual.contents.first { it.anchor == "1-content" }
        val comments = manual.contents.first { it.anchor == "comments" }
        assertEquals(content, manual.contentsEntryAt(content.block))
        // the paragraphs and examples under a heading belong to it, up to the next heading
        val next = manual.contents.first { it.block > comments.block }
        for (block in comments.block until next.block) assertEquals(comments, manual.contentsEntryAt(block))
        assertNull(manual.contentsEntryAt(-1))
    }
}
