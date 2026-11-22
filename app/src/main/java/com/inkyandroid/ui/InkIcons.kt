package com.inkyandroid.ui

import androidx.compose.ui.graphics.vector.ImageVector
import desktopkit.components.DeskIcons

/**
 * The icons an ink editor needs beyond the kit's, drawn the kit's way: lines on a 16 x 16 grid,
 * 1.6 stroke, round caps ([DeskIcons.build]).
 */
object InkIcons {
    /** A pen's nib: writing. */
    val Write: ImageVector by lazy {
        DeskIcons.build(
            "Write",
            DeskIcons.stroke("M10.5 2.5 13.5 5.5 6 13H3v-3z"),
            DeskIcons.stroke("M9 4l3 3"),
        )
    }

    /** A sheet with a folded corner: an ink file. */
    val File: ImageVector by lazy {
        DeskIcons.build(
            "File",
            DeskIcons.stroke("M4.5 2.5h4.5L12 5.5v7a1 1 0 0 1-1 1H4.5a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1z"),
            DeskIcons.stroke("M9 2.5v3h3"),
        )
    }

    /** A sheet with lines of text: the files of a project. */
    val Files: ImageVector by lazy {
        DeskIcons.build(
            "Files",
            DeskIcons.stroke("M4.5 2.5h4.5L12 5.5v7a1 1 0 0 1-1 1H4.5a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1z"),
            DeskIcons.stroke("M9 2.5v3h3"),
            DeskIcons.stroke("M5.8 8.5h3.9M5.8 11h3.9"),
        )
    }

    /** An open book: the main ink file, and the manual. */
    val Book: ImageVector by lazy {
        DeskIcons.build(
            "Book",
            DeskIcons.stroke("M8 4.2C6.6 3.3 4.6 3 2.5 3.2v8.6c2.1-.2 4.1.1 5.5 1 1.4-.9 3.4-1.2 5.5-1V3.2C11.4 3 9.4 3.3 8 4.2Z"),
            DeskIcons.stroke("M8 4.2v8.6"),
        )
    }

    /** Three levels of lines: the outline of a file. */
    val Outline: ImageVector by lazy {
        DeskIcons.build("Outline", DeskIcons.stroke("M2.5 4h11M5 8h8.5M7.5 12h6"))
    }

    /** A loop pulled tight: a knot. */
    val Knot: ImageVector by lazy {
        DeskIcons.build(
            "Knot",
            DeskIcons.stroke("M4.5,8a3.5,3.5 0 1 0 7,0a3.5,3.5 0 1 0 -7,0Z"),
            DeskIcons.stroke("M1.5 8h3M11.5 8h3"),
        )
    }

    /** A small ring on a line: a stitch inside a knot. */
    val Stitch: ImageVector by lazy {
        DeskIcons.build(
            "Stitch",
            DeskIcons.stroke("M6,8a2,2 0 1 0 4,0a2,2 0 1 0 -4,0Z"),
            DeskIcons.stroke("M2.5 8H6M10 8h3.5"),
        )
    }

    /** Braces: a function. */
    val Function: ImageVector by lazy {
        DeskIcons.build(
            "Function",
            DeskIcons.stroke("M6 3c-1.5 0-2 .7-2 2v1.5c0 .9-.5 1.5-1.5 1.5C3.5 8 4 8.6 4 9.5V11c0 1.3.5 2 2 2"),
            DeskIcons.stroke("M10 3c1.5 0 2 .7 2 2v1.5c0 .9.5 1.5 1.5 1.5-1 0-1.5.6-1.5 1.5V11c0 1.3-.5 2-2 2"),
        )
    }

    /** An arrow leaving a box: a function bound outside the story. */
    val External: ImageVector by lazy {
        DeskIcons.build(
            "External",
            DeskIcons.stroke("M7 3.5H4a1 1 0 0 0-1 1V12a1 1 0 0 0 1 1h7.5a1 1 0 0 0 1-1V9"),
            DeskIcons.stroke("M9.5 3h3.5v3.5M13 3 7.5 8.5"),
        )
    }

    /** An arrow that turns back: one choice back. */
    val StepBack: ImageVector by lazy {
        DeskIcons.build(
            "StepBack",
            DeskIcons.stroke("M6.5 4 2.5 8l4 4"),
            DeskIcons.stroke("M2.5 8h6.5a4.5 4.5 0 0 1 4.5 4.5"),
        )
    }

    /** An arrow around a circle: the story from its start. */
    val Restart: ImageVector by lazy {
        DeskIcons.build(
            "Restart",
            DeskIcons.stroke("M3 8a5 5 0 1 0 1.7-3.8"),
            DeskIcons.stroke("M3 2.5v3h3"),
        )
    }

    /** The mirror of the kit's Undo. */
    val Redo: ImageVector by lazy {
        DeskIcons.build("Redo", DeskIcons.stroke("M10.5 4 13.5 7l-3 3"), DeskIcons.stroke("M13.5 7h-7a3.5 3.5 0 0 0 0 7H9"))
    }

    /** A disk: save. */
    val Save: ImageVector by lazy {
        DeskIcons.build(
            "Save",
            DeskIcons.stroke("M3.5 2.5h7.5l2.5 2.5v7.5a1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1z"),
            DeskIcons.stroke("M5.5 2.5v3h4.5v-3M5 13.5V9.5h6v4"),
        )
    }

    /** A note with a tick box: a TODO. */
    val Todo: ImageVector by lazy {
        DeskIcons.build(
            "Todo",
            DeskIcons.stroke("M3.5 2.5h9a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1z"),
            DeskIcons.stroke("m5.5 8.2 1.8 1.8 3.4-3.8"),
        )
    }

    /** An arrow to the right: forward, and a jump to a place. */
    val ArrowRight: ImageVector by lazy {
        DeskIcons.build("ArrowRight", DeskIcons.stroke("M2.5 8h11M9.5 4l4 4-4 4"))
    }

    /** Lines, the second pushed in: a snippet of ink to insert. */
    val Snippet: ImageVector by lazy {
        DeskIcons.build("Snippet", DeskIcons.stroke("M2.5 4h7M5 8h8.5M2.5 12h7"))
    }

    /** A box with an arrow out of it: export. */
    val Export: ImageVector by lazy {
        DeskIcons.build(
            "Export",
            DeskIcons.stroke("M3 9.5V12a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1V9.5"),
            DeskIcons.stroke("M8 10V2.5M5 5.5l3-3 3 3"),
        )
    }

    /** A tag on a string: a choice's or a gather's label, a place a divert can lead to. */
    val Label: ImageVector by lazy {
        DeskIcons.build(
            "Label",
            DeskIcons.stroke("M2.5 3.5v4.1l6 6 5-5-6-6H3.5a1 1 0 0 0-1 1z"),
            DeskIcons.stroke("M5.5 6h.01"),
        )
    }

    /** A line of text: a line of a file found by its words. */
    val TextLine: ImageVector by lazy {
        DeskIcons.build("TextLine", DeskIcons.stroke("M2.5 5h11M2.5 8h11M2.5 11h7"))
    }
}
