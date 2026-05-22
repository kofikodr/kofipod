// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.domain

import kotlinx.serialization.Serializable

/**
 * Which search index a [PodcastSummary] came from. A summary may carry more than one
 * (e.g. both Podcast Index and iTunes returned the same feed), which the merger uses
 * as a quality signal when ranking.
 *
 * Persisted into [PodcastSummary] only at search time — once a podcast is subscribed
 * and lives in the DB, the source attribution is dropped (the row in `Podcast` has no
 * source column; everything in-library is treated equally).
 *
 * Lives in `domain/` because `PodcastSummary` (also a domain type) carries it as a
 * field; placing it in a data-layer package would make the domain depend on the data
 * layer.
 */
@Serializable
enum class SourceId {
    /** Podcast Index — the primary catalog. Default for everything in the DB. */
    PodcastIndex,

    /** Apple iTunes Search API — broader/faster-updating catalog, no auth required. */
    ITunes,
}
