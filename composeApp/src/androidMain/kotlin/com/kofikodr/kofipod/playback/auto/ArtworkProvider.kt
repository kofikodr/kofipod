// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.playback.auto

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.kofikodr.kofipod.data.repo.LibraryRepository
import org.koin.java.KoinJavaComponent
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest

/**
 * Serves cached podcast artwork as content:// URIs so Android Auto (which refuses http
 * artworkUris) can load images.
 *
 * URI form: content://{applicationId}.artwork/{urlencoded-http-url}
 *
 * The remote URL must match a known Podcast.artworkUrl — otherwise the provider refuses
 * to open the file. This prevents any app on the device from using the provider as an
 * open HTTP proxy that would pre-populate our cache from attacker-controlled URLs.
 */
class ArtworkProvider : ContentProvider() {
    private val library: LibraryRepository by lazy {
        KoinJavaComponent.get(LibraryRepository::class.java)
    }

    override fun onCreate(): Boolean = true

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val encoded = uri.lastPathSegment ?: return null
        val remoteUrl = Uri.decode(encoded) ?: return null
        if (!remoteUrl.startsWith("http://") && !remoteUrl.startsWith("https://")) return null
        if (!library.hasArtworkUrl(remoteUrl)) return null
        val file = cacheFileFor(ctx, remoteUrl)
        if (!file.exists() || file.length() == 0L) {
            if (!downloadAtomically(remoteUrl, file)) return null
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String = "image/*"

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun downloadAtomically(
        url: String,
        dest: File,
    ): Boolean {
        // SSRF gate: every resolved address must be public. The exported
        // provider plus library.hasArtworkUrl() means an attacker who controls
        // a podcast feed can register `http://192.168.1.1/admin` as an artwork
        // URL — without this check, any local app could trigger the fetch.
        // The OS may resolve a *different* address at openConnection time
        // (DNS rebinding, separate A-record); we accept that TOCTOU window
        // because instanceFollowRedirects=false plus the content-type check
        // makes the worst-case payload limited.
        when (validateArtworkUrl(url) { host -> InetAddress.getAllByName(host) }) {
            is ArtworkUrlCheck.Blocked -> return false
            ArtworkUrlCheck.Ok -> Unit
        }

        val parent = dest.parentFile ?: return false
        parent.mkdirs()
        val tmp = File(parent, "${dest.name}.tmp")
        var conn: HttpURLConnection? = null
        var succeeded = false
        return try {
            run download@{
                val opened =
                    (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = TIMEOUT_MS
                        readTimeout = TIMEOUT_MS
                        // Redirects must not bypass the pre-fetch validation. A 3xx
                        // could send us to a private address whose hostname we
                        // never resolved. Reject any non-200 status.
                        instanceFollowRedirects = false
                    }
                conn = opened
                if (opened.responseCode != HttpURLConnection.HTTP_OK) return@download false

                val contentType = opened.contentType?.substringBefore(';')?.trim()?.lowercase()
                if (contentType == null || !contentType.startsWith("image/")) return@download false

                // contentLengthLong returns -1 when missing. Reject pre-declared
                // overshoots before opening the input stream so we don't read a
                // single byte of a hostile multi-GB response.
                val declaredLength = opened.contentLengthLong
                if (declaredLength > MAX_ARTWORK_BYTES) return@download false

                var copied = 0L
                var sizeExceeded = false
                opened.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(ARTWORK_BUFFER_SIZE)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            copied += n
                            if (copied > MAX_ARTWORK_BYTES) {
                                sizeExceeded = true
                                return@use
                            }
                            output.write(buffer, 0, n)
                        }
                    }
                }
                if (sizeExceeded) return@download false
                if (tmp.length() == 0L) return@download false
                succeeded = tmp.renameTo(dest)
                succeeded
            }
        } catch (_: Exception) {
            false
        } finally {
            // disconnect() releases the socket back to the keep-alive pool;
            // skipping it on failure paths leaks the connection until GC.
            conn?.disconnect()
            if (!succeeded && tmp.exists()) runCatching { tmp.delete() }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 10_000
        private const val CACHE_DIR = "artwork_cache"

        fun authority(context: Context): String = "${context.packageName}.artwork"

        fun uriFor(
            context: Context,
            remoteUrl: String,
        ): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(Uri.encode(remoteUrl))
                .build()

        internal fun cacheFileFor(
            context: Context,
            remoteUrl: String,
        ): File {
            val dir = File(context.filesDir, CACHE_DIR)
            val name = sha256Hex(remoteUrl)
            return File(dir, "$name.img")
        }

        private fun sha256Hex(input: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                sb.append(HEX[(b.toInt() ushr 4) and 0xF])
                sb.append(HEX[b.toInt() and 0xF])
            }
            return sb.toString()
        }

        private val HEX = "0123456789abcdef".toCharArray()
    }
}
