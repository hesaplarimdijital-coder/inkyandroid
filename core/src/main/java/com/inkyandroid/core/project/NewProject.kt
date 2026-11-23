package com.inkyandroid.core.project

/** What a brand-new project starts with. */
object NewProject {

    /**
     * The text of a new project's main file, exactly upstream's (`inkFile.js`): a tiny complete
     * story, so the player has something to show at once. Included files start empty.
     */
    const val MAIN_TEXT: String =
        "Once upon a time...\n\n" +
            " * There were two choices.\n" +
            " * There were four lines of content.\n\n" +
            "- They lived happily ever after.\n" +
            "    -> END\n"

    /** The name upstream gives the main file of a project that has not been saved yet. */
    const val UNTITLED_NAME: String = "Untitled.ink"
}
