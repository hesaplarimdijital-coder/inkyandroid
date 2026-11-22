package com.inkyandroid.core.project

/** A place the user has been: a file and a zero-based cursor position in it. */
data class NavStep(val path: String, val row: Int, val column: Int = 0)

/**
 * The back / forward history of places in a project: upstream's `navHistory.js` as plain logic.
 *
 * A step is added when the user arrives somewhere on purpose (a file switch, a click, a jump to a
 * definition or an issue, a "go to anything" choice); moving back and forward walks the list
 * without adding to it. Not thread-safe: use it from one thread.
 */
class NavigationHistory {

    private val steps = ArrayList<NavStep>()
    private var index = -1

    /** The step the history stands on, or null while it is empty. */
    val current: NavStep? get() = steps.getOrNull(index)

    val canGoBack: Boolean get() = index > 0

    val canGoForward: Boolean get() = index < steps.size - 1

    /**
     * Records that the user is now at [step]. Nothing happens when the current step is in the same
     * file on the same row (upstream's rule against duplicates; the column does not count).
     * Otherwise the steps after the current one are dropped (history is rewritten from here) and
     * [step] becomes the current, last step. Returns whether a step was added.
     */
    fun add(step: NavStep): Boolean {
        val existing = current
        if (existing != null && existing.path == step.path && existing.row == step.row) return false
        while (steps.size > index + 1) steps.removeAt(steps.size - 1)
        steps.add(step)
        index = steps.size - 1
        if (steps.size > MAX_STEPS) {
            steps.removeAt(0)
            index--
        }
        return true
    }

    /** Steps back and returns where to go, or null at the oldest step (upstream clamps and does nothing). */
    fun back(): NavStep? = go(-1)

    /** Steps forward and returns where to go, or null at the newest step. */
    fun forward(): NavStep? = go(+1)

    /** Forgets everything: a new project starts a new history. */
    fun reset() {
        steps.clear()
        index = -1
    }

    /** A file was renamed: its steps follow it. */
    fun renamed(from: String, to: String) {
        for (i in steps.indices) if (steps[i].path == from) steps[i] = steps[i].copy(path = to)
    }

    /**
     * A file left the project: its steps are dropped, so back and forward never lead to a file that
     * is not there (upstream keeps them and silently does nothing when one is reached). The current
     * step moves to the nearest older step that remains.
     */
    fun removed(path: String) {
        if (steps.none { it.path == path }) return
        val keptUpToCurrent = steps.subList(0, index + 1).count { it.path != path }
        steps.removeAll { it.path == path }
        index = if (steps.isEmpty()) -1 else (keptUpToCurrent - 1).coerceAtLeast(0)
    }

    private fun go(by: Int): NavStep? {
        if (steps.isEmpty()) return null
        val target = (index + by).coerceIn(0, steps.size - 1)
        if (target == index) return null
        index = target
        return steps[index]
    }

    companion object {
        /**
         * Upstream has no limit. A session here can last for weeks, so the oldest steps are dropped
         * beyond this many; nobody steps back a thousand times.
         */
        const val MAX_STEPS = 1000
    }
}
