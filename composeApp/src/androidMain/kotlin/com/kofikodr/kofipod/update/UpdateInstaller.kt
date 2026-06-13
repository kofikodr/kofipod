// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.kofikodr.kofipod.data.repo.UpdateRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Handles the lifecycle of a downloaded APK: streaming it from the GitHub release
 * URL into private app storage, then handing it off to the system installer.
 *
 * Storage location: `<filesDir>/updates/`. We exclude `files/downloads/` from
 * Auto Backup but `files/updates/` is small (one APK at a time) and re-fetchable —
 * it doesn't need to be backed up either, but the cost of including it is negligible.
 */
class UpdateInstaller(
    private val context: Context,
    private val httpClient: HttpClient,
    private val repo: UpdateRepository,
) {
    /**
     * Reconciles the persisted "I have a downloaded APK at <path>" pointer with what's
     * actually on disk. The pointer rides Auto Backup (it's a SyncMeta row in the SQLDelight
     * DB) but the APK itself does not — so after a device restore the pointer is stale and
     * `install()` would crash trying to open a missing file. Call this once at app start.
     */
    fun reconcileDownloadedApk() {
        val path = repo.downloadedApkPathNow() ?: return
        if (!File(path).exists()) {
            // Clear silently. The user can re-download from Settings if they still want this update.
            repo.markApkDownloaded("")
        }
    }

    /**
     * Streaming download. Reports raw bytes-downloaded via [onProgress] (capped to
     * the total when known). Returns the absolute path of the APK on success.
     *
     * No in-app signature verification is performed: the system installer (PackageInstaller)
     * compares the new APK's signing certificate against the currently-installed Kofipod
     * certificate before the install proceeds, and rejects mismatches. That's the
     * authoritative check for a sideloaded update channel like ours; an additional in-app
     * hash/signature verification would be defense-in-depth but isn't load-bearing here.
     */
    suspend fun download(
        info: UpdateInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): String =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, UPDATES_DIR).apply { mkdirs() }
            val path = streamApkInto(httpClient = httpClient, dir = dir, info = info, onProgress = onProgress)
            repo.markApkDownloaded(path)
            path
        }

    /**
     * Hands the APK to the system installer. Caller must verify
     * [canRequestInstall] first; if the permission isn't granted, route the user
     * to [openInstallPermissionSettings] instead.
     *
     * Returns `false` if the APK is no longer on disk (e.g. user cleared app data
     * after download) — the cached pointer is cleared so the UI drops back to
     * `Available` on the next state emission.
     */
    fun install(apkPath: String): Boolean {
        val file = File(apkPath)
        if (!file.exists()) {
            repo.markApkDownloaded("")
            return false
        }
        val uri: Uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(intent)
        return true
    }

    /**
     * Returns false on API 26+ when the user hasn't yet granted the per-app
     * "install unknown apps" permission. Always true on older APIs (covered by
     * the manifest declaration alone).
     */
    fun canRequestInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /**
     * Opens the system Settings page where the user grants install-unknown-apps
     * permission for our app. After they enable it, the back-button returns
     * them to Kofipod and they tap install again.
     */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent =
            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }

    companion object {
        internal const val UPDATES_DIR = "updates"
    }
}

private const val STREAM_CHUNK_BYTES: Int = 64 * 1024

// Tolerate 60s of zero-byte progress before declaring the stream dead. The shared
// HttpClient uses 15s — too tight for an APK download over flaky cellular where a
// brief tower handoff can stall reads for tens of seconds.
private const val SOCKET_INACTIVITY_TIMEOUT_MS: Long = 60_000L

// Slightly higher than the shared client's 10s — give marginal cellular a chance
// to complete the TCP handshake without paying the full request timeout.
private const val CONNECT_TIMEOUT_MS: Long = 15_000L

/**
 * Stream the APK at [UpdateInfo.apkUrl] into [dir], with HTTP Range resume across
 * call attempts. Returns the absolute path of the completed `.apk`.
 *
 * Resume protocol:
 * - If `kofipod-${info.version}.apk.partial` exists in [dir], we send
 *   `Range: bytes=<existing-size>-`. A `206 Partial Content` response means the server
 *   honored the range — we append to the partial. A `200 OK` means it didn't — we
 *   wipe the partial and restart cleanly.
 * - The partial file is **never deleted in any `catch` or `finally`** — this is the
 *   load-bearing invariant for resume. If the stream throws mid-read, the partial stays
 *   on disk; the next call reads `partial.length()` and resumes. The one place the
 *   partial *is* deleted up-front is the deliberate 200-restart branch (server returned
 *   `200 OK` to our `Range` request, signalling it ignored the range and is sending the
 *   full body), since appending fresh bytes onto an old partial would corrupt the file.
 *   That delete happens *before* writing begins, never as exception cleanup. To preserve
 *   this guarantee: do NOT introduce `partial.delete()` inside any `catch` or `finally`,
 *   and keep the partial→target rename strictly *after* `.execute { }` returns cleanly.
 *   A regression here silently breaks resume with no test-level signal (see test file's
 *   note on the Ktor `toInputStream()` cause-swallowing bridge that prevents a clean
 *   unit test of this contract).
 * - Any non-matching files in [dir] (e.g. an old-version partial or stale temp) are
 *   wiped at the start of each attempt so storage stays bounded.
 *
 * Timeout policy mirrors [com.kofikodr.kofipod.ai.buildAiHttpClient]: per-request
 * `requestTimeoutMillis = INFINITE` (otherwise the shared client's 15s wall-clock cap
 * would kill any APK over a few MB), with `socketTimeoutMillis = 60_000` to still
 * detect a truly dead connection.
 *
 * Top-level so unit tests can drive it with a `MockEngine`-backed [HttpClient] and a
 * `TemporaryFolder`-style [File] without constructing an Android [android.content.Context].
 */
internal suspend fun streamApkInto(
    httpClient: HttpClient,
    dir: File,
    info: UpdateInfo,
    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
): String {
    // info.version comes from a tag on the kofikodr/kofipod GitHub release, which we
    // control — but treat it as untrusted defensively. A `/` or `..` in the filename
    // would let a future supply-chain compromise escape `dir`.
    require(!info.version.contains('/') && !info.version.contains('\\') && !info.version.contains("..")) {
        "Unsafe characters in update version: ${info.version}"
    }
    val partial = File(dir, "kofipod-${info.version}.apk.partial")
    val target = File(dir, "kofipod-${info.version}.apk")
    dir.listFiles()
        ?.filter { it != partial && it != target }
        ?.forEach { it.delete() }

    val existingBytes = if (partial.exists()) partial.length() else 0L

    httpClient
        .prepareGet(info.apkUrl) {
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_INACTIVITY_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
            }
            if (existingBytes > 0L) header(HttpHeaders.Range, "bytes=$existingBytes-")
        }
        .execute { response ->
            if (response.status.value !in 200..299) {
                throw UpdateDownloadHttpException(response.status.value)
            }
            val resumed = existingBytes > 0L && response.status == HttpStatusCode.PartialContent
            if (!resumed && partial.exists()) {
                // Server ignored our Range (200 OK) or we had no partial to begin with —
                // start the file fresh so we don't write past stale bytes.
                partial.delete()
            }
            var downloaded = if (resumed) existingBytes else 0L
            val total = info.apkSizeBytes
            onProgress(downloaded, total)
            FileOutputStream(partial, resumed).use { out ->
                response.bodyAsChannel().toInputStream().use { input ->
                    val buffer = ByteArray(STREAM_CHUNK_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
        }

    // Promote partial → final only after the stream completed cleanly. If renameTo
    // fails (cross-filesystem, etc.) fall back to copy+delete so we never strand the
    // user without a usable file.
    if (target.exists()) target.delete()
    if (!partial.renameTo(target)) {
        partial.copyTo(target, overwrite = true)
        partial.delete()
    }
    return target.absolutePath
}

internal class UpdateDownloadHttpException(
    val statusCode: Int,
) : IllegalStateException("Update APK download failed with HTTP $statusCode")
