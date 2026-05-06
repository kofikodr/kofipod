// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.kofipod.background.AiSummaryScheduler
import app.kofipod.data.repo.EpisodeSource
import app.kofipod.db.Download
import app.kofipod.db.KofipodDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
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
    // Owns the upload-or-reuse-cached decision. Shared with [DiscussRepository]
    // so a Summary-side upload survives long enough for a Discuss session on
    // the same episode to skip re-uploading.
    private val coordinator: AudioUploadCoordinator,
    // Narrow seam over `client.generateFromAudio`. The full upload pipeline
    // is the coordinator's job; this seam is only the structured-summary
    // call against an already-active file URI.
    private val audio: AudioSummariser,
    private val transcripts: TranscriptFetcher,
    private val episodes: EpisodeSource,
    private val downloads: DownloadSource,
    private val appScope: CoroutineScope,
    // Schedules the out-of-process resume worker (Android). iOS impl is a no-op
    // — that platform's only resume mechanism is `resumePending()` running at
    // app start.
    private val scheduler: AiSummaryScheduler,
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

    /**
     * episodeId → live stage + payload size for the in-flight pipeline. Drives
     * the staged progress card. Cleared when the job finishes (success, error,
     * or cancellation).
     */
    private val progress = MutableStateFlow<Map<String, GenerationProgress>>(emptyMap())

    /** episodeId → most recent transient error, surfaced once and cleared on next run. */
    private val transientErrors = MutableStateFlow<Map<String, AiError>>(emptyMap())

    /**
     * episodeId → live coroutine `Job` for the in-flight pipeline so [clearAll]
     * can cancel + drain them on Disconnect. Without this, a long audio upload
     * that finishes after `clearAll()` would land an `upsert(...)` against the
     * just-wiped table and the row would resurface the next time the user
     * connects with a different key.
     */
    private val activeJobs = MutableStateFlow<Map<String, Job>>(emptyMap())

    /**
     * Single-flight guard around the in-flight + DB write window. The repository is
     * reused across the app, so two coroutines on different screens calling
     * `generate(id)` simultaneously must not double-fire the network request.
     */
    private val generateLock = Mutex()

    /**
     * Read-only projection of the cached summary, independent of key state or
     * in-flight pipeline. Consumed by [DiscussRepository] (via the
     * [SummarySource] seam) to derive episode-specific suggestion prompts —
     * we want the entity lists even when the user is mid-session and
     * [observeFor] would render Generating/Idle.
     */
    fun cachedFor(episodeId: String): Flow<AiSummary?> =
        db.episodeAiSummaryQueries
            .selectByEpisodeFlow(episodeId)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { row -> row?.toDomain() }

    fun observeFor(episodeId: String): Flow<AiSummaryUiState> {
        val cachedFlow: Flow<DbEpisodeAiSummary?> =
            db.episodeAiSummaryQueries.selectByEpisodeFlow(episodeId).asFlow().mapToOneOrNull(Dispatchers.Default)
        val episodeFlow = episodes.episodeFlow(episodeId)
        val downloadFlow = downloads.forEpisodeFlow(episodeId)
        val keyFlow = aiConfig.isKeyConfigured()
        val inFlightForId: Flow<AiSourceKind?> = inFlight.map { it[episodeId] }
        val errorForId: Flow<AiError?> = transientErrors.map { it[episodeId] }
        val progressForId: Flow<GenerationProgress?> = progress.map { it[episodeId] }

        // `combine` tops out at 5 typed flows. Pre-zip the always-needed-together
        // signals (key + transient state + progress) so we stay below that
        // ceiling alongside the episode / cached / download flows.
        val transientFlow: Flow<TransientState> =
            combine(keyFlow, inFlightForId, errorForId, progressForId) { keyOk, runningKind, error, runningProgress ->
                TransientState(keyOk, runningKind, error, runningProgress)
            }

        return combine(transientFlow, episodeFlow, cachedFlow, downloadFlow) { transient, episode, cached, download ->
            if (!transient.keyOk) return@combine AiSummaryUiState.Hidden
            if (transient.runningKind != null) {
                val live = transient.runningProgress
                return@combine AiSummaryUiState.Generating(
                    sourceKind = transient.runningKind,
                    stage = live?.stage ?: GenerationStage.Preparing,
                    sizeBytes = live?.sizeBytes,
                )
            }
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
        // Schedule the out-of-process backstop FIRST. On Android this enqueues
        // a unique WorkManager job (KEEP policy → bursts collapse to one); on
        // iOS it's a no-op. The actual marker write happens inside the launch
        // below to keep the caller (typically a Compose tap handler on the
        // main thread) off SQLDelight's synchronous I/O — a process death
        // between this call and the first dispatch is microseconds wide and
        // dwarfed by the kill window during the 30s+ upload itself.
        scheduler.enqueueResume()
        launchGenerate(episodeId, writeMarker = true)
    }

    /**
     * Common launch path used by both [generate] (foreground tap) and
     * [resumePending] (worker / app-init drain). Critically, both routes
     * register the launched job in [activeJobs] so [clearAll] can cancel it
     * — without this, a worker-driven `runGenerate` running mid-Disconnect
     * would proceed to upsert a row keyed to the just-revoked vault.
     */
    private fun launchGenerate(
        episodeId: String,
        writeMarker: Boolean,
    ): Job {
        val job =
            appScope.launch {
                if (writeMarker) {
                    // Off-main marker write. Persists the intent before the
                    // pipeline reaches its first network suspension point so a
                    // mid-upload process death still leaves a recoverable row.
                    withContext(ioContext) {
                        db.pendingAiOperationQueries.upsert(
                            episodeId = episodeId,
                            kind = PendingOperationKind.Summary.wire,
                            requestedAtMs = clock.now().toEpochMilliseconds(),
                        )
                    }
                }
                runGenerate(episodeId)
            }
        // Register the completion handler BEFORE adding to activeJobs. If we
        // did it the other way around, a fast-completing job (e.g. a pre-empted
        // dispatcher under load, or an unconfined-dispatcher test path) could
        // fire `invokeOnCompletion` before the put-to-map ran — the cleanup
        // would remove a key that hadn't been written yet, leaving a permanent
        // ghost entry that survives subsequent generate/cancel cycles.
        job.invokeOnCompletion { activeJobs.update { it - episodeId } }
        activeJobs.update { it + (episodeId to job) }
        return job
    }

    /**
     * Non-suspending fire-and-forget variant of [resumePending] for app-startup
     * call sites that don't have a coroutine context handy. Launches on the
     * shared `appScope` so the work outlives the calling context. Kicked from
     * `KofipodApplication.onCreate` on every cold start so an iOS process
     * (or an Android cold start where the worker hasn't fired yet) recovers
     * any markers left by the previous run.
     */
    fun resumePendingAsync() {
        appScope.launch { resumePending() }
    }

    /**
     * Drains every [app.kofipod.db.PendingAiSummary] marker — the resume entry
     * point used by both [AiSummaryWorker] (Android, on process restart while
     * the app was killed) and the on-init resume hook (every platform, on
     * fresh app launch).
     *
     * Single-flight semantics inherited from [runGenerate]: if an appScope
     * job is already running for the same episodeId, this call is a cheap
     * no-op for that id. Errors are surfaced through the existing transient
     * error channel — the worker itself returns success regardless, since
     * auto-retrying a `KeyInvalid` or `RateLimited` would burn quota silently.
     *
     * Suspends until every pending pipeline has completed (or short-circuited)
     * so the WorkManager runtime knows when to release wake locks.
     */
    suspend fun resumePending() {
        // Read the markers off-main — the queries are tiny but SQLDelight
        // doesn't enforce off-main I/O on its own. Filter to Summary kind so
        // the worker doesn't try to re-fire DiscussUpload markers (those
        // belong to [DiscussRepository.cleanStaleDiscussUploads]).
        val pending =
            withContext(ioContext) {
                db.pendingAiOperationQueries
                    .selectByKind(PendingOperationKind.Summary.wire)
                    .executeAsList()
            }
        // Run sequentially rather than in parallel: most users have one
        // in-flight summary at a time, and parallelising over multiple keys
        // worth of audio uploads would burn the user's metered budget faster
        // than they expect. The worker itself isn't time-critical.
        //
        // Each iteration goes through `launchGenerate` so the resulting job
        // lands in `activeJobs`. Without that, a worker-driven pipeline that
        // overlaps with a user-initiated `clearAll()` (Disconnect) would not
        // be cancellable — the in-pipeline `currentKey()` re-check is the
        // last-line defence, but cooperative cancellation is what makes that
        // check reliable.
        for (row in pending) {
            launchGenerate(row.episodeId, writeMarker = false).join()
        }
    }

    /**
     * Cancels the in-flight pipeline for [episodeId], if any. Safe to call when
     * nothing is running. The cancelled job's `finally` clears `inFlight` and
     * `progress` so the panel falls back to Idle.
     *
     * Unlike [clearAll], this does not touch the cached-summary table — a user
     * cancelling a regenerate keeps any prior cached summary intact, which is
     * what they expect (cancel ≠ disconnect).
     */
    fun cancel(episodeId: String) {
        activeJobs.value[episodeId]?.cancel()
    }

    /**
     * Wipes all cached summaries. Wired up by Slice 4 Disconnect.
     *
     * Cancels in-flight pipelines first so a long audio upload finishing after
     * the wipe can't insert a row against the just-cleared table — that would
     * leak content generated under the old key into a session keyed to the
     * new one. Cancellation is cooperative (next suspension point), so the
     * `currentKey()` re-check inside [runTranscript]/[runAudio] is the
     * defence-in-depth backup for the slim window where the network call is
     * already past its last suspension point.
     *
     * The DB write is routed through [ioContext] (default `Dispatchers.Default`)
     * so callers from the main thread don't ANR on a sluggish on-device write —
     * SQLDelight does not enforce off-main I/O on its own.
     */
    suspend fun clearAll() {
        val jobs = activeJobs.value.values.toList()
        activeJobs.value = emptyMap()
        inFlight.value = emptyMap()
        progress.value = emptyMap()
        transientErrors.value = emptyMap()
        jobs.forEach { it.cancel() }
        jobs.joinAll()
        withContext(ioContext) {
            db.episodeAiSummaryQueries.deleteAll()
            // Transcripts only land in the cache because the AI pipeline fetched
            // them; on Disconnect they're part of the AI footprint and must go too.
            // The AFTER DELETE trigger on TranscriptCache wipes the FTS index rows
            // automatically — no separate FTS delete needed here.
            db.transcriptCacheQueries.deleteAll()
            // Disconnect must also wipe pending markers; otherwise the
            // worker would resume a request the user has explicitly opted
            // out of, against a vault that no longer holds a key. We delete
            // ONLY the summary kind here — DiscussRepository owns its own
            // markers and clears them in its own clearAll.
            db.pendingAiOperationQueries.deleteByKind(PendingOperationKind.Summary.wire)
        }
        // Clear the shared upload cache too. We don't call deleteFile on
        // Gemini's side (locked design decision: let the 48h TTL handle it),
        // but the local row pointing to those URIs has to go so a future
        // reconnect uploads fresh rather than handing the new key a URI
        // uploaded under the old one.
        coordinator.clearAll()
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
            // Tear down progress state alongside the in-flight registration so
            // the panel doesn't briefly render a stale "Formatting" stage after
            // an error or cancellation.
            progress.update { it - episodeId }
            // Drop the resume marker on every terminal state — success, surfaced
            // error, cooperative cancel. Auto-retrying a surfaced error would
            // burn quota; the user's Retry button (or a fresh Generate tap) is
            // the right re-entry point. Process-death recovery is the only
            // case where this delete *doesn't* run, which is exactly the case
            // we want the worker to handle.
            //
            // `NonCancellable` is defence-in-depth: the SQLDelight call is
            // currently synchronous so there's no suspension point for the
            // cancel signal to interrupt, but a future driver upgrade could
            // change that. Without the wrapper a Disconnect-mid-finally race
            // would leak markers. The unit test `pendingMarker_isDeletedOnCancel`
            // rides on `UnconfinedTestDispatcher`'s sequential semantics and
            // does NOT exercise the wrapper directly — the production
            // guarantee is by inspection.
            withContext(NonCancellable) {
                db.pendingAiOperationQueries.deleteByEpisodeAndKind(
                    episodeId = episodeId,
                    kind = PendingOperationKind.Summary.wire,
                )
            }
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

        // No upload payload size to surface here — transcripts are typically
        // tiny and a HEAD probe to learn Content-Length is more code than the
        // cue is worth. The panel hides the right-side size column on null.
        setStage(episodeId, GenerationStage.Preparing, sizeBytes = null)
        val transcriptText: String =
            transcripts.fetch(transcriptUrl).getOrElse { throwable ->
                surface(episodeId, throwable.toAiError())
                return
            }

        setStage(episodeId, GenerationStage.Analysing, sizeBytes = null)
        val prompt = AiPrompts.episodeSummaryPrompt(localeTag = currentLocaleTag())
        val structured: AiSummaryJson =
            summariser.generateFromText(
                apiKey = key,
                model = model,
                prompt = prompt,
                content = transcriptText,
            ).getOrElse { throwable ->
                surface(episodeId, throwable.toAiError())
                return
            }
        setStage(episodeId, GenerationStage.Formatting, sizeBytes = null)
        // Persist transcript text opportunistically for FTS-backed Library search.
        // The text is already in memory from step 3; writing it here costs only
        // the disk write. We cache BEFORE the disconnect guard because transcript
        // content is publisher data — it remains useful for search even if the
        // user's Gemini key has since been revoked.
        db.transcriptCacheQueries.upsert(
            episodeId = episodeId,
            text = transcriptText,
            fetchedAtMs = clock.now().toEpochMilliseconds(),
        )

        if (aiConfig.currentKey().isNullOrBlank()) {
            // Defence in depth: if the user disconnected during the network
            // call, drop the result on the floor rather than persist content
            // that was generated under a now-revoked key.
            return
        }
        db.episodeAiSummaryQueries.upsert(
            episodeId = episodeId,
            generatedAtMs = clock.now().toEpochMilliseconds(),
            modelId = model.apiId,
            sourceKind = AiSourceKind.Transcript.wire,
            sourceFingerprint = transcriptUrl,
            summary = structured.summary,
            peopleJson = encodePersonList(structured.people),
            thingsJson = encodeThingList(structured.things),
            linksJson = encodeLinkList(structured.links),
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
        // pickSource returns Audio only when `download.localPath` is non-blank
        // (and the lock guarantees the snapshot we captured is what reaches
        // here), so `download` and its localPath are present.
        val downloadSnap = download!!
        val key = aiConfig.currentKey()
        if (key.isNullOrBlank()) {
            surface(episodeId, AiError.NoKey)
            return
        }
        val model = aiConfig.model().first()

        val sizeBytes = downloadSnap.downloadedBytes
        val prompt = AiPrompts.episodeSummaryPrompt(localeTag = currentLocaleTag())

        // Initial stage; the coordinator will flip us to Analysing as soon as
        // the upload finalises. We seed Preparing here so the panel renders
        // the size cue immediately, even before the first byte goes out.
        setStage(episodeId, GenerationStage.Preparing, sizeBytes = sizeBytes)
        val acquired =
            coordinator
                .acquire(
                    apiKey = key,
                    episode = episode,
                    download = downloadSnap,
                    onStage = { stage -> setStage(episodeId, stage, sizeBytes = sizeBytes) },
                ).getOrElse { throwable ->
                    surface(episodeId, throwable.toAiError())
                    return
                }
        // On a fresh upload the coordinator already pushed us to Analysing
        // via onStage; on a cache hit it didn't fire onStage at all so the
        // panel is still on Preparing — flip to Analysing here so the user
        // sees forward motion regardless of the cache outcome.
        if (acquired.fromCache) setStage(episodeId, GenerationStage.Analysing, sizeBytes = sizeBytes)
        val structured: AiSummaryJson =
            audio.summariseFromAudio(
                apiKey = key,
                model = model,
                fileUri = acquired.fileUri,
                mimeType = acquired.mimeType,
                prompt = prompt,
            ).getOrElse { throwable ->
                surface(episodeId, throwable.toAiError())
                return
            }
        setStage(episodeId, GenerationStage.Formatting, sizeBytes = sizeBytes)

        if (aiConfig.currentKey().isNullOrBlank()) {
            // Same disconnect-during-pipeline guard as the transcript path.
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
            summary = structured.summary,
            peopleJson = encodePersonList(structured.people),
            thingsJson = encodeThingList(structured.things),
            linksJson = encodeLinkList(structured.links),
        )
        transientErrors.update { it - episodeId }
    }

    private fun surface(
        episodeId: String,
        error: AiError,
    ) {
        transientErrors.update { it + (episodeId to error) }
    }

    private fun setStage(
        episodeId: String,
        stage: GenerationStage,
        sizeBytes: Long?,
    ) {
        progress.update { it + (episodeId to GenerationProgress(stage, sizeBytes)) }
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
        val runningProgress: GenerationProgress?,
    )

    private fun DbEpisodeAiSummary.toDomain(): AiSummary =
        AiSummary(
            episodeId = episodeId,
            generatedAtMs = generatedAtMs,
            modelId = modelId,
            sourceKind = AiSourceKind.fromWire(sourceKind) ?: AiSourceKind.Transcript,
            sourceFingerprint = sourceFingerprint,
            summary = summary,
            people = decodePersonList(peopleJson),
            things = decodeThingList(thingsJson),
            links = decodeLinkList(linksJson),
        )

    private companion object {
        // Soft cap aligned with the spec — Gemini's actual context window
        // varies by model, but 8h is a safe headroom across Flash variants
        // and matches the user-facing "8 hours" copy in the AudioTooLong
        // error card.
        const val AUDIO_MAX_DURATION_SEC = 8L * 3600

        // Lenient on read so a forward-compatible schema bump (e.g. adding a
        // confidence score per entity) doesn't surface as a parse error and
        // wipe the cached row out of the user's view. On write we use the
        // same instance.
        val entityJson: Json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

        private val personListSerializer = ListSerializer(MentionedPersonJson.serializer())
        private val thingListSerializer = ListSerializer(MentionedThingJson.serializer())
        private val linkListSerializer = ListSerializer(MentionedLinkJson.serializer())

        fun encodePersonList(values: List<MentionedPersonJson>): String = entityJson.encodeToString(personListSerializer, values)

        fun encodeThingList(values: List<MentionedThingJson>): String = entityJson.encodeToString(thingListSerializer, values)

        fun encodeLinkList(values: List<MentionedLinkJson>): String = entityJson.encodeToString(linkListSerializer, values)

        // All decoders fall back to an empty list on parse failure rather
        // than tearing the entire Ready card down — a corrupt entity column
        // is annoying but the prose summary is still useful, and the next
        // regenerate will repair the row anyway.
        //
        // The person/thing decoders also accept the legacy `["string", ...]`
        // shape (rows persisted before the Slice 3.5 wire-shape extension)
        // so users with cached summaries don't see them disappear after the
        // upgrade. Legacy entries land with an empty subtitle, which the UI
        // already handles.
        fun decodePersonList(raw: String): List<MentionedPerson> =
            runCatching {
                entityJson
                    .decodeFromString(personListSerializer, raw)
                    .map { MentionedPerson(it.name, it.subtitle) }
            }.recoverCatching {
                entityJson
                    .decodeFromString(legacyStringListSerializer, raw)
                    .map { MentionedPerson(name = it, subtitle = "") }
            }.getOrDefault(emptyList())

        fun decodeThingList(raw: String): List<MentionedThing> =
            runCatching {
                entityJson
                    .decodeFromString(thingListSerializer, raw)
                    .map { MentionedThing(it.name, it.subtitle) }
            }.recoverCatching {
                entityJson
                    .decodeFromString(legacyStringListSerializer, raw)
                    .map { MentionedThing(name = it, subtitle = "") }
            }.getOrDefault(emptyList())

        fun decodeLinkList(raw: String): List<MentionedLink> =
            runCatching {
                entityJson
                    .decodeFromString(linkListSerializer, raw)
                    .map { MentionedLink(it.label, it.url) }
            }.getOrDefault(emptyList())

        private val legacyStringListSerializer = ListSerializer(String.serializer())
    }
}
