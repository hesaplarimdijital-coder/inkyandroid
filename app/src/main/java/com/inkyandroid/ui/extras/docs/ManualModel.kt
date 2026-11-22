package com.inkyandroid.ui.extras.docs

/** How a run of inline text is set. */
enum class InlineStyle { Code, Emphasis, Strong }

/** [style] over `start until end` of a [RichText]. */
data class InlineSpan(val start: Int, val end: Int, val style: InlineStyle)

/** A link over `start until end`: [target] is `#anchor` inside the manual, or an address outside it. */
data class LinkSpan(val start: Int, val end: Int, val target: String) {
    /** The anchor this link jumps to inside the manual, or null for an outside address. */
    val anchor: String? get() = target.takeIf { it.startsWith("#") }?.substring(1)
}

/** Text of one block with its inline styling and links, independent of how it is drawn. */
data class RichText(val text: String, val styles: List<InlineSpan> = emptyList(), val links: List<LinkSpan> = emptyList())

/**
 * One block of the manual, as the documentation screen draws it: one item of its lazy list. Lists and
 * block quotes are flattened into the blocks they contain, each block knowing how deep it sits
 * ([listDepth], [quoteDepth]), so a long list scrolls and searches like any other text.
 */
sealed interface ManualBlock {
    /** List nesting: 0 outside lists, 1 inside a top-level list item, and so on. */
    val listDepth: Int

    /** Block quote nesting. */
    val quoteDepth: Int

    /** The text find looks in: what the reader sees, without markup. */
    val plainText: String

    /** A section title. [level] is the number of `#`; [anchor] is the GitHub-style id links use. */
    data class Heading(val level: Int, val text: RichText, val anchor: String) : ManualBlock {
        override val listDepth: Int get() = 0
        override val quoteDepth: Int get() = 0
        override val plainText: String get() = text.text
    }

    /**
     * A paragraph. [marker] is set on the first paragraph of a list item: "•" (a bullet, whatever
     * character the source used) or the item's number with its delimiter ("3.").
     */
    data class Paragraph(
        val text: RichText,
        override val listDepth: Int = 0,
        override val quoteDepth: Int = 0,
        val marker: String? = null,
    ) : ManualBlock {
        override val plainText: String get() = text.text
    }

    /** A code example, indented or fenced; every example in the manual is ink. No final line break. */
    data class Code(val code: String, override val listDepth: Int = 0, override val quoteDepth: Int = 0, val marker: String? = null) : ManualBlock {
        override val plainText: String get() = code
    }

    /** A thematic break. */
    data class Rule(override val listDepth: Int = 0, override val quoteDepth: Int = 0) : ManualBlock {
        override val plainText: String get() = ""
    }
}

/** A heading as the table of contents lists it: [block] is its index in [Manual.blocks]. */
data class ManualSection(val block: Int, val level: Int, val title: String, val anchor: String)

/** One place [Manual.find] found the query: `start until end` of the block's [ManualBlock.plainText]. */
data class ManualMatch(val block: Int, val start: Int, val end: Int)

/**
 * The manual as blocks, with what navigating it needs: the table of contents, anchors, the section a
 * block belongs to, and find. Immutable; build it once with [ManualParser.parse].
 */
class Manual(val blocks: List<ManualBlock>) {

    /** Every heading, in order. */
    val sections: List<ManualSection> = blocks.mapIndexedNotNull { index, block ->
        (block as? ManualBlock.Heading)?.let { ManualSection(index, it.level, it.text.text, it.anchor) }
    }

    /** The table of contents: the headings of the first [TOC_LEVELS] levels. */
    val contents: List<ManualSection> = sections.filter { it.level <= TOC_LEVELS }

    private val byAnchor: Map<String, Int> = sections.associate { it.anchor to it.block }

    /** The block a `#anchor` link (given with or without the `#`) jumps to, or null if there is none. */
    fun resolve(anchor: String): Int? = byAnchor[anchor.removePrefix("#")]

    /**
     * The entry of [contents] the reader is in when [block] is at the top of the screen: the last one
     * at or before it, or null before the first.
     */
    fun contentsEntryAt(block: Int): ManualSection? {
        // binary search for the last entry whose block is <= block
        var low = 0
        var high = contents.size - 1
        var found: ManualSection? = null
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (contents[mid].block <= block) {
                found = contents[mid]
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /**
     * Every occurrence of [query] in the manual, in reading order, ignoring case. Blank queries find
     * nothing. Matches do not span blocks.
     */
    fun find(query: String): List<ManualMatch> {
        if (query.isBlank()) return emptyList()
        val matches = ArrayList<ManualMatch>()
        blocks.forEachIndexed { index, block ->
            val text = block.plainText
            var from = 0
            while (true) {
                val at = text.indexOf(query, from, ignoreCase = true)
                if (at < 0) break
                matches += ManualMatch(index, at, at + query.length)
                from = at + query.length
            }
        }
        return matches
    }

    companion object {
        /** How many heading levels the table of contents shows: parts, chapters and sections. */
        const val TOC_LEVELS = 3
    }
}
