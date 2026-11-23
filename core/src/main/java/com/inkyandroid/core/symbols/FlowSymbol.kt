package com.inkyandroid.core.symbols

/** The kinds of named flow upstream indexes (`inkFileSymbols.js: flowTypes`), with their nesting level. */
enum class FlowType(val level: Int) {
    /** `=== name ===`, also `=== function name ===` (then [FlowSymbol.isFunction]). */
    Knot(1),

    /** `= name` */
    Stitch(2),

    /** The label of a choice: `* (name) …` */
    Choice(3),

    /** The label of a gather: `- (name) …` */
    Gather(3),
}

/**
 * A named piece of flow in one file: a knot, a stitch, or a choice or gather label.
 *
 * [row] and [column] are zero-based and point at the name. [parent] is the enclosing symbol, null at the
 * top level of the file. A symbol and its tree are immutable once [InkFileSymbols.parse] returns.
 */
class FlowSymbol internal constructor(
    val name: String,
    val flowType: FlowType,
    /** Whether the knot was declared `=== function name ===`. */
    val isFunction: Boolean,
    val row: Int,
    val column: Int,
    val parent: FlowSymbol?,
) {
    internal val childList = ArrayList<FlowSymbol>(0)
    internal val innerMap = JsObjectMap<FlowSymbol>()

    /**
     * The symbols directly inside this one, in file order (upstream's `rangeIndex`). A name declared twice
     * in the same scope appears twice here.
     */
    val children: List<FlowSymbol> get() = childList

    /**
     * The symbols directly inside this one by name (upstream's `innerSymbols`): a later declaration of a
     * name replaces an earlier one, and the order is a JavaScript object's (integer-like names first, in
     * numeric order, then the others in order of first declaration), which is the order upstream lists them.
     */
    val innerSymbols: Map<String, FlowSymbol> get() = innerMap

    /** The names of the enclosing symbols, outermost first: `["knot", "stitch"]` for a label in a stitch. */
    val ancestorNames: List<String>
        get() = generateSequence(parent) { it.parent }.map { it.name }.toList().asReversed()

    /** The dotted path of the symbol, `knot.stitch.label`, as upstream shows it in "Go to anything". */
    val path: String get() = (ancestorNames + name).joinToString(".")

    override fun toString(): String = "$flowType($path @$row:$column${if (isFunction) ", function" else ""})"
}

/**
 * The flow containing a row, one entry per kind (upstream's `flowAtPos` result keyed by flow type name):
 * the knot, stitch and label whose ranges contain it. Choice and gather labels are on the same level, so
 * at most one of [choice] and [gather] is set.
 */
data class FlowPath(
    val knot: FlowSymbol? = null,
    val stitch: FlowSymbol? = null,
    val choice: FlowSymbol? = null,
    val gather: FlowSymbol? = null,
)
