// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.kofipod.data.repo.EpisodeSource
import app.kofipod.db.Download
import app.kofipod.db.KofipodDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlin.coroutines.CoroutineContext
import app.kofipod.db.EpisodeAiSummary as DbEpisodeAiSummary

/**
 * Tiny seam over the transcript fetch so [AiSummaryRepository] can be unit-tested
 * without standing up Ktor's MockEngine (which dispatches on its own engine
 * coroutine and doesn't compose cleanly with `runTest`'s scheduler). Production
 * implementation in [HttpTranscriptFetcher].
 */
fun interface TranscriptFetcher {
    /** Returns success(body) on 2xx, failure(AiErrorException(TranscriptUnavailable)) otherwise. */
    suspend fun fetch(url: String): Result<String>
}

/**
 * Seam over the per-episode download flow so [AiSummaryRepository] can choose
 * the audio source kind without taking a hard dependency on `DownloadRepository`
 * (which would balloon test fixtures). Production implementation: bind to
 * `DownloadRepository::forEpisodeFlow` in `CommonModule`.
 */
fun interface DownloadSource {
    fun forEpisodeFlow(episodeId: String): Flow<Download?>
}

/**
 * Orchestrates the BYOK summary pipeline for one episode at a time.
 *
 * Slice 2 covers the transcript path; Slice 2.5 adds the audio fallback for
 * downloaded episodes that lack a publisher transcript. Both paths run on the
 * long-lived `appScope` so navigating away does not cancel the request —
 * the persisted Flow reflects the result regardless of which screen is bound.
 *
 * **Logging discipline.** Failures here surface as [AiError] only. We never log
 * the prompt, the transcript body, the audio file path, the API key, or the
 * response body — the same rule [GeminiClient] follows.
 */
class AiSummaryRepository(
    private val db: KofipodDatabase,
    private val aiConfig: AiConfigRepository,
    private val summariser: TextSummariser,
    private val audio: AudioSummariser,
    private val transcripts: TranscriptFetcher,
    private val episodes: EpisodeSource,
    private val downloads: DownloadSource,
    private val appScope: CoroutineScope,
    private val clock: Clock = Clock.System,
    // Injected so tests can drive `clearAll()` on the test scheduler. Production
    // uses `Dispatchers.Default`, mirroring `SettingsRepository.flowContext`.
    private val ioContext: CoroutineContext = Dispatchers.Default,
    // Injected so call sites on iOS (where audio fallback isn't wired yet) can
    // skip the Audio branch in `pickSource`. Default reads from the platform.
    private val audioFallbackEnabled: Boolean = audioFallbackSupported(),
) {
    /** episodeId → in-flight source kind. A second `generate()` for the same id is a no-op. */
    private val inFlight = MutableStateFlow<Map<String, AiSourceKind>>(emptyMap())

    /** episodeId → most recent transient error, surfaced once and cleared on next run. */
    private val transientErrors = MutableStateFlow<Map<String, AiError>>(emptyMap())

    /**
     * Single-flight guard around the in-flight + DB write window. The repository is
     * reused across the app, so two coroutines on different screens calling
     * `generate(id)` simultaneously must not double-fire the network request.
     */
    private val generateLock = Mutex()

    fun observeFor(episodeId: String): Flow<AiSummaryUiState> {
        val cachedFlow: Flow<DbEpisodeAiSummary?> =
            db.episodeAiSummaryQueries.selectByEpisodeFlow(episodeId).asFlow().mapToOneOrNull(Dispatchers.Default)
        val episodeFlow = episodes.episodeFlow(episodeId)
        val downloadFlow = downloads.forEpisodeFlow(episodeId)
        val keyFlow = aiConfig.isKeyConfigured()
        val inFlightForId: Flow<AiSourceKind?> = inFlight.map { it[episodeId] }
        val errorForId: Flow<AiError?> = transientErrors.map { it[episodeId] }

        // `combine` tops out at 5 typed flows. Pre-zip the always-needed-together
        // signals (key + transient state) so we stay below that ceiling once the
        // download flow is added in Slice 2.5.
        val transientFlow: Flow<TransientState> =
            combine(keyFlow, inFlightForId, errorForId) { keyOk, runningKind, error ->
                TransientState(keyOk, runningKind, error)
            }

        return combine(transientFlow, episodeFlow, cachedFlow, downloadFlow) { transient, episode, cached, download ->
            if (!transient.keyOk) return@combine AiSummaryUiState.Hidden
            if (transient.runningKind != null) return@combine AiSummaryUiState.Generating(transient.runningKind)
            if (transient.error != null) return@combine AiSummaryUiState.Error(transient.error)
            val available = pickSource(episode, download)
            if (cached != null) {
                val mapped = cached.toDomain()
                val stale =
                    available != null &&
                        (mapped.sourceKind != available || !fingerprintMatches(episode, download, mapped))
                return@combine AiSummaryUiState.Ready(mapped, stale)
            }
            AiSummaryUiState.Idle(available)
        }.onCompletion {
            // Once the last subscriber for this episode goes away, drop the
            // transient error. Otherwise a stale `AiError.Network` from yesterday
            // would re-render the moment the user reopens the episode, even
            // though the connection has since recovered. The flag is rebuilt by
            // the next `generate()` call if the failure repeats.
            transientErrors.update { it - episodeId }
        }
    }

    /**
     * Non-suspending entry point. Kicks the pipeline on `appScope` and returns
     * immediately; UI tracks progress via [observeFor]. Idempotent per episodeId
     * while a job is in flight.
     */
    fun generate(episodeId: String) {
        appScope.launch { runGenerate(episodeId) }
    }

    /**
     * Wipes all cached summaries. Wired up by Slice 4 Disconnect. Routed through
     * [ioContext] (default `Dispatchers.Default`) so callers from the main
     * thread don't ANR on a sluggish on-device write — SQLDelight does not
     * enforce off-main I/O on its own.
     */
    suspend fun clearAll() {
        withContext(ioContext) {
            db.episodeAiSummaryQueries.deleteAll()
        }
    }

    // -----------------------------------------------------------------------
    // Pipeline
    // -----------------------------------------------------------------------

    private suspend fun runGenerate(episodeId: String) {
        // Acquire the lock for the eligibility check + in-flight registration as one
        // atomic step, AND capture the episode + download snapshot used for the
        // request body here. Reading either one again outside the lock would open
        // a TOCTOU window: the feed (or the user's downloads) could change between
        // the two reads, leaving us sending a request whose source no longer
        // matches the `pickSource()` decision the caller made. The actual network
        // call happens outside the lock so other episodes can summarise concurrently.
        val (source, episode, download) =
            generateLock.withLock {
                if (inFlight.value.containsKey(episodeId)) return
                val ep = episodes.episodeFlow(episodeId).first() ?: return
                val dl = downloads.forEpisodeFlow(episodeId).first()
                val available = pickSource(ep, dl) ?: return surface(episodeId, AiError.TranscriptUnavailable)
                inFlight.update { it + (episodeId to available) }
                transientErrors.update { it - episodeId }
                Triple(available, ep, dl)
            }

        try {
            when (source) {
                AiSourceKind.Transcript -> runTranscript(episode.id, episode.transcriptUrl.orEmpty())
                AiSourceKind.Audio -> runAudio(episode, download)
            }
        } finally {
            inFlight.update { it - episodeId }
        }
    }

    private suspend fun runTranscript(
        episodeId: String,
        transcriptUrl: String,
    ) {
        if (transcriptUrl.isBlank()) {
            surface(episodeId, AiError.TranscriptUnavailable)
            return
        }
        val key = aiConfig.currentKey()
        if (key.isNullOrBlank()) {
            surface(episodeId, AiError.NoKey)
            return
        }
        val model = aiConfig.model().first()

        val transcriptText: String =
            transcripts.fetch(transcriptUrl).getOrElse { throwable ->
                surface(episodeId, throwable.toAiError())
                return
            }

        val prompt = AiPrompts.episodeSummaryPrompt(localeTag = currentLocaleTag())
        val summary: String =
            summariser.generateFromText(
                apiKey = key,
                model = model,
                prompt = prompt,
                content = transcriptText,
            ).getOrElse { throwable ->
                surface(episodeId, throwable.toAiError())
                return
            }

        db.episodeAiSummaryQueries.upsert(
            episodeId = episodeId,
            generatedAtMs = clock.now().toEpochMilliseconds(),
            modelId = model.apiId,
            sourceKind = AiSourceKind.Transcript.wire,
            sourceFingerprint = transcriptUrl,
            summary = summary,
            peopleJson = "[]",
            thingsJson = "[]",
            linksJson = "[]",
        )
        // Persisted successfully — drop any error left over from a previous
        // failed attempt for this episode so the UI doesn't briefly flash the
        // old error card before the cached summary lands.
        transientErrors.update { it - episodeId }
    }

    private suspend fun runAudio(
        episode: app.kofipod.db.Episode,
        download: Download?,
    ) {
        val episodeId = episode.id
        // Soft cap from the spec — we'd rather fail fast here than spend 30s
        // uploading a 12-hour episode only to have Gemini reject it for
        // exceeding the context window. Keeps the user's data + battery budget
        // intact, and the dedicated AudioTooLong copy is clearer than the
        // generic "Unknown" retry card.
        if (episode.durationSec > AUDIO_MAX_DURATION_SEC) {
            surface(episodeId, AiError.AudioTooLong)
            return
        }
        val localPath = download?.localPath?.takeIf { it.isNotBlank() }
        if (localPath == null) {
            // Should be unreachable — pickSource only returns Audio when the
            // download has a local path — but guard against the race between
            // the snapshot and a download that gets deleted moments later.
            surface(episodeId, AiError.Unknown(null))
            return
        }
        val key = aiConfig.currentKey()
        if (key.isNullOrBlank()) {
            surface(episodeId, AiError.NoKey)
            return
        }
        val model = aiConfig.model().first()

        val mimeType = episode.enclosureMimeType.ifBlank { DEFAULT_AUDIO_MIME }
        val sizeBytes = download.downloadedBytes
        val prompt = AiPrompts.episodeSummaryPrompt(localeTag = currentLocaleTag())

        val summary: String =
            audio.summariseAudio(
                apiKey = key,
                model = model,
                prompt = prompt,
                localPath = localPath,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                displayName = "kofipod-$episodeId",
            ).getOrElse { throwable ->
                surface(episodeId, throwable.toAiError())
                return
            }

        db.episodeAiSummaryQueries.upsert(
            episodeId = episodeId,
            generatedAtMs = clock.now().toEpochMilliseconds(),
            modelId = model.apiId,
            sourceKind = AiSourceKind.Audio.wire,
            // Decimal byte count per spec — used by [fingerprintMatches] so a
            // re-downloaded (potentially re-encoded) episode invalidates the
            // cache and prompts a regenerate.
            sourceFingerprint = sizeBytes.toString(),
            summary = summary,
            peopleJson = "[]",
            thingsJson = "[]",
            linksJson = "[]",
        )
        transientErrors.update { it - episodeId }
    }

    private fun surface(
        episodeId: String,
        error: AiError,
    ) {
        transientErrors.update { it + (episodeId to error) }
    }

    private fun pickSource(
        episode: app.kofipod.db.Episode?,
        download: Download?,
    ): AiSourceKind? {
        val transcript = episode?.transcriptUrl?.takeIf { it.isNotBlank() }
        val audioReady =
            audioFallbackEnabled &&
                download != null &&
                download.state == DOWNLOAD_STATE_COMPLETED &&
                !download.localPath.isNullOrBlank()
        return when {
            transcript != null -> AiSourceKind.Transcript
            audioReady -> AiSourceKind.Audio
            else -> null
        }
    }

    private fun fingerprintMatches(
        episode: app.kofipod.db.Episode?,
        download: Download?,
        cached: AiSummary,
    ): Boolean =
        when (cached.sourceKind) {
            AiSourceKind.Transcript -> cached.sourceFingerprint == (episode?.transcriptUrl ?: "")
            // Re-downloaded files (different bitrate, partial repair, etc.) get
            // a new byte count, which invalidates the cached summary so the
            // panel shows the "Source updated" stale chip + Regenerate button.
            AiSourceKind.Audio -> cached.sourceFingerprint == download?.downloadedBytes?.toString()
        }

    private data class TransientState(
        val keyOk: Boolean,
        val runningKind: AiSourceKind?,
        val error: AiError?,
    )

    private companion object {
        // Soft cap aligned with the spec — Gemini's actual context window
        // varies by model, but 8h is a safe headroom across Flash variants
        // and matches the user-facing "8 hours" copy in the AudioTooLong
        // error card.
        const val AUDIO_MAX_DURATION_SEC = 8L * 3600

        // Falls through when the episode's RSS enclosure doesn't declare a
        // type. Most podcast feeds do, but a misconfigured one shouldn't
        // block summarisation.
        const val DEFAULT_AUDIO_MIME = "audio/mpeg"

        // Mirrors `Download.state` writes from `DownloadRepository` (which
        // mirrors `DownloadProgress.State.Completed`). A partial download is
        // not summarisable.
        const val DOWNLOAD_STATE_COMPLETED = "Completed"
    }

    private fun DbEpisodeAiSummary.toDomain(): AiSummary =
        AiSummary(
            episodeId = episodeId,
            generatedAtMs = generatedAtMs,
            modelId = modelId,
            sourceKind = AiSourceKind.fromWire(sourceKind) ?: AiSourceKind.Transcript,
            sourceFingerprint = sourceFingerprint,
            summary = summary,
            // Slice 3 parses the JSON arrays; Slice 2 ships them empty.
            people = emptyList(),
            things = emptyList(),
            links = emptyList(),
        )
}
