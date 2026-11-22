// This file is deliberately declared in the ink library's own package, and it is the only one.
//
// blade-ink 1.3.3 keeps a few members package-private that the reference C# runtime makes public.
// Kotlin honours Java's package-private access for code declared in the same package, so the
// functions below are the whole of what the app reaches that way. Each says why. If a later version
// of the library makes these public, delete this file and call the library directly. The engine
// (com.inkyandroid.core.engine) is the only caller.
package com.bladecoder.ink.runtime

/**
 * Sets the random seed of a story that has not produced anything yet (`StoryState.storySeed`, public
 * in the C# runtime, where games set it to make a playthrough repeatable). The live player keeps one
 * seed across recompiles, so that replaying the player's choices shows the same shuffles and random
 * numbers as before.
 */
internal fun Story.inkySetStorySeed(seed: Int) {
    state.setStorySeed(seed)
}
