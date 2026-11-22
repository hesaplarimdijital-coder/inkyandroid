package com.inkyandroid.core.project

import java.security.MessageDigest

/**
 * The fingerprint of a file's text: how the project model recognises "the same content" without
 * keeping a second copy of every file. It tells an outside change from one of our own saves, and
 * ties a draft to the stored version it was written over.
 */
object TextHash {

    /** SHA-256 of [text] in UTF-8, as lowercase hexadecimal. */
    fun of(text: CharSequence): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toString().toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            hex.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return hex.toString()
    }

    private const val HEX = "0123456789abcdef"
}
