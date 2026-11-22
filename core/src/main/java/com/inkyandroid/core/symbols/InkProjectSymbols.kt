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
    fun findSymbol(name: String, activePath: String, row: Int, files: List<FileSymbols>): FoundSymbol? = TODO()
}
