// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import app.kofipod.data.repo.UpdateRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
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
            // Wipe any old APKs to keep storage bounded; the previous version is no longer useful.
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "kofipod-${info.version}.apk")

            httpClient.prepareGet(info.apkUrl).execute { response ->
                val total = info.apkSizeBytes
                var downloaded = 0L
                val buffer = ByteArray(STREAM_CHUNK_BYTES)
                response.bodyAsChannel().toInputStream().use { input ->
                    FileOutputStream(target).use { out ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                        out.flush()
                    }
                }
            }

            repo.markApkDownloaded(target.absolutePath)
            target.absolutePath
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
        private const val UPDATES_DIR = "updates"
        private const val STREAM_CHUNK_BYTES: Int = 64 * 1024
    }
}
