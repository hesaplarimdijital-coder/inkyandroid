package com.inkyandroid.ui.extras

import androidx.compose.runtime.Composable

/**
 * The numbers of upstream's "Word count and more" (`controller.js: project-stats`), as ink's compiler
 * counts them (`Stats.Generate`): knots include functions, gathers and diverts include some the
 * compiler adds itself, diverts include `END` and `DONE`.
 */
data class StoryStatistics(
    val words: Int,
    val knots: Int,
    val stitches: Int,
    val functions: Int,
    val choices: Int,
    val gathers: Int,
    val diverts: Int,
)

/**
 * "Word count and more": the project's statistics in a dialog, computed when it opens.
 *
 * @param statistics computes the numbers (the live session's statistics); a thrown exception is
 *   shown as a failure inside the dialog.
 */
@Composable
fun StatisticsDialog(statistics: suspend () -> StoryStatistics, onDismiss: () -> Unit) {
    TODO("app-extras: being implemented")
}
