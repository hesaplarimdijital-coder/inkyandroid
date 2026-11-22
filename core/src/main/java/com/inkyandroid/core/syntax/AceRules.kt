package com.inkyandroid.core.syntax

/**
 * A highlight rule as Ace keeps it: a mutable object that Ace's `normalizeRules` and `Tokenizer`
 * constructor rewrite in place, shared between every state that includes it. The port keeps that shape
 * on purpose, because the state names Ace invents and the way shared rules are rewritten depend on it.
 *
 * Only the parts of Ace's rule format that upstream's ink rules use, or that Ace's algorithm itself
 * produces, are here: `regex` (JavaScript source), `token` (a type or one type per group), `next`
 * (a state name, `"pop"`, or an inline state), `push` (an inline state), `include`, `defaultToken`,
 * `caseInsensitive`, `merge`, `stateName`. (Ace also accepts token functions, keyword maps, `start`/`end`
 * shorthands, nested arrays and string entries; upstream's rules use none of them.)
 */
internal class AceRule(
    var regex: String? = null,
    /** A token type (`String`), one type per capturing group (`List<String>`), or null. */
    var token: Any? = null,
    /** A state name (`String`), an inline state (`MutableList<AceRule>`) or, once normalized, an [AceNext]. */
    var next: Any? = null,
    /** An inline state to push (`MutableList<AceRule>`) until normalized. */
    var push: Any? = null,
    var include: String? = null,
    var defaultToken: String? = null,
    var caseInsensitive: Boolean = false,
    var merge: Boolean? = null,
    var stateName: String? = null,
) {
    /** For a pushing rule: the state it pushes (Ace's `rule.nextState`). */
    var nextState: String? = null

    /** One type per capturing group, set by the tokenizer for rules whose token is a list (Ace's `tokenArray`). */
    var tokenArray: List<String>? = null

    /** The rule's own regex anchored at both ends, used to split a match into its groups (Ace's `splitRegex`). */
    var splitRegex: java.util.regex.Pattern? = null
}

/** Ace's two state-changing functions, which `normalizeRules` puts into `rule.next`. */
internal enum class AceNext {
    /** Ace's `pushState`: push the rule's `nextState` (unless in "start" with an empty stack) and go there. */
    Push,

    /** Ace's `popState`: drop the current state from the stack and return to the one below it. */
    Pop,
}

/** Ace's rule set: state name to rules, in JavaScript object key order (insertion order for these names). */
internal typealias AceRuleSet = LinkedHashMap<String, MutableList<AceRule>>

/**
 * Ace's `TextHighlightRules.normalizeRules` (v1.2.6): names the inline states of `next` and `push`
 * (after the rule's token, numbered on a clash), turns `"pop"` and `push` into [AceNext], and splices
 * included states in place of `include` rules. An include of a state that does not exist stays behind as
 * a rule without a regex, which the tokenizer skips (upstream's `#endOfSection`, and `#customInstruction`
 * until an instruction prefix is set and the rules are normalized again).
 */
internal fun normalizeRules(rules: AceRuleSet) {
    var id = 0

    fun processState(key: String) {
        val state = rules.getValue(key)
        var i = 0
        while (i < state.size) {
            val rule = state[i]

            val next = rule.next ?: rule.push
            if (next is MutableList<*>) {
                var stateName = rule.stateName
                if (stateName.isNullOrEmpty()) {
                    stateName = when (val token = rule.token) {
                        is String -> token
                        is List<*> -> (token.firstOrNull() as String?).orEmpty()
                        else -> ""
                    }
                    if (rules.containsKey(stateName)) stateName += id++
                }
                @Suppress("UNCHECKED_CAST")
                rules[stateName] = next as MutableList<AceRule>
                rule.next = stateName
                processState(stateName)
            } else if (next == "pop") {
                rule.next = AceNext.Pop
            }

            if (rule.push != null) {
                rule.nextState = (rule.next ?: rule.push) as String
                rule.next = AceNext.Push
                rule.push = null
            }

            val includeName = rule.include.orEmpty()
            val toInsert = if (includeName.isNotEmpty()) rules[includeName] else null
            if (toInsert != null) {
                val copy = ArrayList(toInsert)
                state.removeAt(i)
                state.addAll(i, copy)
                i--
            }
            i++
        }
    }

    for (key in rules.keys.toList()) processState(key)
}
