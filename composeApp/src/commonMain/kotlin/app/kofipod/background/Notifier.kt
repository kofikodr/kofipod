// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

expect class Notifier {
    suspend fun postSingleNewEpisode(
        podcastTitle: String,
        episodeTitle: String,
        episodeId: String,
        artworkUrl: String?,
    )

    fun postManyNewEpisodes(
        totalEpisodes: Int,
        totalShows: Int,
    )
}
