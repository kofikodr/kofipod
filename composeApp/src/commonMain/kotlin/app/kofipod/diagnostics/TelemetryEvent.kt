// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

sealed class TelemetryEvent(val name: String, val props: Map<String, String>) {
    object AppOpened : TelemetryEvent("app_opened", emptyMap())

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
