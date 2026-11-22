package com.inkyandroid.ui.extras

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import desktopkit.settings.DeskPreferences

/**
 * The route `docs`: inkle's manual "Writing with ink", drawn in the kit's type and colours, with its
 * table of contents, find, and the ink examples coloured as in the editor.
 *
 * @param prefs the kit's preferences, where the split between contents and text is kept.
 * @param insertIntoEditor inserts an example at the cursor of the open file; null when no project
 *   is open. Offered on wide windows only.
 */
@Composable
fun DocsScreen(
    prefs: DeskPreferences,
    modifier: Modifier = Modifier,
    insertIntoEditor: ((String) -> Unit)? = null,
) {
    TODO("app-extras: being implemented")
}
