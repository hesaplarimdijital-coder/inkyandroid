// This file is deliberately declared in the ink library's own package, and it is the only one.
//
// blade-ink 1.3.3 keeps a few members package-private that the reference C# runtime makes public,
// and it has three defects in its table of native functions. Kotlin honours Java's package-private
// access for code declared in the same package, so the functions below are the whole of what the
// app reaches that way. Each says why. If a later version of the library makes the members public
// and corrects the table, delete this file and call the library directly. The engine
// (com.inkyandroid.core.engine) is the only caller.
package com.bladecoder.ink.runtime

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Sets the random seed of a story that has not produced anything yet (`StoryState.storySeed`, public
 * in the C# runtime, where games set it to make a playthrough repeatable). The live player keeps one
 * seed across recompiles, so that replaying the player's choices shows the same shuffles and random
 * numbers as before.
 */
internal fun Story.inkySetStorySeed(seed: Int) {
    state.setStorySeed(seed)
}

/**
 * Builds the library's table of native functions (`+`, `FLOOR`, `LIST_COUNT`, …) and replaces the
 * four entries that are wrong in blade-ink 1.3.3. Call it once before any ink runs.
 *
 * - `FLOOR(x)` and `CEILING(x)` of a float cast the boxed `Float` to `double` and throw
 *   `ClassCastException`; so does `INT(x)` of a float, cast to `int`. A story that rounds a number
 *   dies of it. The reference: `(float)Math.Floor(x)`, `(float)Math.Ceiling(x)`, `(int)x`.
 * - `FLOAT(n)` of an int returns the int, so `FLOAT(3) / 2` is 1. The reference: `(float)n`, 1.5.
 *
 * The table is static and built lazily without synchronisation; building it here, from the
 * engine's own initialisation, also settles that before two threads can meet in it.
 */
internal fun inkyPrepareNativeFunctions() {
    NativeFunctionCall.generateNativeFunctionsIfNecessary()
    NativeFunctionCall.addFloatUnaryOp(NativeFunctionCall.Floor) { value -> floor((value as Float).toDouble()).toFloat() }
    NativeFunctionCall.addFloatUnaryOp(NativeFunctionCall.Ceiling) { value -> ceil((value as Float).toDouble()).toFloat() }
    NativeFunctionCall.addFloatUnaryOp(NativeFunctionCall.Int) { value -> (value as Float).toInt() }
    NativeFunctionCall.addIntUnaryOp(NativeFunctionCall.Float) { value -> (value as Int).toFloat() }
}
