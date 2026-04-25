// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

actual class Notifier {
    actual fun postNewEpisodes(
        totalEpisodes: Int,
        totalShows: Int,
    ) { /* TODO UNUserNotificationCenter */ }

    actual fun postUpdateAvailable(version: String) { /* iOS doesn't sideload — no-op */ }
}
