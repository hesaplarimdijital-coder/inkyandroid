package com.inkyandroid.ui.extras

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.fillMaxSize
import com.inkyandroid.ui.extras.docs.DocsReader
import com.inkyandroid.ui.extras.docs.ManualSource
import desktopkit.settings.DeskPreferences

/**
 * The route `docs`: inkle's manual "Writing with ink", drawn in the kit's type and colours, with its
 * table of contents (beside the text on wide windows, in a sheet otherwise), find, the reader's own
 * text size, and the ink examples coloured as in the editor. The reading position is kept between
 * visits and restarts.
 *
 * @param prefs the kit's preferences, where the split between contents and text is kept.
 * @param insertIntoEditor inserts an example at the cursor of the open file; null when no project
 *   is open. Offered on wide windows only (on a phone the editor is another screen).
 */
@Composable
fun DocsScreen(
    prefs: DeskPreferences,
    modifier: Modifier = Modifier,
    insertIntoEditor: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    // parsed once per process: the first visit waits for it (a fraction of a second), later ones do not
    val manual by produceState(ManualSource.cached(), context) { value = ManualSource.load(context) }
    val loaded = manual
    if (loaded == null) {
        Box(modifier.fillMaxSize())
        return
    }
    Column(modifier.fillMaxSize()) {
        DocsReader(loaded, prefs, insertIntoEditor)
    }
}
