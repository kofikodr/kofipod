// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

actual class Notifier(private val context: Context) {
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "New episodes",
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
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("$totalEpisodes new episodes")
                .setContentText(text)
                .setAutoCancel(true)
                .build()
        mgr.notify(NOTIFY_ID, notif)
    }

    companion object {
        const val CHANNEL_ID = "kofipod.new_episodes"
        const val NOTIFY_ID = 42
    }
}
