// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.kofikodr.kofipod.EXTRA_OPEN_EPISODE_ID
import com.kofikodr.kofipod.EXTRA_OPEN_LIBRARY
import com.kofikodr.kofipod.MainActivity
import com.kofikodr.kofipod.R
import com.kofikodr.kofipod.update.UpdaterCapability

actual class Notifier(private val context: Context) {
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID_NEW_EPISODES) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID_NEW_EPISODES,
                        "New episodes",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
            }
            // Only register the App-updates channel when the sideload updater is
            // enabled — otherwise a Play Store flavor would surface a misleading
            // "App updates" entry in the system notification settings UI.
            if (UpdaterCapability.enabled && mgr.getNotificationChannel(CHANNEL_ID_APP_UPDATES) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID_APP_UPDATES,
                        "App updates",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
            }
        }
    }

    actual suspend fun postSingleNewEpisode(
        podcastTitle: String,
        episodeTitle: String,
        episodeId: String,
        artworkUrl: String?,
    ) {
        val largeIcon = artworkUrl?.takeIf { it.isNotBlank() }?.let { fetchBitmap(it) }
        val tapIntent =
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_OPEN_EPISODE_ID, episodeId)
            }
        val pi =
            PendingIntent.getActivity(
                context,
                REQ_CODE_SINGLE,
                tapIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notif =
            NotificationCompat.Builder(context, CHANNEL_ID_NEW_EPISODES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Ep.$episodeTitle")
                .setContentText(podcastTitle)
                .setStyle(NotificationCompat.BigTextStyle().bigText(podcastTitle))
                .also { if (largeIcon != null) it.setLargeIcon(largeIcon) }
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        NotificationManagerCompat.from(context).notify(NOTIFY_ID_NEW_EPISODES, notif)
    }

    actual fun postManyNewEpisodes(
        totalEpisodes: Int,
        totalShows: Int,
    ) {
        val text = "from $totalShows show" + if (totalShows == 1) "" else "s"
        val tapIntent =
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_OPEN_LIBRARY, true)
            }
        val pi =
            PendingIntent.getActivity(
                context,
                REQ_CODE_MANY,
                tapIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notif =
            NotificationCompat.Builder(context, CHANNEL_ID_NEW_EPISODES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("$totalEpisodes new episodes")
                .setContentText(text)
                .also { b -> launcherBitmap()?.let { b.setLargeIcon(it) } }
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        NotificationManagerCompat.from(context).notify(NOTIFY_ID_NEW_EPISODES, notif)
    }

    actual fun postUpdateAvailable(version: String) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        val tapIntent =
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_OPEN_SETTINGS_FOR_UPDATE, true)
            }
        val pending =
            PendingIntent.getActivity(
                context,
                NOTIFY_ID_APP_UPDATE,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notif =
            NotificationCompat.Builder(context, CHANNEL_ID_APP_UPDATES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Kofipod $version available")
                .setContentText("Tap to download and install")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        mgr.notify(NOTIFY_ID_APP_UPDATE, notif)
    }

    private suspend fun fetchBitmap(url: String): Bitmap? =
        runCatching {
            val loader = SingletonImageLoader.get(context)
            val request =
                ImageRequest
                    .Builder(context)
                    .data(url)
                    // allowHardware(false): system_server serializes the large icon over Binder, so
                    // a Config.HARDWARE bitmap throws. size(...): podcast covers are typically
                    // 3000×3000 — without a target size, Coil decodes at native resolution and
                    // burns unnecessary memory in the worker process for an icon the system caps
                    // anyway.
                    .size(LARGE_ICON_TARGET_PX, LARGE_ICON_TARGET_PX)
                    .allowHardware(false)
                    .build()
            val result = loader.execute(request)
            if (result !is SuccessResult) return@runCatching null
            (result.image as? BitmapImage)?.bitmap
        }.getOrNull()

    // Renders the (adaptive) launcher mipmap into a square bitmap so it can be used as
    // the notification large icon. Adaptive icons require explicit bounds + a Canvas
    // pass — there's no public Bitmap factory for them.
    private fun launcherBitmap(): Bitmap? =
        runCatching {
            val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher) ?: return null
            val bitmap = Bitmap.createBitmap(LAUNCHER_BITMAP_PX, LAUNCHER_BITMAP_PX, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, LAUNCHER_BITMAP_PX, LAUNCHER_BITMAP_PX)
            drawable.draw(canvas)
            bitmap
        }.getOrNull()

    companion object {
        const val CHANNEL_ID_NEW_EPISODES = "kofipod.new_episodes"
        const val CHANNEL_ID_APP_UPDATES = "kofipod.app_updates"
        const val NOTIFY_ID_NEW_EPISODES = 42
        const val NOTIFY_ID_APP_UPDATE = 43
        const val EXTRA_OPEN_SETTINGS_FOR_UPDATE = "com.kofikodr.kofipod.extra.OPEN_SETTINGS_FOR_UPDATE"
        private const val REQ_CODE_SINGLE = 100
        private const val REQ_CODE_MANY = 101
        private const val LAUNCHER_BITMAP_PX = 192
        private const val LARGE_ICON_TARGET_PX = 192
    }
}
