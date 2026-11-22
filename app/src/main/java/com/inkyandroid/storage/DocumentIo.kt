package com.inkyandroid.storage

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.SyncFailedException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Reading and writing the bytes of one document, the same way for every store and for exports.
 * Blocking; callers are on an I/O dispatcher.
 */
internal object DocumentIo {

    /** The bytes of [uri], refusing anything longer than [StorageLimits.MAX_TEXT_BYTES]. */
    fun readBytes(resolver: ContentResolver, uri: Uri, nameForErrors: String): ByteArray {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: throw ProviderFailedException("$nameForErrors could not be opened")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > StorageLimits.MAX_TEXT_BYTES) throw NotTextException("$nameForErrors is larger than ${StorageLimits.MAX_TEXT_BYTES} bytes")
            }
            return out.toByteArray()
        }
    }

    /**
     * Replaces the content of [uri] with [bytes].
     *
     * Opened with `"wt"` (write, truncate). Some providers do not accept `"wt"`; then `"w"` is used
     * and the file is truncated to the new length afterwards, which fails harmlessly for providers
     * that stream through a pipe (their `"w"` already replaces). Whatever happened, the caller
     * verifies the result: a provider may report success and keep the old content.
     *
     * The bytes are flushed to the disk before the descriptor is closed; closing reports a failure
     * the provider signals at the end of a piped write.
     */
    fun writeBytes(resolver: ContentResolver, uri: Uri, bytes: ByteArray) {
        val truncating = openForWrite(resolver, uri, "wt")
        val pfd = truncating ?: openForWrite(resolver, uri, "w")
            ?: throw ProviderFailedException("the file could not be opened for writing")
        ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
            out.write(bytes)
            out.flush()
            if (truncating == null) {
                try {
                    out.channel.truncate(bytes.size.toLong())
                } catch (e: IOException) {
                    Log.i(LOG_TAG, "no truncate after \"w\" for $uri: ${e.message}")
                }
            }
            try {
                out.fd.sync()
            } catch (e: SyncFailedException) {
                // pipes and sockets cannot be synced; the provider writes on close
            }
        }
    }

    /** `null` when the provider rejects [mode] itself (not the file: that failure is thrown). */
    private fun openForWrite(resolver: ContentResolver, uri: Uri, mode: String): ParcelFileDescriptor? = try {
        resolver.openFileDescriptor(uri, mode)
    } catch (e: IllegalArgumentException) {
        Log.i(LOG_TAG, "mode \"$mode\" refused for $uri: ${e.message}")
        null
    } catch (e: UnsupportedOperationException) {
        Log.i(LOG_TAG, "mode \"$mode\" refused for $uri: ${e.message}")
        null
    } catch (e: FileNotFoundException) {
        // Some providers answer an unsupported mode with "FileNotFoundException: Unsupported mode";
        // a missing file fails the same way, and then the second mode fails too and is thrown.
        if (mode == "wt") {
            Log.i(LOG_TAG, "mode \"wt\" failed for $uri: ${e.message}")
            null
        } else {
            throw e
        }
    }

    /**
     * The text in [bytes]: strict UTF-8 (a file in another encoding is refused, never decoded with
     * replacement characters that a save would then write back), one leading byte order mark
     * removed, nothing else changed.
     */
    fun decodeText(bytes: ByteArray, nameForErrors: String): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw NotTextException("$nameForErrors is not UTF-8 text")
        }
        return text.removePrefix("﻿")
    }

    /** UTF-8 without a byte order mark, exactly the given text. */
    fun encodeText(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /**
     * Whether [bytes] look like a text file a person would open in Inky: valid UTF-8 and no NUL
     * byte in the first 64 KiB. Used to accept files other apps send without a name or type.
     */
    fun looksLikeText(bytes: ByteArray): Boolean {
        val head = if (bytes.size > 64 * 1024) bytes.copyOf(64 * 1024) else bytes
        if (head.any { it == 0.toByte() }) return false
        return try {
            decodeText(bytes, "")
            true
        } catch (e: NotTextException) {
            false
        }
    }
}
