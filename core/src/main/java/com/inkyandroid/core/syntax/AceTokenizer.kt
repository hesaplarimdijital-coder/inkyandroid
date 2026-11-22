package com.inkyandroid.core.syntax

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Ace's `Tokenizer` (v1.2.6, `ace/tokenizer.js`), ported line for line over normalized [AceRuleSet]s.
 *
 * Per state, every rule's regex becomes one alternative of a single regex `(r1)|(r2)|…|($)`; the earliest
 * match in the line wins, and among matches at the same position the first rule. (The port searches each
 * alternative on its own and remembers where it next matches, which gives the same match as the ordered
 * alternation, many times faster with Java's regex engine; see [Search].) Text between matches
 * gets the state's default token. A rule's token is one type, or one type per capturing group (the match
 * is then split by the rule's own regex, anchored, and empty groups are dropped). Consecutive tokens of
 * the same type are merged. `next` switches state; [AceNext.Push] and [AceNext.Pop] work on the state
 * stack. After [MAX_TOKEN_COUNT] matches in a line the rest becomes `overflow` tokens and the state is
 * reset, as Ace does to stop runaway rules.
 */
internal class AceTokenizer(rules: AceRuleSet) {

    private class CompiledState(
        val name: String,
        val rules: List<AceRule>,
        /** The alternatives of Ace's combined regex, in order; the last is Ace's final `$`. */
        val alternatives: Array<Pattern>,
        /** Where each alternative can start, so it is only tried there. */
        val alternativeStarts: Array<JsRegex.StartInfo>,
        /** The rule index of each alternative (-1 for the final `$`, or for Ace's stand-in of a state without regexes). */
        val alternativeRule: IntArray,
        val defaultToken: String,
        /** Position of this state in [stateList]. */
        val index: Int,
    )

    private val states = LinkedHashMap<String, CompiledState>()
    private val stateList: List<CompiledState>

    /** Problems Ace would report with `reportError` (a group count that does not match the token list). */
    val reportedErrors = ArrayList<String>()

    /** Each state's combined regex, as Ace builds it (JavaScript source), for the golden comparison. */
    val combinedSources = LinkedHashMap<String, String>()

    init {
        val validated = HashSet<String>()
        val compiled = HashMap<Pair<String, Boolean>, Pattern>()
        for ((key, state) in rules) {
            val ruleRegExps = ArrayList<String>()
            var matchTotal = 0
            val mapping = HashMap<Int, Int>()
            var defaultToken = "text"
            var ignoreCase = false
            val splitterRules = ArrayList<AceRule>()
            val alternativeSources = ArrayList<String>()
            val alternativeRules = ArrayList<Int>()

            for (i in state.indices) {
                val rule = state[i]
                if (!rule.defaultToken.isNullOrEmpty()) defaultToken = rule.defaultToken!!
                if (rule.caseInsensitive) ignoreCase = true
                val regex = rule.regex ?: continue

                var adjustedRegex = regex
                // Ace: new RegExp("(?:(" + regex + ")|(.))").exec("a").length - 2
                var matchCount = JsRegex.capturingGroupCount(regex) + 1
                // Ace counts the groups by running the regex, which rejects an invalid one
                if (validated.add(regex)) JsRegex.compile(regex)
                val token = rule.token
                if (token is List<*>) {
                    @Suppress("UNCHECKED_CAST")
                    token as List<String>
                    if (token.size == 1 || matchCount == 1) {
                        rule.token = token[0]
                    } else if (matchCount - 1 != token.size) {
                        reportedErrors.add("number of classes and regexp groups doesn't match: /$regex/ has ${matchCount - 1} groups")
                        rule.token = token[0]
                    } else {
                        rule.tokenArray = token
                        rule.token = null
                    }
                }

                if (matchCount > 1) {
                    if (BACKREFERENCE.containsMatchIn(regex)) {
                        val offset = matchTotal
                        adjustedRegex = BACKREFERENCE_NUMBER.replace(regex) { m ->
                            "\\" + (m.groupValues[1].toInt() + offset + 1)
                        }
                    } else {
                        matchCount = 1
                        adjustedRegex = removeCapturingGroups(regex)
                    }
                    if (rule.splitRegex == null && rule.token !is String) splitterRules.add(rule)
                }

                mapping[matchTotal] = i
                matchTotal += matchCount
                ruleRegExps.add(adjustedRegex)
                // On its own, an alternative with backreferences keeps its own group numbers
                alternativeSources.add(if (matchCount > 1) regex else adjustedRegex)
                alternativeRules.add(i)
            }

            if (ruleRegExps.isEmpty()) {
                mapping[0] = 0
                ruleRegExps.add("$")
                alternativeSources.add("$")
                alternativeRules.add(if (state.isEmpty()) -1 else 0)
            }
            alternativeSources.add("$")
            alternativeRules.add(-1)

            for (rule in splitterRules) {
                rule.splitSource = splitterSource(rule.regex!!)
                rule.splitRegex = JsRegex.compile(rule.splitSource!!, ignoreCase)
            }

            val combined = "(" + ruleRegExps.joinToString(")|(") + ")|($)"
            JsRegex.compile(combined, ignoreCase) // as Ace's `new RegExp`, which rejects what does not compile together
            val alternatives = Array(alternativeSources.size) { k ->
                compiled.getOrPut(alternativeSources[k] to ignoreCase) { JsRegex.compile(alternativeSources[k], ignoreCase) }
            }
            val starts = Array(alternativeSources.size) { k ->
                if (ignoreCase) JsRegex.StartInfo(onlyAtInputStart = false, firstUnits = null) else JsRegex.startInfo(alternativeSources[k])
            }
            states[key] = CompiledState(key, state, alternatives, starts, alternativeRules.toIntArray(), defaultToken, states.size)
            combinedSources[key] = combined
        }
        stateList = states.values.toList()
    }

    /** The normalized rules this tokenizer runs, for the golden comparison. */
    val ruleSet: Map<String, List<AceRule>> get() = states.mapValues { it.value.rules }

    /**
     * Finds the next match of a state's combined regex the way the ordered alternation `(r1)|…|(rn)` does:
     * the earliest position, and there the first alternative that matches. Each alternative's next match
     * is searched on its own and remembered; a remembered match stays valid as long as the search starts
     * at or before it (and after where it was searched from), so most alternatives are searched once per
     * line instead of being tried at every character. And an alternative is only tried where it can start
     * ([JsRegex.StartInfo]): at the line's start if it begins with `^`, else at characters that can begin
     * it (with transparent, non-anchoring bounds, so lookbehind and `^` see the whole line). One per
     * thread and state, reused across lines.
     */
    private class Search(val state: CompiledState) {
        private val count = state.alternatives.size
        private val matchers = arrayOfNulls<Matcher>(count)
        private val searchedFrom = IntArray(count)
        private val start = IntArray(count)
        private val end = IntArray(count)
        private var line: CharSequence? = null

        /** Matchers are pointed at a new line only when used ([matcherLine] behind [lineGeneration]). */
        private var lineGeneration = 0
        private val matcherLine = IntArray(count) { -1 }

        private fun matcher(k: Int): Matcher {
            val text = line!!
            val existing = matchers[k]
            if (existing != null && matcherLine[k] == lineGeneration) return existing
            matcherLine[k] = lineGeneration
            return existing?.reset(text)
                ?: state.alternatives[k].matcher(text).useTransparentBounds(true).useAnchoringBounds(false).also { matchers[k] = it }
        }

        /** The alternative that matched last, and the match's range. */
        var alternative = -1
        var matchStart = 0
        var matchEnd = 0

        fun reset(newLine: CharSequence) {
            line = newLine
            for (k in 0 until count) searchedFrom[k] = Int.MAX_VALUE // nothing remembered
            lineGeneration++
        }

        private fun searchAlternative(k: Int, from: Int) {
            val text = line!!
            val length = text.length
            val startInfo = state.alternativeStarts[k]
            val firstUnits = startInfo.firstUnits
            start[k] = -1
            val required = startInfo.requiredText
            if (required != null && !containsFrom(text, required, from)) return
            val matcher = matcher(k)
            when {
                startInfo.onlyAtInputStart -> if (from == 0) {
                    matcher.region(0, length)
                    if (matcher.lookingAt()) {
                        start[k] = 0
                        end[k] = matcher.end()
                    }
                }
                firstUnits != null -> {
                    var p = from
                    while (p < length) {
                        if (firstUnits.get(text[p].code)) {
                            matcher.region(p, length)
                            if (matcher.lookingAt()) {
                                start[k] = p
                                end[k] = matcher.end()
                                return
                            }
                        }
                        p++
                    }
                }
                matcher.find(from) -> {
                    start[k] = matcher.start()
                    end[k] = matcher.end()
                }
            }
        }

        private fun containsFrom(text: CharSequence, required: String, from: Int): Boolean {
            if (text is String) return text.indexOf(required, from) >= 0
            val first = required[0]
            val last = text.length - required.length
            var p = from
            while (p <= last) {
                if (text[p] == first && text.regionMatches(p, required, 0, required.length)) return true
                p++
            }
            return false
        }

        /** Searches from [from]; false only if no alternative matches (the final `$` always does). */
        fun find(from: Int): Boolean {
            var best = -1
            var bestStart = Int.MAX_VALUE
            for (k in 0 until count) {
                if (!(searchedFrom[k] <= from && (start[k] < 0 || start[k] >= from))) {
                    searchedFrom[k] = from
                    searchAlternative(k, from)
                }
                if (start[k] in 0 until bestStart) {
                    best = k
                    bestStart = start[k]
                    if (bestStart == from) break // nothing can start earlier, and later alternatives lose ties
                }
            }
            if (best < 0) return false
            alternative = best
            matchStart = start[best]
            matchEnd = end[best]
            return true
        }
    }

    private val searches = ThreadLocal.withInitial { arrayOfNulls<Search>(stateList.size) }

    private fun searchFor(state: CompiledState, line: CharSequence): Search {
        val cache = searches.get()
        val search = cache[state.index] ?: Search(state).also { cache[state.index] = it }
        search.reset(line)
        return search
    }

    private fun splitMatcher(rule: AceRule, value: String): Matcher {
        val cache = splitMatchers.get()
        val matcher = cache[rule]
        return if (matcher == null) rule.splitRegex!!.matcher(value).also { cache[rule] = it } else matcher.reset(value)
    }

    private val splitMatchers = ThreadLocal.withInitial { java.util.IdentityHashMap<AceRule, Matcher>() }

    /** Ace's `getLineTokens(line, startState)`. */
    fun getLineTokens(line: CharSequence, startState: InkLineState): InkLineTokens {
        // Ace: an array state is the stack; "#tmp" heads a stack whose current state is the next name
        var stack: InkLineState?
        var currentName: String
        if (!startState.isPlain) {
            if (startState.head == TMP) {
                val afterTmp = startState.pop()!!
                currentName = afterTmp.head
                stack = afterTmp.pop()
            } else {
                currentName = startState.head
                stack = startState
            }
        } else {
            currentName = startState.head
            stack = null
        }

        var state = states[currentName] ?: states.getValue(START).also { currentName = START }
        var search = searchFor(state, line)

        val tokens = ArrayList<InkToken>()
        var pendingType: String? = null
        var pendingLength = 0
        var position = 0 // where the next token starts: the sum of the previous tokens' lengths, as in Ace

        fun flushPending() {
            val type = pendingType
            // Ace keeps the pending token only if its type is truthy: an empty type's text is dropped
            if (!type.isNullOrEmpty()) {
                tokens.add(InkToken(type, position, pendingLength))
                position += pendingLength
            }
            pendingType = null
            pendingLength = 0
        }

        var lastIndex = 0
        var searchFrom = 0
        var matchAttempts = 0
        val length = line.length

        while (search.find(searchFrom)) {
            var type: String? = state.defaultToken
            var rule: AceRule? = null
            val matchStart = search.matchStart
            val index = search.matchEnd
            searchFrom = index
            val valueLength = index - matchStart

            if (matchStart > lastIndex) {
                val skipped = matchStart - lastIndex
                if (pendingType == type) {
                    pendingLength += skipped
                } else {
                    flushPending()
                    pendingType = type
                    pendingLength = skipped
                }
            }

            var arrayTokens: List<Pair<String, Int>>? = null
            val ruleIndex = state.alternativeRule[search.alternative]
            // Ace looks for the rule of the first participating group; its final `$` has none
            if (ruleIndex >= 0) {
                rule = state.rules[ruleIndex]

                if (rule.tokenArray != null) {
                    // Ace's $arrayTokens
                    if (valueLength == 0) {
                        arrayTokens = emptyList()
                        type = null
                    } else {
                        val value = line.subSequence(matchStart, index).toString()
                        val split = splitMatcher(rule, value)
                        if (!split.find()) {
                            type = "text"
                        } else {
                            val list = ArrayList<Pair<String, Int>>(rule.tokenArray!!.size)
                            for ((k, groupType) in rule.tokenArray!!.withIndex()) {
                                val groupStart = split.start(k + 1)
                                if (groupStart >= 0 && split.end(k + 1) > groupStart) {
                                    list.add(groupType to (split.end(k + 1) - groupStart))
                                }
                            }
                            arrayTokens = list
                            type = null
                        }
                    }
                } else {
                    type = rule.token as String?
                }

                val next = rule.next
                if (next != null) {
                    currentName = when (next) {
                        is String -> next
                        AceNext.Push -> {
                            val pushed = rule.nextState!!
                            if (currentName != START || stack != null) {
                                stack = InkLineState.push(pushed, InkLineState.push(currentName, stack))
                            }
                            pushed
                        }
                        AceNext.Pop -> {
                            stack = stack?.pop()
                            val below = stack?.head
                            stack = stack?.pop()
                            if (below.isNullOrEmpty()) START else below
                        }
                        else -> error("Unexpected next: $next")
                    }
                    val nextState = states[currentName]
                    if (nextState == null) {
                        reportedErrors.add("state doesn't exist: $currentName")
                        currentName = START
                    }
                    state = nextState ?: states.getValue(START)
                    lastIndex = index
                    search = searchFor(state, line)
                }
            }

            if (valueLength > 0) {
                if (arrayTokens == null && type != null) {
                    if ((rule == null || rule.merge != false) && pendingType == type) {
                        pendingLength += valueLength
                    } else {
                        flushPending()
                        pendingType = type
                        pendingLength = valueLength
                    }
                } else if (arrayTokens != null) {
                    flushPending()
                    for ((groupType, groupLength) in arrayTokens) {
                        tokens.add(InkToken(groupType, position, groupLength))
                        position += groupLength
                    }
                }
            }

            if (lastIndex == length) break

            lastIndex = index

            if (matchAttempts++ > MAX_TOKEN_COUNT) {
                while (lastIndex < length) {
                    flushPending()
                    val chunkEnd = minOf(length, lastIndex + MAX_TOKEN_COUNT)
                    pendingType = OVERFLOW
                    pendingLength = chunkEnd - lastIndex
                    lastIndex += MAX_TOKEN_COUNT
                }
                currentName = START
                stack = null
                break
            }
        }

        flushPending()

        val finalStack = stack
        val endState = when {
            finalStack == null -> InkLineState.plain(currentName)
            finalStack.depth > 1 && finalStack.head != currentName ->
                InkLineState.push(TMP, InkLineState.push(currentName, finalStack))
            else -> finalStack
        }
        return InkLineTokens(tokens, endState)
    }

    companion object {
        const val START = "start"
        const val TMP = "#tmp"
        const val OVERFLOW = "overflow"

        /** Ace's `MAX_TOKEN_COUNT`. */
        const val MAX_TOKEN_COUNT = 2000

        private val BACKREFERENCE = Regex("""\\\d""")
        private val BACKREFERENCE_NUMBER = Regex("""\\([0-9]+)""")
        private val CAPTURING_GROUP = Regex("""\[(?:\\.|[^\]])*?\]|\\.|\(\?[:=!]|(\()""")
        private val SPLITTER_PARTS = Regex("""(\\.)|(\((?:\?[=!])?)|(\))|([\[\]])""")

        /** Ace's `removeCapturingGroups`: every `(` that opens a capturing group becomes `(?:`. */
        fun removeCapturingGroups(source: String): String =
            CAPTURING_GROUP.replace(source) { m -> if (m.groups[1] != null) "(?:" else m.value }

        /**
         * Ace's `createSplitterRegexp` (the source; Ace compiles it with the state's flags): the rule's regex anchored at both ends, after dropping a final
         * lookahead (which looked past the match and cannot succeed on the match alone).
         */
        fun splitterSource(source: String): String {
            var src = source
            if (src.indexOf("(?=") != -1) {
                var stack = 0
                var inCharClass = false
                var lastCaptureStack: Int? = null
                var lastCaptureStart: Int? = null
                var lastCaptureEnd: Int? = null
                for (m in SPLITTER_PARTS.findAll(src)) {
                    val parenOpen = m.groups[2]?.value
                    val parenClose = m.groups[3]
                    val square = m.groups[4]?.value
                    val index = m.range.first
                    if (inCharClass) {
                        inCharClass = square != "]"
                    } else if (square != null) {
                        inCharClass = true
                    } else if (parenClose != null) {
                        if (stack == lastCaptureStack) {
                            lastCaptureEnd = index + 1
                            lastCaptureStack = -1
                        }
                        stack--
                    } else if (parenOpen != null) {
                        stack++
                        if (parenOpen.length != 1) {
                            lastCaptureStack = stack
                            lastCaptureStart = index
                        }
                    }
                }
                if (lastCaptureEnd != null && Regex("""^\)*$""").matches(src.substring(lastCaptureEnd))) {
                    src = src.substring(0, lastCaptureStart!!) + src.substring(lastCaptureEnd)
                }
            }
            if (!src.startsWith("^")) src = "^$src"
            if (!src.endsWith("$")) src += "$"
            return src
        }
    }
}
