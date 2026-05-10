// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

/**
 * Discriminator for [com.kofikodr.kofipod.db.PendingAiOperation] rows. Each value's
 * [wire] string is what lands in the table's `kind` column — pinned as a
 * constant per variant so a typo at a single call site can't silently
 * orphan markers in a no-one-drains-them state.
 *
 *  - [Summary]       — written by [AiSummaryRepository.generate] before the
 *    pipeline reaches its first network suspension point. Worker drains via
 *    [AiSummaryRepository.resumePending], which re-fires the full pipeline.
 *  - [DiscussUpload] — written by [DiscussRepository] before an audio upload
 *    starts. Recovery is intentionally light: the worker just clears stale
 *    rows on next launch via [DiscussRepository.cleanStaleDiscussUploads].
 *    Re-firing a chat send minutes later, with the user no longer watching,
 *    would be jarring; the user retries by tapping send.
 */
enum class PendingOperationKind(val wire: String) {
    Summary("summary"),
    DiscussUpload("discuss_upload"),
    ;

    companion object {
        fun fromWire(value: String): PendingOperationKind? = entries.firstOrNull { it.wire == value }
    }
}
