package com.inkyandroid.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log

/**
 * The name and size every content provider is asked to offer for a URI it shares
 * (`OpenableColumns`). File managers that send a file through their own provider often answer
 * nothing else, and some not even this.
 */
internal data class OpenableInfo(val name: String?, val size: Long?) {
    companion object {
        /** What the provider tells; `null` when it answers the query with nothing at all. Blocking. */
        fun query(resolver: ContentResolver, uri: Uri): OpenableInfo? = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                OpenableInfo(
                    name = if (nameIndex >= 0 && !c.isNull(nameIndex)) c.getString(nameIndex) else null,
                    size = if (sizeIndex >= 0 && !c.isNull(sizeIndex)) c.getLong(sizeIndex) else null,
                )
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            // providers that do not implement query() throw anything from UnsupportedOperation to NPE
            Log.i(LOG_TAG, "no openable columns for $uri: ${e.message}")
            null
        }
    }
}
