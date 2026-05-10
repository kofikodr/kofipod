// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.repo

import com.kofikodr.kofipod.data.api.PodcastIndexApi
import com.kofikodr.kofipod.domain.PodcastSummary
import com.kofikodr.kofipod.domain.toSummary

interface DiscoverySource {
    suspend fun trending(limit: Int = DEFAULT_TRENDING_LIMIT): List<PodcastSummary>

    companion object {
        const val DEFAULT_TRENDING_LIMIT = 24
    }
}

class DiscoveryRepository(private val api: PodcastIndexApi) : DiscoverySource {
    override suspend fun trending(limit: Int): List<PodcastSummary> = api.trending(limit = limit).map { it.toSummary() }
}
