package com.inkyandroid.core.syntax

import java.util.regex.Pattern

/**
 * Translates JavaScript regular expression sources (ECMAScript without the `u` flag, with the web
 * compatibility rules of Annex B, as V8 runs Ace's rules) into `java.util.regex` sources that match the
 * same text on the JVM and on Android.
 *
 * The two dialects agree on most syntax but not on several meanings:
 * - `\s` is Unicode white space in JavaScript (with NBSP, U+2000–U+200A, U+3000, U+FEFF …), ASCII in Java
 *   and Unicode-but-different on Android (ICU); `\w`, `\d`, `\b` are ASCII in JavaScript and Unicode on
 *   Android. All of them become explicit sets here.
 * - `.` excludes `\n`, `\r`, U+2028 and U+2029 in JavaScript; Java also excludes U+0085.
 * - `$` (without the `m` flag) is the end of the input in JavaScript; in Java it also matches before a
 *   final line terminator. It becomes `\z`.
 * - Inside a class Java treats `[` (nesting), `&&` (intersection) specially, and the rules for `-`
 *   next to a class escape differ. Classes are rebuilt from their computed set of characters.
 * - Annex B leniencies (a `{` that is not a quantifier, `\c` without a letter, legacy octal escapes,
 *   identity escapes of letters such as `\p`) are literal characters, which Java would reject.
 *
 * JavaScript without `u` works on UTF-16 units and Java on code points; the two differ only when a
 * single-character construct meets a character outside the Basic Multilingual Plane, which no ink rule
 * does (repetitions such as `[^x]+` and `.*` cover the same text).
 */
internal object JsRegex {

    /** JavaScript's white space and line terminators (`\s`), as UTF-16 ranges. */
    private val SPACE = intArrayOf(
        0x09, 0x0D, 0x20, 0x20, 0xA0, 0xA0, 0x1680, 0x1680, 0x2000, 0x200A,
        0x2028, 0x2029, 0x202F, 0x202F, 0x205F, 0x205F, 0x3000, 0x3000, 0xFEFF, 0xFEFF,
    )
    private val WORD = intArrayOf('0'.code, '9'.code, 'A'.code, 'Z'.code, '_'.code, '_'.code, 'a'.code, 'z'.code)
    private val DIGIT = intArrayOf('0'.code, '9'.code)

    private const val WORD_CLASS = "[0-9A-Z_a-z]"
    private const val WORD_BOUNDARY = "(?:(?<=$WORD_CLASS)(?!$WORD_CLASS)|(?<!$WORD_CLASS)(?=$WORD_CLASS))"
    private const val NOT_WORD_BOUNDARY = "(?:(?<=$WORD_CLASS)(?=$WORD_CLASS)|(?<!$WORD_CLASS)(?!$WORD_CLASS))"

    /** Thrown for a source JavaScript itself would reject, or that Java cannot express. */
    class SyntaxException(message: String) : IllegalArgumentException(message)

    /** Compiles the JavaScript [source] (flag `i` when [ignoreCase]) for Java. */
    fun compile(source: String, ignoreCase: Boolean = false): Pattern {
        val java = toJava(source)
        val flags = if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0
        return try {
            Pattern.compile(java, flags)
        } catch (e: java.util.regex.PatternSyntaxException) {
            throw SyntaxException("Invalid regular expression /$source/: ${e.description}")
        }
    }

    /** The number of capturing groups (numbered and named) in the JavaScript [source]. */
    fun capturingGroupCount(source: String): Int = Scanner(source).countGroups()

    /** The Java source matching what the JavaScript [source] matches. */
    fun toJava(source: String): String = Translator(source).translate()

    /** Walks a source outside character classes and escapes, as both the counter and the translator need. */
    private class Scanner(val s: String) {
        fun countGroups(): Int {
            var count = 0
            var i = 0
            var inClass = false
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '\\' -> i++
                    inClass -> if (c == ']') inClass = false
                    c == '[' -> inClass = true
                    c == '(' -> {
                        if (i + 1 >= s.length || s[i + 1] != '?') count++
                        else if (i + 2 < s.length && s[i + 2] == '<' && i + 3 < s.length && s[i + 3] != '=' && s[i + 3] != '!') count++
                    }
                }
                i++
            }
            return count
        }

        fun hasNamedGroups(): Boolean {
            var i = 0
            var inClass = false
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '\\' -> i++
                    inClass -> if (c == ']') inClass = false
                    c == '[' -> inClass = true
                    c == '(' && s.startsWith("(?<", i) && i + 3 < s.length && s[i + 3] != '=' && s[i + 3] != '!' -> return true
                }
                i++
            }
            return false
        }
    }

    private open class Translator(val s: String) {
        protected val out = StringBuilder(s.length * 2)
        protected var i = 0
        private val groupCount = Scanner(s).countGroups()
        private val namedGroups = Scanner(s).hasNamedGroups()

        fun translate(): String {
            while (i < s.length) {
                val c = s[i]
                when (c) {
                    '\\' -> escapeOutsideClass()
                    '[' -> characterClass()
                    '.' -> { out.append("[^\\n\\r\\u2028\\u2029]"); i++ }
                    '$' -> { out.append("\\z"); i++ }
                    '{' -> quantifierOrBrace()
                    '}' -> { out.append("\\}"); i++ }
                    ']' -> { out.append("\\]"); i++ }
                    else -> { out.append(c); i++ }
                }
            }
            return out.toString()
        }

        private fun quantifierOrBrace() {
            val end = quantifierEnd(i)
            if (end < 0) {
                out.append("\\{")
                i++
            } else {
                out.append(s, i, end)
                i = end
            }
        }

        /** The index after a `{n}`, `{n,}` or `{n,m}` quantifier starting at [start], or -1. */
        protected fun quantifierEnd(start: Int): Int {
            var j = start + 1
            val digitsStart = j
            while (j < s.length && s[j].isAsciiDigit()) j++
            if (j == digitsStart) return -1
            if (j < s.length && s[j] == ',') {
                j++
                while (j < s.length && s[j].isAsciiDigit()) j++
            }
            return if (j < s.length && s[j] == '}') j + 1 else -1
        }

        private fun escapeOutsideClass() {
            if (i + 1 >= s.length) throw SyntaxException("\\ at end of pattern /$s/")
            val d = s[i + 1]
            i += 2
            when (d) {
                'd' -> out.append("[0-9]")
                'D' -> out.append("[^0-9]")
                'w' -> out.append(WORD_CLASS)
                'W' -> out.append("[^0-9A-Z_a-z]")
                's' -> out.append('[').append(rangesSource(SPACE)).append(']')
                'S' -> out.append("[^").append(rangesSource(SPACE)).append(']')
                'b' -> out.append(WORD_BOUNDARY)
                'B' -> out.append(NOT_WORD_BOUNDARY)
                'k' -> if (namedGroups) out.append("\\k") else literal('k'.code)
                in '1'..'9' -> backreferenceOrOctal(d)
                else -> literal(characterEscape(d))
            }
        }

        /** `\N`: a backreference if the pattern has N groups, else a legacy octal escape or a digit (Annex B). */
        private fun backreferenceOrOctal(first: Char) {
            var j = i
            while (j < s.length && s[j].isAsciiDigit()) j++
            val number = (first.toString() + s.substring(i, j)).toBigInteger()
            if (number <= groupCount.toBigInteger()) {
                out.append("(?:\\").append(number).append(')')
                i = j
            } else {
                i-- // back to the first digit
                literal(legacyOctalOrDigit())
            }
        }

        /** At s[i], a digit after a backslash that is not a backreference: up to three octal digits, else the digit. */
        protected fun legacyOctalOrDigit(): Int {
            val c = s[i]
            if (c == '8' || c == '9') {
                i++
                return c.code
            }
            var value = 0
            var digits = 0
            while (i < s.length && s[i] in '0'..'7' && digits < 3) {
                val next = value * 8 + (s[i] - '0')
                if (next > 0xFF) break
                value = next
                digits++
                i++
            }
            return value
        }

        /**
         * The character a non-class escape `\d` stands for (the backslash and [d] already consumed), in a
         * class or outside one: control escapes, `\cX`, `\xHH`, `\uHHHH`, `\0`, legacy octal, else [d] itself.
         */
        protected fun characterEscape(d: Char): Int = when (d) {
            't' -> 0x09
            'n' -> 0x0A
            'v' -> 0x0B
            'f' -> 0x0C
            'r' -> 0x0D
            'c' -> if (i < s.length && s[i].isAsciiLetter()) {
                s[i++].code % 32
            } else {
                // Annex B: "\c" not followed by a letter is a backslash followed by "c"
                i-- // re-read the "c" as a literal next
                '\\'.code
            }
            'x' -> hexEscape(2) ?: 'x'.code
            'u' -> hexEscape(4) ?: 'u'.code
            '0' -> if (i < s.length && s[i].isAsciiDigit()) {
                i-- // legacy octal starting with 0
                legacyOctalOrDigit()
            } else {
                0
            }
            else -> d.code
        }

        private fun hexEscape(digits: Int): Int? {
            if (i + digits > s.length) return null
            val hex = s.substring(i, i + digits)
            if (!hex.all { it.isAsciiHexDigit() }) return null
            i += digits
            return hex.toInt(16)
        }

        private fun literal(code: Int) {
            appendUnit(out, code)
        }

        private fun characterClass() {
            val (negated, ranges) = parseClass()
            when {
                ranges.isEmpty() && !negated -> out.append("(?!)")
                ranges.isEmpty() -> out.append("(?s:.)")
                else -> out.append(if (negated) "[^" else "[").append(rangesSource(ranges)).append(']')
            }
        }

        /** Parses a class at s[i] == '[': whether it is negated, and its (positive) set of units. */
        fun parseClass(): Pair<Boolean, IntArray> {
            i++ // [
            val negated = i < s.length && s[i] == '^'
            if (negated) i++
            val set = UnitSet()
            while (true) {
                if (i >= s.length) throw SyntaxException("Unterminated character class in /$s/")
                if (s[i] == ']') {
                    i++
                    break
                }
                val first = classAtom()
                if (i + 1 < s.length && s[i] == '-' && s[i + 1] != ']') {
                    i++ // -
                    val second = classAtom()
                    if (first is Atom.Unit && second is Atom.Unit) {
                        if (first.code > second.code) throw SyntaxException("Range out of order in character class in /$s/")
                        set.add(first.code, second.code)
                    } else {
                        // Annex B: a class escape next to "-" makes the "-" a literal
                        first.addTo(set)
                        set.add('-'.code, '-'.code)
                        second.addTo(set)
                    }
                } else {
                    first.addTo(set)
                }
            }
            return negated to set.ranges()
        }

        protected sealed interface Atom {
            fun addTo(set: UnitSet)

            data class Unit(val code: Int) : Atom {
                override fun addTo(set: UnitSet) = set.add(code, code)
            }

            data class Ranges(val ranges: IntArray, val complement: Boolean) : Atom {
                override fun addTo(set: UnitSet) {
                    val r = if (complement) complementOf(ranges) else ranges
                    for (k in r.indices step 2) set.add(r[k], r[k + 1])
                }
            }
        }

        protected fun classAtom(): Atom {
            val c = s[i]
            if (c != '\\') {
                i++
                return Atom.Unit(c.code)
            }
            if (i + 1 >= s.length) throw SyntaxException("\\ at end of pattern /$s/")
            val d = s[i + 1]
            i += 2
            return when (d) {
                'd' -> Atom.Ranges(DIGIT, complement = false)
                'D' -> Atom.Ranges(DIGIT, complement = true)
                'w' -> Atom.Ranges(WORD, complement = false)
                'W' -> Atom.Ranges(WORD, complement = true)
                's' -> Atom.Ranges(SPACE, complement = false)
                'S' -> Atom.Ranges(SPACE, complement = true)
                'b' -> Atom.Unit(0x08)
                '-' -> Atom.Unit('-'.code)
                in '1'..'9' -> {
                    i-- // in a class, digits after a backslash are octal (or the digit itself)
                    Atom.Unit(legacyOctalOrDigit())
                }
                else -> Atom.Unit(characterEscape(d))
            }
        }
    }

    /**
     * Where a match of a regex can start, worked out from its JavaScript source, so that the tokenizer
     * only tries it there. Conservative: when in doubt, anywhere.
     */
    class StartInfo(
        /** Only at the start of the input (the regex begins with `^`, without the `m` flag). */
        val onlyAtInputStart: Boolean,
        /** The units a match can start with, or null if it can start anywhere (it can match empty, …). */
        val firstUnits: java.util.BitSet?,
        /** Text every match contains, or null: where the rest of the line lacks it, there is no match. */
        val requiredText: String? = null,
    )

    /** [StartInfo] of the JavaScript [source]. */
    fun startInfo(source: String): StartInfo {
        val info = Analyzer(source).analyze()
        return StartInfo(
            onlyAtInputStart = info.anchored,
            firstUnits = if (info.anything || info.nullable) null else info.first,
            requiredText = info.required?.takeIf { it.isNotEmpty() },
        )
    }

    /** What a piece of regex can start with. */
    private class Info(
        val first: java.util.BitSet = java.util.BitSet(),
        var anything: Boolean = false,
        var nullable: Boolean = false,
        var anchored: Boolean = false,
        /** Text every match contains. */
        var required: String? = null,
        /** The one unit this atom matches, if it is a single literal. */
        var literal: Int = -1,
    )

    private class Analyzer(source: String) : Translator(source) {
        fun analyze(): Info {
            val info = alternation()
            if (i < s.length) return Info(anything = true) // an unbalanced ")": let the regex engine judge
            return info
        }

        private fun alternation(): Info {
            val result = sequence()
            while (i < s.length && s[i] == '|') {
                i++
                val branch = sequence()
                result.first.or(branch.first)
                result.anything = result.anything || branch.anything
                result.nullable = result.nullable || branch.nullable
                result.anchored = result.anchored && branch.anchored
                result.required = null
                result.literal = -1
            }
            return result
        }

        private fun sequence(): Info {
            val result = Info(nullable = true)
            var open = true // everything so far can match empty, so the next term can start the match
            val run = StringBuilder() // consecutive single literals, all required
            var terms = 0
            fun keepLongest(candidate: String?) {
                if (candidate != null && candidate.length > (result.required?.length ?: 0)) result.required = candidate
            }
            while (i < s.length && s[i] != '|' && s[i] != ')') {
                val term = term()
                terms++
                if (term.literal >= 0) {
                    run.append(term.literal.toChar())
                } else {
                    // ^, $, \b and lookarounds take no text: literals on both sides stay one run
                    val zeroWidth = term.nullable && term.first.isEmpty && !term.anything
                    if (!zeroWidth) {
                        keepLongest(run.toString())
                        run.setLength(0)
                    }
                    if (!term.nullable) keepLongest(term.required)
                }
                if (open) {
                    if (term.anchored) result.anchored = true
                    result.first.or(term.first)
                    if (term.anything) result.anything = true
                    if (!term.nullable) {
                        open = false
                        result.nullable = false
                    }
                }
            }
            keepLongest(run.toString())
            if (terms == 1 && result.required?.length == 1 && !result.nullable) {
                result.literal = result.required!![0].code
            }
            return result
        }

        private fun term(): Info {
            val atom = atom()
            if (i < s.length) {
                val c = s[i]
                var minimumZero = false
                var quantified = true
                when (c) {
                    '*', '?' -> { minimumZero = true; i++ }
                    '+' -> i++
                    '{' -> {
                        val end = quantifierEnd(i)
                        if (end < 0) {
                            quantified = false
                        } else {
                            minimumZero = s.substring(i + 1, end - 1).substringBefore(',').toInt() == 0
                            i = end
                        }
                    }
                    else -> quantified = false
                }
                if (quantified && i < s.length && s[i] == '?') i++ // lazy
                if (quantified) atom.literal = -1 // no longer exactly one unit
                if (minimumZero) {
                    atom.nullable = true
                    atom.anchored = false
                    atom.required = null
                }
            }
            return atom
        }

        private fun zeroWidth(anchored: Boolean = false) = Info(nullable = true, anchored = anchored)

        private fun units(code: Int) = Info(required = code.toChar().toString(), literal = code).also { it.first.set(code) }

        private fun ranges(ranges: IntArray, negated: Boolean): Info {
            val info = Info()
            val r = if (negated) complementOf(ranges) else ranges
            for (k in r.indices step 2) info.first.set(r[k], r[k + 1] + 1)
            // Java matches a character outside the BMP as one code point: be generous with surrogates
            if (info.first.nextSetBit(0xD800) in 0xD800..0xDFFF) info.first.set(0xD800, 0xE000)
            return info
        }

        private fun atom(): Info {
            val c = s[i]
            return when (c) {
                '^' -> { i++; zeroWidth(anchored = true) }
                '$' -> { i++; zeroWidth() }
                '.' -> { i++; ranges(intArrayOf(0x0A, 0x0A, 0x0D, 0x0D, 0x2028, 0x2029), negated = true) }
                '[' -> { val (negated, set) = parseClass(); ranges(set, negated) }
                '(' -> group()
                '\\' -> escape()
                else -> { i++; units(c.code) }
            }
        }

        private fun group(): Info {
            i++ // (
            var lookaround = false
            if (s.startsWith("?:", i)) {
                i += 2
            } else if (s.startsWith("?=", i) || s.startsWith("?!", i)) {
                i += 2
                lookaround = true
            } else if (s.startsWith("?<=", i) || s.startsWith("?<!", i)) {
                i += 3
                lookaround = true
            } else if (s.startsWith("?<", i)) {
                i = s.indexOf('>', i) + 1
                if (i == 0) return Info(anything = true).also { i = s.length }
            }
            val inner = alternation()
            if (i < s.length && s[i] == ')') i++
            return if (lookaround) zeroWidth() else inner
        }

        private fun escape(): Info {
            if (i + 1 >= s.length) return Info(anything = true).also { i = s.length }
            val d = s[i + 1]
            i += 2
            return when (d) {
                'd' -> ranges(DIGIT, negated = false)
                'D' -> ranges(DIGIT, negated = true)
                'w' -> ranges(WORD, negated = false)
                'W' -> ranges(WORD, negated = true)
                's' -> ranges(SPACE, negated = false)
                'S' -> ranges(SPACE, negated = true)
                'b', 'B' -> zeroWidth()
                in '1'..'9', 'k' -> Info(anything = true, nullable = true).also {
                    while (i < s.length && s[i].isDigit()) i++ // a backreference can match anything
                }
                else -> units(characterEscape(d))
            }
        }
    }

    /** A set of UTF-16 units, kept as sorted, merged inclusive ranges. */
    private class UnitSet {
        private val pairs = ArrayList<IntArray>()

        fun add(from: Int, to: Int) {
            pairs.add(intArrayOf(from, to))
        }

        fun ranges(): IntArray {
            if (pairs.isEmpty()) return IntArray(0)
            val sorted = pairs.sortedBy { it[0] }
            val merged = ArrayList<IntArray>()
            for (p in sorted) {
                val last = merged.lastOrNull()
                if (last != null && p[0] <= last[1] + 1) last[1] = maxOf(last[1], p[1]) else merged.add(intArrayOf(p[0], p[1]))
            }
            val result = IntArray(merged.size * 2)
            merged.forEachIndexed { k, p ->
                result[2 * k] = p[0]
                result[2 * k + 1] = p[1]
            }
            return result
        }
    }

    private fun complementOf(ranges: IntArray): IntArray {
        val result = ArrayList<Int>()
        var next = 0
        for (k in ranges.indices step 2) {
            if (ranges[k] > next) {
                result.add(next)
                result.add(ranges[k] - 1)
            }
            next = ranges[k + 1] + 1
        }
        if (next <= 0xFFFF) {
            result.add(next)
            result.add(0xFFFF)
        }
        return result.toIntArray()
    }

    private fun rangesSource(ranges: IntArray): String {
        val sb = StringBuilder()
        for (k in ranges.indices step 2) {
            appendUnit(sb, ranges[k])
            if (ranges[k + 1] != ranges[k]) {
                sb.append('-')
                appendUnit(sb, ranges[k + 1])
            }
        }
        return sb.toString()
    }

    /** One UTF-16 unit as a Java pattern literal: letters and digits as they are, anything else as `\uXXXX`. */
    private fun appendUnit(sb: StringBuilder, code: Int) {
        val c = code.toChar()
        if (c.isAsciiLetter() || c.isAsciiDigit()) sb.append(c) else sb.append("\\u").append(String.format("%04X", code))
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isAsciiHexDigit() = isAsciiDigit() || this in 'a'..'f' || this in 'A'..'F'
}
