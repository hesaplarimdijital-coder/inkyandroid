package com.inkyandroid.core.engine

import org.junit.Assert.assertTrue

/** Small ink projects shared by the tests. Line numbers in the tests refer to these texts. */
object Stories {
    /** A main file and two levels of includes, every line number deliberate. */
    val twoFiles = InkSources(
        mainPath = "main.ink",
        texts = mapOf(
            "main.ink" to """
                |# author: Barış
                |INCLUDE sub/part.ink
                |VAR x = 5
                |Line one. # tag1
                |Value of x is {x} and more.
                |-> knot_a
                |== knot_a ==
                |A {&first|second} sequence.
                |* [Go to the part] -> part_knot
                |* Choice text # ctag
                |  After choice.
                |  -> END
                |
            """.trimMargin(),
            "sub/part.ink" to """
                |INCLUDE sub/helpers.ink
                |== part_knot ==
                |Part line one.
                |{shout("hey")} said the part.
                |-> END
                |
            """.trimMargin(),
            "sub/helpers.ink" to """
                |== function shout(word) ==
                |Loud
                |~ return word
                |
            """.trimMargin(),
        ),
    )

    fun single(text: String) = InkSources("main.ink", mapOf("main.ink" to text.trimIndent() + "\n"))

    fun compiled(sources: InkSources): CompiledStory {
        val result = InkEngine.Default.compile(sources)
        assertTrue(result.issues.toString(), result is CompileResult.Success)
        return (result as CompileResult.Success).story
    }

    fun play(sources: InkSources, limits: PlayLimits = PlayLimits(), seed: Int? = null): PlaySession =
        compiled(sources).play(PlayOptions(limits, seed))

    fun play(text: String, limits: PlayLimits = PlayLimits(), seed: Int? = null): PlaySession = play(single(text), limits, seed)
}
