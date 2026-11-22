package com.inkyandroid.core

import com.bladecoder.ink.compiler.Compiler
import com.bladecoder.ink.runtime.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The ink library on the JVM, before :core wraps it: a few lines of ink are compiled, played to
 * their choice and on to their end, once straight from the compiler and once through the JSON
 * that Inky hands from its compiler to its player.
 */
class BladeInkSmokeTest {

    private val source = """
        Kedi kapının önünde oturuyor.
        * [Kapıyı aç] Kedi içeri giriyor.
        * [Bekle] Kedi bekliyor.
        - Akşam oluyor.
        -> END
    """.trimIndent()

    /** continueMaximally: every line the story has to give until it stops, at a choice or at its end. */
    private fun playToTheEnd(story: Story) {
        assertEquals("Kedi kapının önünde oturuyor.\n", story.continueMaximally())
        assertEquals(listOf("Kapıyı aç", "Bekle"), story.currentChoices.map { it.text })

        story.chooseChoiceIndex(0)
        assertEquals("Kedi içeri giriyor.\nAkşam oluyor.\n", story.continueMaximally())
        assertEquals(emptyList<String>(), story.currentChoices.map { it.text })
        assertFalse(story.canContinue())
    }

    @Test
    fun compilesAndPlaysToAChoice() {
        playToTheEnd(Compiler(source, null).compile())
    }

    @Test
    fun playsTheCompiledJson() {
        val json = Compiler(source, null).compile().toJson()
        playToTheEnd(Story(json))
    }
}
