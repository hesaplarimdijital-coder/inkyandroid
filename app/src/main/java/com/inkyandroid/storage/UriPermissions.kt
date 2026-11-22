package com.inkyandroid.storage

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * The persisted URI permissions of the app: taken when the user picks a folder or a file, given
 * back when nothing refers to them any more (Android caps how many an app may hold).
 */
internal class UriPermissions(private val resolver: ContentResolver) {

    /**
     * Keeps read and write access to [uri] across restarts. Returns `false` when the grant is not
     * persistable (a file another app shared with a plain `VIEW` intent): then access ends with
     * the process.
     */
    fun take(uri: Uri): Boolean {
        val modes = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return try {
            resolver.takePersistableUriPermission(uri, modes)
            true
        } catch (e: SecurityException) {
            // Write may not be on offer (a read-only share): keep at least read.
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                true
            } catch (e2: SecurityException) {
                Log.i(LOG_TAG, "no persistable permission for $uri: ${e2.message}")
                false
            }
        }
    }

    /** Whether a persisted grant for [uri] (with read access, and write access when [write]) exists. */
    fun holds(uri: String, write: Boolean = false): Boolean = resolver.persistedUriPermissions.toList().any {
        it.uri.toString() == uri && it.isReadPermission && (!write || it.isWritePermission)
    }

    /** The URIs of every persisted grant, trees and single documents alike. */
    fun persisted(): List<Uri> = resolver.persistedUriPermissions.toList().map { it.uri }

    /** Gives back every persisted grant that is not in [inUse]. */
    fun releaseAllExcept(inUse: Set<String>) {
        // a copy: releasing changes the list some implementations return
        for (permission in resolver.persistedUriPermissions.toList()) {
            val uri = permission.uri
            if (uri.toString() in inUse) continue
            var modes = 0
            if (permission.isReadPermission) modes = modes or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (permission.isWritePermission) modes = modes or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                resolver.releasePersistableUriPermission(uri, modes)
                Log.i(LOG_TAG, "released the permission for $uri")
            } catch (e: SecurityException) {
                Log.w(LOG_TAG, "could not release the permission for $uri: ${e.message}")
            }
        }
    }
}
