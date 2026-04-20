// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playback.auto

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.kofipod.data.repo.LibraryRepository
import org.koin.java.KoinJavaComponent
import java.io.File
import java.net.HttpURLConnection
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
        val parent = dest.parentFile ?: return false
        parent.mkdirs()
        val tmp = File(parent, "${dest.name}.tmp")
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            conn.disconnect()
            if (tmp.length() == 0L) {
                tmp.delete()
                false
            } else {
                tmp.renameTo(dest)
            }
        } catch (_: Exception) {
            runCatching { tmp.delete() }
            false
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
