// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.background

actual class Notifier {
    actual suspend fun postSingleNewEpisode(
        podcastTitle: String,
        episodeTitle: String,
        episodeId: String,
        artworkUrl: String?,
    ) { /* TODO UNUserNotificationCenter */ }

    actual fun postManyNewEpisodes(
        totalEpisodes: Int,
        totalShows: Int,
    ) { /* TODO UNUserNotificationCenter */ }

    actual fun postUpdateAvailable(version: String) { /* iOS doesn't sideload — no-op */ }
}
