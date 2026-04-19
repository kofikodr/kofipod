// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.repo

import app.kofipod.data.api.PodcastIndexApi
import app.kofipod.domain.PodcastSummary
import app.kofipod.domain.toSummary

interface DiscoverySource {
    suspend fun trending(limit: Int = DEFAULT_TRENDING_LIMIT): List<PodcastSummary>

    companion object {
        const val DEFAULT_TRENDING_LIMIT = 24
    }
}

class DiscoveryRepository(private val api: PodcastIndexApi) : DiscoverySource {
    override suspend fun trending(limit: Int): List<PodcastSummary> = api.trending(limit = limit).map { it.toSummary() }
}
