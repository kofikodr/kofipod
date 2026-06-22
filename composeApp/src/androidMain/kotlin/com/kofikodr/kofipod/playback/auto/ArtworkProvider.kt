// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.playback.auto

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.kofikodr.kofipod.data.repo.LibraryRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.java.KoinJavaComponent
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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

    private val httpClient: OkHttpClient by lazy { buildArtworkHttpClient() }

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
        // Fast, NON-authoritative pre-flight: reject obviously-bad URLs (bad
        // scheme, malformed, missing host, an already-private resolution) before
        // building the request. The authoritative SSRF gate is [SsrfBlockingDns]
        // on [httpClient] — OkHttp connects to exactly the addresses that Dns
        // validated, so there is no validate-then-reconnect DNS-rebinding window
        // (issue #31). This pre-flight is just a cheap early-out.
        when (validateArtworkUrl(url) { host -> InetAddress.getAllByName(host) }) {
            is ArtworkUrlCheck.Blocked -> return false
            ArtworkUrlCheck.Ok -> Unit
        }

        val parent = dest.parentFile ?: return false
        parent.mkdirs()
        val tmp = File(parent, "${dest.name}.tmp")
        var succeeded = false
        return try {
            val request = Request.Builder().url(url).get().build()
            succeeded =
                httpClient.newCall(request).execute().use { response ->
                    // Redirects are disabled on the client, so a non-200 (incl. any
                    // 3xx) is a hard reject — a redirect could target an unvalidated host.
                    if (response.code != HTTP_OK) return@use false

                    val body = response.body ?: return@use false
                    val mediaType = body.contentType()
                    if (mediaType == null || mediaType.type != "image") return@use false

                    // contentLength() is -1 when the server omits it. Reject a
                    // pre-declared overshoot before reading a single byte.
                    if (body.contentLength() > MAX_ARTWORK_BYTES) return@use false

                    copyCapped(body.byteStream(), tmp) && tmp.length() > 0L && tmp.renameTo(dest)
                }
            succeeded
        } catch (_: Exception) {
            false
        } finally {
            if (!succeeded && tmp.exists()) runCatching { tmp.delete() }
        }
    }

    /**
     * Streams [input] into [tmp], aborting (and returning false) if more than
     * [MAX_ARTWORK_BYTES] arrive — so a hostile server can't fill the disk by
     * omitting/lying about Content-Length. Both streams are closed on exit.
     */
    private fun copyCapped(
        input: java.io.InputStream,
        tmp: File,
    ): Boolean {
        input.use { src ->
            tmp.outputStream().use { output ->
                val buffer = ByteArray(ARTWORK_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val n = src.read(buffer)
                    if (n < 0) break
                    copied += n
                    if (copied > MAX_ARTWORK_BYTES) return false
                    output.write(buffer, 0, n)
                }
            }
        }
        return true
    }

    companion object {
        private const val HTTP_OK = 200
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

private const val ARTWORK_HTTP_TIMEOUT_MS = 10_000L

/**
 * Builds the [OkHttpClient] [ArtworkProvider] uses to fetch artwork.
 *
 * Extracted as an `internal` top-level function so a unit test can assert the
 * security-critical wiring stays in place — pinned [SsrfBlockingDns] (the
 * authoritative SSRF gate, issue #31) plus redirects disabled (a 3xx must not
 * bounce the fetch to an unvalidated host). Without this seam those guarantees
 * lived inline in a `private val` that no test could observe, so a future edit
 * could silently drop them with every test still green.
 */
internal fun buildArtworkHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(ARTWORK_HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(ARTWORK_HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .dns(SsrfBlockingDns())
        .build()
