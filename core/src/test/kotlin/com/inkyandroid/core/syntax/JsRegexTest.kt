package com.inkyandroid.core.syntax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.regex.Pattern
import kotlin.random.Random

/**
 * The JavaScript-to-Java translation where the dialects differ (every expectation here was checked in
 * Node 22's V8), and the soundness of [JsRegex.startInfo], which the tokenizer relies on to skip positions
 * (instruction prefixes are written by users, so this is checked on arbitrary regexes, not only on
 * upstream's rules).
 */
class JsRegexTest {

    private fun matches(js: String, text: String) = JsRegex.compile(js).matcher(text).find()

    @Test
    fun `white space is JavaScript's`() {
        for (c in listOf(' ', ' ', '　', '﻿', ' ', '\u000B', '\t')) assertTrue("$c", matches("^\\s$", "$c"))
        assertFalse("U+0085 is not JavaScript white space", matches("^\\s$", "\u0085"))
        assertTrue(matches("^\\S$", "\u0085"))
    }

    @Test
    fun `word characters and boundaries are ASCII`() {
        assertFalse(matches("\\w", "çğı"))
        assertTrue(matches("^\\W+$", "çğı"))
        assertTrue("ç is not a word character, so there is a boundary before a", matches("ç\\ba", "ça"))
        assertTrue(matches("\\bTODO\\b", "a TODO."))
        assertFalse(matches("\\bTODO\\b", "aTODO"))
    }

    @Test
    fun `dot and dollar are JavaScript's`() {
        assertTrue("U+0085 is matched by dot", matches("^.$", "\u0085"))
        assertFalse(matches("^.$", " "))
        assertFalse("dollar is only the end", matches("a$", "a "))
        assertFalse(matches("a$", "a\n"))
    }

    @Test
    fun `character classes follow Annex B`() {
        // [\w,\s->] from upstream's rules: "-" next to a class escape is a literal
        val p = "^[\\w,\\s->]+$"
        assertTrue(matches(p, "a, b -> c"))
        assertFalse(matches(p, "a=b"))
        assertTrue("[ and & are literals in a JavaScript class", matches("^[[&]+$", "[&&["))
        assertTrue(matches("^[^\\[\\]$]+$", "# tag with"))
        assertFalse("$ is a literal in a class", matches("^[^\\[\\]$]+$", "# tag $"))
        assertFalse(matches("[]", "anything"))
        assertTrue(matches("^[^]$", "\n"))
    }

    @Test
    fun `lenient escapes and braces are literals`() {
        assertTrue(matches("^a{$", "a{"))
        assertTrue(matches("^a{2}$", "aa"))
        assertTrue(matches("^\\p$", "p"))
        assertTrue(matches("^\\/$", "/"))
        assertTrue(matches("^\\ca$", "\u0001"))
        assertTrue("\\c without a letter is a backslash and c", matches("^\\c1$", "\\c1"))
        assertTrue("octal when there is no such group", matches("^\\101$", "A"))
        assertTrue("a backreference when there is", matches("^(a)\\1$", "aa"))
        assertTrue(matches("^\\x41\\u0042$", "AB"))
        assertTrue(matches("^\\x4$", "x4"))
    }

    @Test
    fun `invalid sources are rejected`() {
        for (bad in listOf("(", "[a", "a)", "[b-a]", "\\")) {
            try {
                JsRegex.compile(bad)
                fail("accepted /$bad/")
            } catch (e: JsRegex.SyntaxException) {
                assertNotNull(e.message)
            }
        }
    }

    @Test
    fun `group counting matches Java's`() {
        for (source in listOf("(a)(?:b)(?=c)(?<n>d)", "[(](x)", "\\((y)", "(?<=a)(b)")) {
            assertEquals(source, JsRegex.compile(source).matcher("").groupCount(), JsRegex.capturingGroupCount(source))
        }
    }

    @Test
    fun `start info never excludes a position where the regex matches`() {
        val tokenizer = InkTokenizer(">>>")
        val sources = tokenizer.ace.ruleSet.values.flatten().mapNotNull { it.regex }.toSet() +
            listOf(">>>", "\\$", "x*", "(>>)", "a|b", "(?:ab)+c", "(ab)?c", "\\bfoo", "^a|b", "[^a]b", "a(?=b)c?", ".*x", "(a|bc)d{2,3}")
        val alphabet = " \t abcxfo>$-*+=([{}])|~#/\\<!:&\"\nIDNCLUET0é😀".toList()
        val random = Random(42)
        for (source in sources) {
            val info = JsRegex.startInfo(source)
            val pattern = Pattern.compile(JsRegex.toJava(source))
            repeat(400) {
                val text = buildString { repeat(random.nextInt(0, 14)) { append(alphabet[random.nextInt(alphabet.size)]) } }
                val matcher = pattern.matcher(text).useTransparentBounds(true).useAnchoringBounds(false)
                for (p in 0..text.length) {
                    matcher.region(p, text.length)
                    if (!matcher.lookingAt()) continue
                    val where = "/$source/ matches \"$text\" at $p"
                    if (info.onlyAtInputStart) assertEquals(where, 0, p)
                    info.firstUnits?.let { assertTrue(where, p < text.length && it.get(text[p].code)) }
                    info.requiredText?.let { assertTrue(where, text.indexOf(it, p) >= 0) }
                }
            }
        }
    }
}
