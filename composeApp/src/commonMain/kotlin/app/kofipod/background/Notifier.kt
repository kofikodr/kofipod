// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

expect class Notifier {
    fun postNewEpisodes(
        totalEpisodes: Int,
        totalShows: Int,
    )

    fun postUpdateAvailable(version: String)
}
