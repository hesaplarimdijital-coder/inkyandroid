package com.inkyandroid.core.syntax

import java.util.concurrent.ConcurrentHashMap

/**
 * The tokenizer state between two lines: Ace's state value, a state name or a stack of state names.
 *
 * Ace returns a plain state name (`"start"`, `"punctuation.definition.comment.json"`, …) when its state
 * stack is empty, and the stack itself (innermost first, sometimes headed by Ace's `"#tmp"` marker)
 * otherwise. [names] is that value as a list: one name for the plain form, the stack for the other.
 * (Ace's stack only ever grows and shrinks by two names at a time, so a one-name stack cannot occur.)
 *
 * States are interned: two equal states are the same object, so `==` and [hashCode] are as cheap as an
 * identity check, and each state has a small, stable [id] for the lifetime of the process, through which
 * it can travel where only an `Int` fits ([fromId]). Interning is thread-safe. Upstream's rules can make
 * the stack grow by four names on every line with a bracketed choice (see [InkTokenizer]); the stacks
 * share their tails, so that costs one small node per name, not a copy of the stack.
 */
class InkLineState private constructor(
    /** The outermost-but-one rest of the stack, or null at the bottom. */
    private val tail: InkLineState?,
    /** The first (innermost) name. */
    internal val head: String,
    /** Whether this is the plain one-name form rather than a stack. */
    internal val isPlain: Boolean,
    /** Small, stable, process-wide number of this state; see [fromId]. */
    val id: Int,
) {
    /** Number of names in [names]. */
    internal val depth: Int = (tail?.depth ?: 0) + 1

    /** Ace's state value as a list of state names, innermost first. */
    val names: List<String> by lazy {
        val result = ArrayList<String>(depth)
        var node: InkLineState? = this
        while (node != null) {
            result.add(node.head)
            node = node.tail
        }
        result
    }

    /** The stack below the first name (a stack node, or null when this is the last name). */
    internal fun pop(): InkLineState? = tail

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = id

    override fun toString(): String = if (isPlain) head else names.joinToString(prefix = "[", postfix = "]")

    private data class Key(val head: String, val tailId: Int, val isPlain: Boolean)

    companion object {
        private val byKey = ConcurrentHashMap<Key, InkLineState>()
        private val byId = ArrayList<InkLineState>()
        private val lock = Any()

        private fun intern(head: String, tail: InkLineState?, isPlain: Boolean): InkLineState {
            val key = Key(head, tail?.id ?: -1, isPlain)
            byKey[key]?.let { return it }
            synchronized(lock) {
                byKey[key]?.let { return it }
                val state = InkLineState(tail, head, isPlain, byId.size)
                byId.add(state)
                byKey[key] = state
                return state
            }
        }

        /** Ace's plain state value: just the state [name] (the state stack is empty). */
        fun plain(name: String): InkLineState = intern(name, null, isPlain = true)

        /** Ace's stack state value, innermost name first; [names] must not be empty. */
        fun stack(names: List<String>): InkLineState {
            require(names.isNotEmpty()) { "A state stack has at least one name" }
            var node: InkLineState? = null
            for (i in names.indices.reversed()) node = intern(names[i], node, isPlain = false)
            return node!!
        }

        /** [name] pushed on top of the stack [below] (null: an empty stack). */
        internal fun push(name: String, below: InkLineState?): InkLineState = intern(name, below, isPlain = false)

        /** The state at the start of a file: Ace's `"start"`. */
        val initial: InkLineState = plain("start")

        /** The state with the given [id], or null if no state has it (ids come from [InkLineState.id]). */
        fun fromId(id: Int): InkLineState? = synchronized(lock) { byId.getOrNull(id) }
    }
}
