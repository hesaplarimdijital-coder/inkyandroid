package com.inkyandroid.core.symbols

/** One file's symbols together with its root-relative path. */
data class FileSymbols(val path: String, val symbols: InkFileSymbols)

/** A symbol found in the project, and the file it is in. */
data class FoundSymbol(val path: String, val symbol: FlowSymbol)

/** Lookups across the files of a project. */
object InkProjectSymbols {

    /**
     * The symbol a divert target [name] (`knot`, `knot.stitch`, `label`, …) written at [row] of the file
     * [activePath] refers to: upstream's `InkProject.findSymbol`, with its search order.
     *
     * The first component is looked for among the children of the symbol at [row], then in that symbol and
     * each of its ancestors up to the file's top level, then among the top-level symbols of every file in
     * [files] order. Each further component is looked for among the children of what was found so far; if
     * one is missing, the symbol found so far is the answer. Null if the first component is not found.
     *
     * [files] are the project's files in the project's order (upstream: main file first, then in the order
     * they were loaded) and must include [activePath].
     */
    fun findSymbol(name: String, activePath: String, row: Int, files: List<FileSymbols>): FoundSymbol? {
        val active = files.firstOrNull { it.path == activePath }?.symbols ?: return null

        // Name components (JavaScript's split: "a..b" has an empty middle component)
        val components = name.split(".")
        val baseName = components[0]

        // Find starting symbol based on the context
        val context = active.symbolAt(row)

        // Try searching towards leaves first. (Deviation: upstream throws here when the file has no
        // symbols at all, so the jump does nothing; going on to the other files finds what it can.)
        var base: FoundSymbol? = context?.let { findWithinDeep(it, baseName) }?.let { FoundSymbol(activePath, it) }

        // Otherwise, work outwards to a broader and broader scope that contains the base name
        if (base == null) {
            var scope = context
            while (scope != null && base == null) {
                scope.innerMap[baseName]?.let { base = FoundSymbol(activePath, it) }
                scope = scope.parent
            }
            // upstream's walk ends at the file's root element, whose children are the top-level symbols
            if (base == null && context != null) active.topLevelScope()[baseName]?.let { base = FoundSymbol(activePath, it) }
        }

        // Finally, search the top level of every file
        if (base == null) {
            for (file in files) {
                val found = file.symbols.topLevelSymbols[baseName]
                if (found != null) {
                    base = FoundSymbol(file.path, found)
                    break
                }
            }
        }

        var result = base ?: return null

        // Resolve the rest of the path
        for (tail in components.drop(1)) {
            val found = findWithinDeep(result.symbol, tail) ?: return result
            result = FoundSymbol(result.path, found)
        }
        return result
    }

    /**
     * Upstream's `findWithinSymbolDeep(withinSymbol, targetName)`: a child named [target], else the same
     * search in each child in turn. Upstream recurses without passing the name on, so below the first
     * level it looks for the name `"undefined"` (JavaScript's `innerSymbols[undefined]`); in effect only
     * direct children are found, unless a descendant is literally called `undefined`. Ported as it is.
     */
    private fun findWithinDeep(within: FlowSymbol, target: String?): FlowSymbol? {
        if (within.innerMap.isEmpty()) return null
        within.innerMap[target ?: "undefined"]?.let { return it }
        for (child in within.innerMap.values) {
            findWithinDeep(child, null)?.let { return it }
        }
        return null
    }
}
