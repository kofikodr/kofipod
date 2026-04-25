// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import app.kofipod.MainActivity
import app.kofipod.R

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
            if (mgr.getNotificationChannel(CHANNEL_ID_APP_UPDATES) == null) {
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

    actual fun postNewEpisodes(
        totalEpisodes: Int,
        totalShows: Int,
    ) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        val text = "from $totalShows show" + if (totalShows == 1) "" else "s"
        val notif =
            NotificationCompat.Builder(context, CHANNEL_ID_NEW_EPISODES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("$totalEpisodes new episodes")
                .setContentText(text)
                .setAutoCancel(true)
                .build()
        mgr.notify(NOTIFY_ID_NEW_EPISODES, notif)
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
                // requestCode =
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

    companion object {
        const val CHANNEL_ID_NEW_EPISODES = "kofipod.new_episodes"
        const val CHANNEL_ID_APP_UPDATES = "kofipod.app_updates"
        const val NOTIFY_ID_NEW_EPISODES = 42
        const val NOTIFY_ID_APP_UPDATE = 43
        const val EXTRA_OPEN_SETTINGS_FOR_UPDATE = "app.kofipod.extra.OPEN_SETTINGS_FOR_UPDATE"
    }
}
