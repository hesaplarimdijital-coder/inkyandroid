package com.inkyandroid.core.engine

import com.bladecoder.ink.runtime.ControlCommand
import com.bladecoder.ink.runtime.RTObject
import com.bladecoder.ink.runtime.StringValue

/**
 * Builds [StoryLine.spans] from the story's output stream after one step of the story.
 *
 * The runtime keeps debug metadata on every compiled text object, so each piece of text in the
 * output stream knows its file and line. (Values printed by `{x}` are made at runtime and have
 * none: they take the place of the text before them, as inklecate's own lookup does.) The line's
 * text is that output after `StoryState.cleanOutputWhitespace`, which collapses runs of blanks and
 * trims line ends; this replays the same algorithm character by character, so that every character
 * of the final text keeps the place it came from. Tag text (between `BeginTag` and `EndTag`) is
 * not part of the line and is skipped, as the runtime skips it.
 *
 * This is finer than desktop Inky, which asks inklecate for a character offset counted over the
 * whole session and drifts (inklecate counts raw text and tag text).
 */
internal object SourceSpans {
    fun of(outputStream: List<RTObject>, expectedText: String, paths: IncludePaths): List<SourceSpan> {
        val raw = StringBuilder()
        val owners = ArrayList<SourceLocation?>()
        var inTag = false
        var lastKnown: SourceLocation? = null
        var firstTagLocation: SourceLocation? = null
        for (obj in outputStream) {
            when {
                obj is ControlCommand && obj.commandType == ControlCommand.CommandType.BeginTag -> inTag = true
                obj is ControlCommand && obj.commandType == ControlCommand.CommandType.EndTag -> inTag = false
                obj is StringValue && inTag -> if (firstTagLocation == null) firstTagLocation = locationOf(obj, paths)
                obj is StringValue -> {
                    val location = locationOf(obj, paths)
                    if (location != null) lastKnown = location
                    raw.append(obj.value)
                    repeat(obj.value.length) { owners += location ?: lastKnown }
                }
            }
        }
        // A line of tags alone ("# title" above an INCLUDE comes out as "\n" with the tag) is located by its tags.
        if (owners.all { it == null }) return listOf(SourceSpan(0, expectedText.length, firstTagLocation))
        val (cleanText, cleanOwners) = cleanWhitespace(raw, owners)
        // The replay must give the runtime's text exactly. Should a different runtime clean text
        // differently, one span with the line's first place is right; shifted spans would not be.
        if (cleanText != expectedText) {
            return listOf(SourceSpan(0, expectedText.length, owners.firstNotNullOfOrNull { it }))
        }
        return group(cleanOwners)
    }

    fun locationOf(obj: RTObject, paths: IncludePaths): SourceLocation? {
        val metadata = obj.debugMetadata ?: return null
        val file = metadata.fileName ?: return null
        return SourceLocation(paths.resolve(file), metadata.startLineNumber)
    }

    /** `StoryState.cleanOutputWhitespace` of blade-ink 1.3.3, carrying an owner for each character. */
    private fun cleanWhitespace(text: CharSequence, owners: List<SourceLocation?>): Pair<String, List<SourceLocation?>> {
        val clean = StringBuilder(text.length)
        val cleanOwners = ArrayList<SourceLocation?>(text.length)
        var whitespaceStart = -1
        var startOfLine = 0
        for (i in text.indices) {
            val c = text[i]
            val isInlineWhitespace = c == ' ' || c == '\t'
            if (isInlineWhitespace && whitespaceStart == -1) whitespaceStart = i
            if (!isInlineWhitespace) {
                if (c != '\n' && whitespaceStart > 0 && whitespaceStart != startOfLine) {
                    clean.append(' ')
                    cleanOwners += owners[whitespaceStart]
                }
                whitespaceStart = -1
            }
            if (c == '\n') startOfLine = i + 1
            if (!isInlineWhitespace) {
                clean.append(c)
                cleanOwners += owners[i]
            }
        }
        return clean.toString() to cleanOwners
    }

    private fun group(owners: List<SourceLocation?>): List<SourceSpan> {
        val spans = ArrayList<SourceSpan>()
        var start = 0
        for (i in 1..owners.size) {
            if (i == owners.size || owners[i] != owners[start]) {
                spans += SourceSpan(start, i, owners[start])
                start = i
            }
        }
        return spans
    }
}
