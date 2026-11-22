package com.inkyandroid.ui.extras

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.inkyandroid.R
import desktopkit.components.DeskButton
import desktopkit.components.DeskDialog
import desktopkit.components.DeskProgressBar
import desktopkit.components.KeyValueRow
import desktopkit.theme.DeskTheme
import java.text.NumberFormat
import kotlin.coroutines.cancellation.CancellationException

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

/** Names the tests find the dialog's parts by. */
object StatisticsTags {
    const val NUMBERS = "statistics-numbers"
    const val BUSY = "statistics-busy"
    const val FAILED = "statistics-failed"
}

/**
 * "Word count and more": the project's statistics in a dialog, computed when it opens. The numbers
 * are grouped as upstream's alert groups them (words; knots, stitches, functions; choices, gathers,
 * diverts), with upstream's note on what they include.
 *
 * @param statistics computes the numbers (the live session's statistics); a thrown exception is
 *   shown as a failure inside the dialog.
 */
@Composable
fun StatisticsDialog(statistics: suspend () -> StoryStatistics, onDismiss: () -> Unit) {
    var state by remember { mutableStateOf<StatisticsState>(StatisticsState.Counting) }
    val compute by rememberUpdatedState(statistics)
    LaunchedEffect(Unit) {
        state = try {
            StatisticsState.Counted(compute())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            StatisticsState.Failed(failure.message?.takeIf { it.isNotBlank() } ?: failure::class.java.simpleName)
        }
    }
    DeskDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.statistics_title),
        actions = { DeskButton(stringResource(R.string.statistics_close), onDismiss, primary = true) },
    ) {
        when (val shown = state) {
            StatisticsState.Counting -> Column(Modifier.fillMaxWidth().testTag(StatisticsTags.BUSY)) {
                Text(stringResource(R.string.statistics_counting), style = DeskTheme.type.body, color = DeskTheme.colors.textSecondary)
                DeskProgressBar(null, Modifier.fillMaxWidth().padding(top = DeskTheme.dimens.gapM))
            }
            is StatisticsState.Failed -> Text(
                stringResource(R.string.statistics_failed, shown.reason),
                style = DeskTheme.type.body,
                color = DeskTheme.colors.danger,
                modifier = Modifier.testTag(StatisticsTags.FAILED),
            )
            is StatisticsState.Counted -> Numbers(shown.numbers)
        }
    }
}

@Composable
private fun Numbers(numbers: StoryStatistics) {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val gap = DeskTheme.dimens.gapM
    Column(Modifier.fillMaxWidth().testTag(StatisticsTags.NUMBERS), verticalArrangement = Arrangement.spacedBy(gap)) {
        for (group in statisticsGroups(numbers)) {
            Column {
                for ((label, value) in group) {
                    KeyValueRow(stringResource(label), format.format(value), mono = true)
                }
            }
        }
        Text(stringResource(R.string.statistics_note), style = DeskTheme.type.caption, color = DeskTheme.colors.textTertiary)
    }
}

/** Upstream's three groups of numbers, each a list of (label resource, value). */
internal fun statisticsGroups(numbers: StoryStatistics): List<List<Pair<Int, Int>>> = listOf(
    listOf(R.string.statistics_words to numbers.words),
    listOf(
        R.string.statistics_knots to numbers.knots,
        R.string.statistics_stitches to numbers.stitches,
        R.string.statistics_functions to numbers.functions,
    ),
    listOf(
        R.string.statistics_choices to numbers.choices,
        R.string.statistics_gathers to numbers.gathers,
        R.string.statistics_diverts to numbers.diverts,
    ),
)

private sealed interface StatisticsState {
    data object Counting : StatisticsState
    data class Counted(val numbers: StoryStatistics) : StatisticsState
    data class Failed(val reason: String) : StatisticsState
}
