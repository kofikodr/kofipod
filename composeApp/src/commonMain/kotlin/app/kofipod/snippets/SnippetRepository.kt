// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.kofipod.db.KofipodDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.random.Random

class SnippetRepository(private val db: KofipodDatabase) {

    /**
     * Create a "snip last 60s" draft from current player state. Returns the
     * generated id. Title defaults to `"<episode title> — <mm:ss.s start>"`
     * for fast user identification; user can edit in the editor.
     *
     * Caller is responsible for ensuring [episodeId] / [podcastId] exist (FK).
     */
    fun createDraftFromPlayer(
        episodeId: String,
        podcastId: String,
        playerPositionMs: Long,
        episodeDurationMs: Long,
        episodeTitle: String,
        nowMs: Long,
    ): String {
        val window = SnippetWindow.computeLast60sWindow(playerPositionMs, episodeDurationMs)
        val id = generateId(nowMs)
        val defaultTitle = "$episodeTitle — ${SnippetWindow.formatTimestampDeci(window.startMs)}"
        db.snippetQueries.insert(
            id = id,
            episodeId = episodeId,
            podcastId = podcastId,
            startMs = window.startMs,
            endMs = window.endMs,
            title = defaultTitle,
            captionOverride = null,
            createdAtMs = nowMs,
            lastExportFormat = null,
            lastExportPath = null,
        )
        return id
    }

    /**
     * Update trim with optional clamping. If [durationMs] is supplied, the
     * pair is run through [SnippetWindow.clampWindow] before write — the
     * editor calls this overload. The unclamped overload is for tests and
     * for callers that have already validated the pair.
     */
    fun updateTrim(id: String, startMs: Long, endMs: Long, durationMs: Long) {
        val w = SnippetWindow.clampWindow(startMs, endMs, durationMs)
        db.snippetQueries.updateTrim(w.startMs, w.endMs, id)
    }

    fun updateTrim(id: String, startMs: Long, endMs: Long) {
        db.snippetQueries.updateTrim(startMs, endMs, id)
    }

    fun updateTitle(id: String, title: String?) =
        db.snippetQueries.updateTitle(title?.takeIf { it.isNotBlank() }, id)

    fun updateCaptionOverride(id: String, captionOverride: String?) =
        db.snippetQueries.updateCaptionOverride(captionOverride?.takeIf { it.isNotBlank() }, id)

    fun setRendered(id: String, format: SnippetFormat, path: String) =
        db.snippetQueries.setRendered(format.wire, path, id)

    fun deleteById(id: String) = db.snippetQueries.deleteById(id)

    suspend fun selectById(id: String): Snippet? =
        db.snippetQueries.selectById(id).asFlow().mapToOneOrNull(Dispatchers.Default).first()
            ?.let(::toDomain)

    fun observeForEpisode(episodeId: String): Flow<List<Snippet>> =
        db.snippetQueries.selectByEpisode(episodeId).asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows -> rows.map(::toDomain) }
            .flowOn(Dispatchers.Default)

    fun observeAllWithContext(): Flow<List<SnippetWithContext>> =
        db.snippetQueries.selectAllWithContext().asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows ->
                rows.map { row ->
                    SnippetWithContext(
                        snippet = Snippet(
                            id = row.id,
                            episodeId = row.episodeId,
                            podcastId = row.podcastId,
                            startMs = row.startMs,
                            endMs = row.endMs,
                            title = row.title,
                            captionOverride = row.captionOverride,
                            createdAtMs = row.createdAtMs,
                            lastExportFormat = SnippetFormat.fromWire(row.lastExportFormat),
                            lastExportPath = row.lastExportPath,
                        ),
                        episodeTitle = row.episodeTitle,
                        podcastTitle = row.podcastTitle,
                        artworkUrl = row.artworkUrl,
                    )
                }
            }
            .flowOn(Dispatchers.Default)

    private fun toDomain(row: app.kofipod.db.Snippet): Snippet = Snippet(
        id = row.id,
        episodeId = row.episodeId,
        podcastId = row.podcastId,
        startMs = row.startMs,
        endMs = row.endMs,
        title = row.title,
        captionOverride = row.captionOverride,
        createdAtMs = row.createdAtMs,
        lastExportFormat = SnippetFormat.fromWire(row.lastExportFormat),
        lastExportPath = row.lastExportPath,
    )

    private fun generateId(nowMs: Long): String {
        val rand = Random.nextLong(0L, Long.MAX_VALUE)
        return "snip-" + nowMs.toString(36) + "-" + rand.toString(36).takeLast(8)
    }
}
