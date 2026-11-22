package com.inkyandroid.core.engine

/**
 * What the compiler compiles: the text of every file of a project at one moment.
 *
 * Paths are root-relative with `/` separators, as everywhere in the project model. Files that
 * nothing includes may be present; the compiler only reads the main file and what its `INCLUDE`
 * lines reach.
 *
 * `INCLUDE` names are resolved the way inklecate resolves them: every one of them, also inside an
 * included file, against the folder of the main file (not of the including file), then made
 * canonical with [com.inkyandroid.core.project.ProjectPaths.normalizeInclude], so the compiler and
 * the files view agree on which file a line names.
 *
 * @property mainPath the file compilation starts from; a key of [texts].
 * @property texts path → text.
 */
data class InkSources(
    val mainPath: String,
    val texts: Map<String, String>,
)
