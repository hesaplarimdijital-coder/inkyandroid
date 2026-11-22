package com.inkyandroid.ui.extras.docs

import androidx.compose.ui.graphics.vector.ImageVector
import desktopkit.components.DeskIcons

/** Two pages, one over the other: copy an example. Drawn on the kit's 16 dp grid in its stroke. */
internal val CopyIcon: ImageVector by lazy {
    DeskIcons.build(
        "Copy",
        DeskIcons.stroke("M6.5,5.5H12A1,1 0 0 1 13,6.5V12.5A1,1 0 0 1 12,13.5H6.5A1,1 0 0 1 5.5,12.5V6.5A1,1 0 0 1 6.5,5.5Z"),
        DeskIcons.stroke("M3.5 10.5h-.5a.5.5 0 0 1-.5-.5V3.5a1 1 0 0 1 1-1H9.5a.5.5 0 0 1 .5.5V3"),
    )
}

/** A text cursor with an arrow into it: insert an example at the editor's cursor. */
internal val InsertIcon: ImageVector by lazy {
    DeskIcons.build(
        "Insert",
        DeskIcons.stroke("M11.5 2.5v11M10 2.5h3M10 13.5h3"),
        DeskIcons.stroke("M2.5 8h6M6 5.5 8.5 8 6 10.5"),
    )
}
