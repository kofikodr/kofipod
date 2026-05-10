// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.diagnostics

sealed class TelemetryEvent(val name: String, val props: Map<String, String>) {
    /**
     * Cold-start session marker. Carries [proSource] so analytics queries can
     * filter sessions by Pro tier — most importantly, exclude reviewer-unlocked
     * sessions from "real Pro user" cohorts and conversion funnels.
     *
     * The source is read from the cached entitlement, not the live billing
     * answer, so the value reflects what the user experienced in this session.
     */
    data class AppOpened(val proSource: ProSourceTag) :
        TelemetryEvent("app_opened", mapOf("pro_source" to proSource.value))

    /**
     * Fired when a hidden reviewer-unlock code is accepted in Settings. Emits
     * even on repeat submissions; Aptabase aggregates server-side. Combined
     * with `app_opened.pro_source = reviewer_unlock` it tells you both that
     * a reviewer is in the app and that they completed the unlock flow.
     */
    object ReviewerUnlockApplied : TelemetryEvent("reviewer_unlock_applied", emptyMap())

    data class SearchPerformed(val source: SearchSource) :
        TelemetryEvent("search_performed", mapOf("source" to source.value))

    object EpisodeDownloaded : TelemetryEvent("episode_downloaded", emptyMap())

    object EpisodePlayed : TelemetryEvent("episode_played", emptyMap())

    data class AiSummaryGenerated(val path: AiPath) :
        TelemetryEvent("ai_summary_generated", mapOf("path" to path.value))

    data class AiDiscussMessageSent(val path: AiPath) :
        TelemetryEvent("ai_discuss_message_sent", mapOf("path" to path.value))
}

enum class SearchSource(val value: String) {
    TYPED("typed"),
    CATEGORY("category"),
}

enum class AiPath(val value: String) {
    TRANSCRIPT("transcript"),
    AUDIO("audio"),
}

/**
 * Wire-format Pro tier tag for analytics. `Unknown` means the entitlement
 * cache hadn't been hydrated by the time the event fired (rare — only on a
 * device whose first cold start hasn't yet written to prefs).
 */
enum class ProSourceTag(val value: String) {
    UNKNOWN("unknown"),
    FREE("free"),
    INDIVIDUAL("individual"),
    FOSS("foss"),
    REVIEWER_UNLOCK("reviewer_unlock"),
}
