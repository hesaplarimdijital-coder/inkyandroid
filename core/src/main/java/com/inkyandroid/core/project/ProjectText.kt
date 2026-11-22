package com.inkyandroid.core.project

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** How a project file's bytes become text and back, for every [ProjectStore]. */
object ProjectText {

    /**
     * The text of a stored file: strict UTF-8, one leading byte order mark removed, nothing else
     * changed (line endings stay as they are).
     *
     * Bytes that are not UTF-8 are a [CharacterCodingException] (an `IOException`), never a silent
     * replacement character: a file in another encoding must fail to open rather than be opened
     * damaged and then saved that way.
     */
    @Throws(CharacterCodingException::class)
    fun decode(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        return if (text.startsWith(BYTE_ORDER_MARK)) text.substring(1) else text
    }

    /** The bytes a file is stored as: UTF-8, no byte order mark. */
    fun encode(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    private const val BYTE_ORDER_MARK = "﻿"
}
