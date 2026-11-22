package com.inkyandroid.core.syntax

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Ace's `Tokenizer` (v1.2.6, `ace/tokenizer.js`), ported line for line over normalized [AceRuleSet]s.
 *
 * Per state, every rule's regex becomes one alternative of a single regex `(r1)|(r2)|…|($)`; the earliest
 * match in the line wins, and among matches at the same position the first rule. Text between matches
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
        val pattern: Pattern,
        /** For each group number - 1 of [pattern] that starts a rule's alternative, the rule's index. */
        val ruleAtGroup: IntArray,
        val defaultToken: String,
    )

    private val states = LinkedHashMap<String, CompiledState>()
    private val stateList: List<CompiledState>

    /** Problems Ace would report with `reportError` (a group count that does not match the token list). */
    val reportedErrors = ArrayList<String>()

    /** Each state's combined regex, as Ace builds it (JavaScript source), for the golden comparison. */
    val combinedSources = LinkedHashMap<String, String>()

    init {
        val validated = HashSet<String>()
        for ((key, state) in rules) {
            val ruleRegExps = ArrayList<String>()
            var matchTotal = 0
            val mapping = HashMap<Int, Int>()
            var defaultToken = "text"
            var ignoreCase = false
            val splitterRules = ArrayList<AceRule>()

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
            }

            if (ruleRegExps.isEmpty()) {
                mapping[0] = 0
                ruleRegExps.add("$")
            }

            for (rule in splitterRules) rule.splitRegex = createSplitterRegexp(rule.regex!!, ignoreCase)

            val combined = "(" + ruleRegExps.joinToString(")|(") + ")|($)"
            val pattern = JsRegex.compile(combined, ignoreCase)
            val groupCount = pattern.matcher("").groupCount()
            val ruleAtGroup = IntArray(groupCount) { -1 }
            for ((group, ruleIndex) in mapping) if (group < groupCount) ruleAtGroup[group] = ruleIndex
            states[key] = CompiledState(key, state, pattern, ruleAtGroup, defaultToken)
            combinedSources[key] = combined
        }
        stateList = states.values.toList()
    }

    /** The normalized rules this tokenizer runs, for the golden comparison. */
    val ruleSet: Map<String, List<AceRule>> get() = states.mapValues { it.value.rules }

    private val matchers = ThreadLocal.withInitial { arrayOfNulls<Matcher>(stateList.size) }
    private val stateIndex = HashMap<String, Int>().also { map -> stateList.forEachIndexed { i, s -> map[s.name] = i } }

    private fun matcherFor(state: CompiledState, line: CharSequence): Matcher {
        val cache = matchers.get()
        val index = stateIndex.getValue(state.name)
        val matcher = cache[index]
        return if (matcher == null) state.pattern.matcher(line).also { cache[index] = it } else matcher.reset(line)
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
        var matcher = matcherFor(state, line)

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

        while (matcher.find(searchFrom)) {
            var type: String? = state.defaultToken
            var rule: AceRule? = null
            val matchStart = matcher.start()
            val index = matcher.end()
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
            val groupCount = matcher.groupCount()
            for (g in 0 until groupCount - 1) {
                if (matcher.start(g + 1) < 0) continue
                val ruleIndex = state.ruleAtGroup[g]
                // an inner group of a rule with backreferences (never the first participating one), or the
                // "$" Ace adds to a state without regexes
                if (ruleIndex < 0 || ruleIndex >= state.rules.size) continue
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
                    matcher = matcherFor(state, line)
                }
                break
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
         * Ace's `createSplitterRegexp`: the rule's regex anchored at both ends, after dropping a final
         * lookahead (which looked past the match and cannot succeed on the match alone).
         */
        fun createSplitterRegexp(source: String, ignoreCase: Boolean): Pattern {
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
            return JsRegex.compile(src, ignoreCase)
        }
    }
}
