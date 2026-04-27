// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.kofipod.data.repo.EpisodeSource
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
 * Orchestrates the BYOK summary pipeline for one episode at a time.
 *
 * Slice 2 only handles the transcript path; the audio path is a stub that emits
 * `Idle(available = null)` and is filled in by Slice 2.5. The whole pipeline runs
 * on the long-lived `appScope` so navigating away does not cancel the request —
 * the persisted Flow reflects the result regardless of which screen is bound.
 *
 * **Logging discipline.** Failures here surface as [AiError] only. We never log
 * the prompt, the transcript body, the API key, or the response body — the same
 * rule [GeminiClient] follows.
 */
class AiSummaryRepository(
    private val db: KofipodDatabase,
    private val aiConfig: AiConfigRepository,
    private val summariser: TextSummariser,
    private val transcripts: TranscriptFetcher,
    private val episodes: EpisodeSource,
    private val appScope: CoroutineScope,
    private val clock: Clock = Clock.System,
    // Injected so tests can drive `clearAll()` on the test scheduler. Production
    // uses `Dispatchers.Default`, mirroring `SettingsRepository.flowContext`.
    private val ioContext: CoroutineContext = Dispatchers.Default,
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
        val keyFlow = aiConfig.isKeyConfigured()
        val inFlightForId: Flow<AiSourceKind?> = inFlight.map { it[episodeId] }
        val errorForId: Flow<AiError?> = transientErrors.map { it[episodeId] }

        return combine(keyFlow, episodeFlow, cachedFlow, inFlightForId, errorForId) { keyOk, episode, cached, runningKind, error ->
            if (!keyOk) return@combine AiSummaryUiState.Hidden
            if (runningKind != null) return@combine AiSummaryUiState.Generating(runningKind)
            if (error != null) return@combine AiSummaryUiState.Error(error)
            val available = pickSource(episode)
            if (cached != null) {
                val mapped = cached.toDomain()
                val stale = available != null && (mapped.sourceKind != available || !fingerprintMatches(episode, mapped))
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
        // atomic step, AND capture the episode snapshot used for the request body
        // here. Reading the episode again outside the lock would open a TOCTOU
        // window: the feed could refresh between the two reads, leaving us
        // sending a request keyed to a `transcriptUrl` that no longer matches the
        // `pickSource()` decision the caller made. The actual network call happens
        // outside the lock so other episodes can summarise concurrently.
        val (source, episode) =
            generateLock.withLock {
                if (inFlight.value.containsKey(episodeId)) return
                val ep = episodes.episodeFlow(episodeId).first() ?: return
                val available = pickSource(ep) ?: return surface(episodeId, AiError.TranscriptUnavailable)
                inFlight.update { it + (episodeId to available) }
                transientErrors.update { it - episodeId }
                available to ep
            }

        try {
            when (source) {
                AiSourceKind.Transcript -> runTranscript(episode.id, episode.transcriptUrl.orEmpty())
                AiSourceKind.Audio -> {
                    // Slice 2.5 fills this in.
                    surface(episodeId, AiError.Unknown(null))
                }
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

    private fun surface(
        episodeId: String,
        error: AiError,
    ) {
        transientErrors.update { it + (episodeId to error) }
    }

    private fun pickSource(episode: app.kofipod.db.Episode?): AiSourceKind? {
        val transcript = episode?.transcriptUrl?.takeIf { it.isNotBlank() }
        return when {
            transcript != null -> AiSourceKind.Transcript
            else -> null // Slice 2.5 widens this branch when a downloaded audio file is available.
        }
    }

    private fun fingerprintMatches(
        episode: app.kofipod.db.Episode?,
        cached: AiSummary,
    ): Boolean =
        when (cached.sourceKind) {
            AiSourceKind.Transcript -> cached.sourceFingerprint == (episode?.transcriptUrl ?: "")
            AiSourceKind.Audio -> true // Slice 2.5 will compare against the local file's byte count.
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
