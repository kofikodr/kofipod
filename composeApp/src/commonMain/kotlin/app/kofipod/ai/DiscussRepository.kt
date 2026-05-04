// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.kofipod.data.repo.EpisodeSource
import app.kofipod.db.KofipodDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import app.kofipod.db.DiscussMessage as DbDiscussMessage
import app.kofipod.db.DiscussSession as DbDiscussSession

/**
 * Seam over the cached-summary read so [DiscussRepository] can derive
 * episode-specific suggestion prompts without taking a hard dependency on
 * [AiSummaryRepository]. Production binding: a thin lambda over
 * [AiSummaryRepository.cachedFor] in `CommonModule`.
 */
fun interface SummarySource {
    fun cachedFor(episodeId: String): Flow<AiSummary?>
}

/**
 * Orchestrates the multi-turn Discuss / Q&A pipeline for one episode at a time.
 *
 * Mirrors [AiSummaryRepository]'s shape: cold per-episode state via [observeFor],
 * non-suspending [send] that launches on the long-lived `appScope`, single-flight
 * via a shared [sendLock], live [Job] tracking via [activeJobs] so [clearAll]
 * (Disconnect) and [clearForEpisode] (per-chat trash) can cancel + drain in-flight
 * work before wiping rows.
 *
 * Logging discipline: failures here surface as [AiError] only. We never log the
 * prompt, the transcript body, the user question, the model answer, or the API
 * key — same rule [GeminiClient] follows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscussRepository(
    private val db: KofipodDatabase,
    private val aiConfig: AiConfigRepository,
    private val chat: ChatSummariser,
    private val source: DiscussSource,
    // Owns the upload-or-reuse-cached decision for audio-backed sessions.
    // Shared with AiSummaryRepository so a Summary-side upload survives long
    // enough for a Discuss session to skip re-uploading.
    private val coordinator: AudioUploadCoordinator,
    private val episodes: EpisodeSource,
    private val downloads: DownloadSource,
    private val summaries: SummarySource,
    private val appScope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val ioContext: CoroutineContext = Dispatchers.Default,
    // Injected so tests can produce deterministic ids. Production default is a
    // timestamp-prefixed base36 string with eight random suffix characters —
    // adequate for a per-row ID in one user's local DB and small enough to log
    // (we don't, but the option's there) without fingerprinting the user.
    private val idGen: () -> String = ::defaultId,
    // Mirrors AiSummaryRepository's gate: false on iOS where
    // openLocalFileChannel is stubbed. Today this only matters as a hard
    // belt-and-braces — iOS has no downloads feature, so observeFor's
    // `hasDownloadedAudio` would already be false. Keeping the guard
    // co-located with the Summary repo's pattern means a future iOS-side
    // download feature won't accidentally enable Discuss audio without
    // the corresponding actual implementation of openLocalFileChannel.
    private val audioFallbackEnabled: Boolean = audioFallbackSupported(),
) {
    /** episodeId → in-flight marker. A second [send] for the same id while one is running is dropped. */
    private val inFlight = MutableStateFlow<Set<String>>(emptySet())

    /** episodeId → most recent transient error, surfaced once and cleared on next send. */
    private val transientErrors = MutableStateFlow<Map<String, AiError>>(emptyMap())

    /**
     * episodeId → live upload-stage payload for the **first** audio-backed
     * send in a session. Cleared in the `runSend` `finally` so subsequent
     * turns use the regular [DiscussUiState.Ready.inFlight] spinner. Only
     * fires on cache miss — a cache hit returns a URI without ever calling
     * onStage on the coordinator.
     */
    private val uploadProgress = MutableStateFlow<Map<String, DiscussProgress>>(emptyMap())

    /**
     * episodeId → live coroutine [Job] for the in-flight pipeline so [clearAll]
     * and [clearForEpisode] can cancel + drain them. Without this, a long
     * Gemini call finishing after a wipe could insert a model row against the
     * just-cleared session.
     */
    private val activeJobs = MutableStateFlow<Map<String, Job>>(emptyMap())

    /**
     * Single-flight guard around the eligibility check + in-flight registration.
     * The actual chat call runs outside the lock so other episodes can be
     * answered concurrently — mirrors [AiSummaryRepository.generateLock].
     */
    private val sendLock = Mutex()

    fun observeFor(episodeId: String): Flow<DiscussUiState> {
        val keyFlow = aiConfig.isKeyConfigured()
        val episodeFlow = episodes.episodeFlow(episodeId)
        val downloadFlow = downloads.forEpisodeFlow(episodeId)
        val sessionFlow: Flow<DbDiscussSession?> =
            db.discussSessionQueries.selectByEpisodeFlow(episodeId).asFlow().mapToOneOrNull(Dispatchers.Default)
        val messagesFlow: Flow<List<DiscussMessage>> =
            sessionFlow.flatMapLatest { session ->
                if (session == null) {
                    flowOf(emptyList())
                } else {
                    db.discussMessageQueries.selectBySessionFlow(session.id)
                        .asFlow()
                        .mapToList(Dispatchers.Default)
                        .map { rows -> rows.map { it.toDomain() } }
                }
            }
        val summaryFlow: Flow<AiSummary?> = summaries.cachedFor(episodeId)
        val inFlightForId: Flow<Boolean> = inFlight.map { episodeId in it }
        val errorForId: Flow<AiError?> = transientErrors.map { it[episodeId] }
        val progressForId: Flow<DiscussProgress?> = uploadProgress.map { it[episodeId] }

        // `combine` tops out at 5 typed flows. Pre-zip the always-needed-together
        // signals so we stay below the ceiling alongside the per-row streams.
        val transientFlow: Flow<Transient> =
            combine(
                keyFlow,
                inFlightForId,
                errorForId,
                summaryFlow,
                progressForId,
            ) { keyOk, running, error, summary, progress ->
                Transient(keyOk, running, error, summary, progress)
            }
        // Source-state pre-zip. Two booleans + one stream of messages, kept
        // separate from `transientFlow` so a transient (key/inFlight/...) tick
        // doesn't recompute source eligibility.
        val sourceFlow: Flow<SourceState> =
            combine(episodeFlow, downloadFlow, messagesFlow) { episode, download, messages ->
                SourceState(episode, download, messages)
            }

        return combine(transientFlow, sourceFlow) { transient, src ->
            if (!transient.keyOk) return@combine DiscussUiState.Hidden
            val episode = src.episode ?: return@combine DiscussUiState.NoSource
            // Phase 2: source eligibility now widens to a downloaded episode
            // when no transcript is published. Mirrors AiSummaryRepository's
            // `pickSource` preference order.
            val hasTranscript = !episode.transcriptUrl.isNullOrBlank()
            val hasDownloadedAudio =
                audioFallbackEnabled &&
                    src.download != null &&
                    src.download.state == DOWNLOAD_STATE_COMPLETED &&
                    !src.download.localPath.isNullOrBlank()
            if (!hasTranscript && !hasDownloadedAudio) return@combine DiscussUiState.NoSource
            // Audio sends spend more user quota per turn (Gemini re-processes
            // the audio on every call). Surface a warning at the threshold so
            // long chats don't accidentally burn the user's daily budget.
            // Transcript sessions don't trigger the warning — text replay is
            // the cheap path the user already opted into.
            val isAudioSession = !hasTranscript
            val userTurnCount = src.messages.count { it.role == DiscussRole.User }
            val warningVisible = isAudioSession && userTurnCount >= AUDIO_TURN_WARNING_THRESHOLD
            DiscussUiState.Ready(
                messages = src.messages,
                suggestions = DiscussPrompts.suggestionsFromSummary(transient.summary),
                quickPrompts = DiscussPrompts.QUICK_PROMPTS,
                inFlight = transient.running,
                error = transient.error,
                progress = transient.progress,
                audioTurnWarningVisible = warningVisible,
            )
        }.onCompletion {
            // Drop transient error when the last subscriber goes away — same
            // rationale as AiSummaryRepository.observeFor: a stale Network
            // error from yesterday shouldn't render the moment the user
            // reopens the screen.
            transientErrors.update { it - episodeId }
        }
    }

    /**
     * Non-suspending entry point. Persists the user turn immediately so the
     * UI lands the message before the network round-trip begins, then runs
     * the pipeline on `appScope` so navigating away mid-call doesn't cancel
     * the request. Idempotent per episodeId while a turn is in flight.
     */
    fun send(
        episodeId: String,
        question: String,
    ) {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return
        val job =
            appScope.launch {
                runSend(episodeId, trimmed)
            }
        // Same ordering as AiSummaryRepository: register the completion
        // handler BEFORE adding to activeJobs so a fast-completing job can't
        // race the put-to-map.
        job.invokeOnCompletion { activeJobs.update { it - episodeId } }
        activeJobs.update { it + (episodeId to job) }
    }

    /**
     * Cancels the in-flight send for [episodeId], if any. Safe to call when
     * nothing is running. Mirrors [AiSummaryRepository.cancel] — the cancelled
     * job's `finally` clears `inFlight` + `uploadProgress` so the panel falls
     * back to Idle. Used by the staged-progress card's Cancel button.
     */
    fun cancel(episodeId: String) {
        activeJobs.value[episodeId]?.cancel()
    }

    /**
     * Wipes one episode's chat — used by the trashcan affordance on the
     * Discuss tab card and on the Ask Gemini header. Mirrors [clearAll]'s
     * cancel-then-delete order so a still-running send can't write back into
     * the just-cleared session.
     */
    suspend fun clearForEpisode(episodeId: String) {
        val job = activeJobs.value[episodeId]
        activeJobs.update { it - episodeId }
        inFlight.update { it - episodeId }
        transientErrors.update { it - episodeId }
        uploadProgress.update { it - episodeId }
        job?.cancel()
        job?.join()
        withContext(ioContext) {
            // Messages cascade with the session via FK ON DELETE CASCADE.
            db.discussSessionQueries.deleteByEpisode(episodeId)
            // Drop any leftover upload marker so the next worker fire doesn't
            // see a ghost row from a chat the user just trashed.
            db.pendingAiOperationQueries.deleteByEpisodeAndKind(
                episodeId = episodeId,
                kind = PendingOperationKind.DiscussUpload.wire,
            )
        }
    }

    /**
     * Worker entry point. Drains every `discuss_upload` marker by simply
     * deleting it — the deviation we picked from the architecture review:
     * recovery is user-driven (re-tap send), the marker is just a breadcrumb
     * that the upload was attempted. Re-firing a chat send minutes after
     * the user has moved on would surface a stale answer with no context.
     *
     * Called from [app.kofipod.background.AiSummaryWorker] alongside
     * [AiSummaryRepository.resumePending], so a single worker fire collapses
     * both kinds.
     */
    suspend fun cleanStaleDiscussUploads() =
        withContext(ioContext) {
            db.pendingAiOperationQueries.deleteByKind(PendingOperationKind.DiscussUpload.wire)
        }

    /**
     * Wipes every chat across every episode. Wired to Disconnect (in
     * [app.kofipod.ui.screens.settings.ai.AiSetupViewModel.confirmDisconnect])
     * after [AiSummaryRepository.clearAll] so both halves of the user's AI
     * footprint are removed in one action.
     */
    suspend fun clearAll() {
        val jobs = activeJobs.value.values.toList()
        activeJobs.value = emptyMap()
        inFlight.value = emptySet()
        transientErrors.value = emptyMap()
        uploadProgress.value = emptyMap()
        jobs.forEach { it.cancel() }
        jobs.joinAll()
        withContext(ioContext) {
            // deleteAll on the parent cascades to messages via FK; the
            // explicit child delete is defence in depth in case a future
            // schema change drops the cascade.
            db.discussSessionQueries.deleteAll()
            db.discussMessageQueries.deleteAll()
            // Discuss owns its own kind of marker; sweep them now so the
            // worker doesn't see them on the next fire after a Disconnect.
            // The shared upload cache is wiped by AiSummaryRepository.clearAll
            // in the same call sequence — leaving us with a clean slate.
            db.pendingAiOperationQueries.deleteByKind(PendingOperationKind.DiscussUpload.wire)
        }
    }

    // ----------------------------------------------------------------------
    // Pipeline
    // ----------------------------------------------------------------------

    private suspend fun runSend(
        episodeId: String,
        question: String,
    ) {
        val sessionId =
            sendLock.withLock {
                if (episodeId in inFlight.value) return
                val ep = episodes.episodeFlow(episodeId).first()
                if (ep == null) {
                    surface(episodeId, AiError.TranscriptUnavailable)
                    return
                }
                inFlight.update { it + episodeId }
                transientErrors.update { it - episodeId }
                ensureSession(episodeId)
            }

        try {
            // Persist the user turn first so the UI lands the message and
            // scrolls before the network round-trip begins. We pass `question`
            // directly to chat.chat() rather than reading it back from the DB
            // to avoid a Flow-debounce race where the just-inserted row hasn't
            // propagated yet.
            insertMessage(sessionId, DiscussRole.User, question, citations = emptyList())

            val key = aiConfig.currentKey()
            if (key.isNullOrBlank()) {
                surface(episodeId, AiError.NoKey)
                return
            }
            val model = aiConfig.model().first()

            // Re-read episode + download under the pipeline (cheap, and the
            // feed could have ticked between the lock-side check and here).
            val episode = episodes.episodeFlow(episodeId).first()
            if (episode == null) {
                surface(episodeId, AiError.TranscriptUnavailable)
                return
            }
            val download = downloads.forEpisodeFlow(episodeId).first()

            val context =
                source.loadContext(episode, download).getOrElse { throwable ->
                    surface(episodeId, throwable.toAiError())
                    return
                }
            val chatContext: ChatContext =
                when (context) {
                    is DiscussContext.Available -> ChatContext.Transcript(context.transcript)
                    is DiscussContext.AudioReady ->
                        acquireAudioContext(episodeId, episode, download, context, key)
                            .getOrElse { throwable ->
                                surface(episodeId, throwable.toAiError())
                                return
                            }
                    DiscussContext.NotAvailable -> {
                        surface(episodeId, AiError.TranscriptUnavailable)
                        return
                    }
                }

            // priorTurns excludes the user message we just inserted — we send
            // it explicitly as the new question. Older turns get capped to
            // MAX_HISTORY_TURNS so the input token count stays bounded as the
            // chat grows. The alternation sanitiser collapses any consecutive
            // same-role rows that may exist from a corrupted DB or a partial
            // recovery — Gemini's chat API requires strict user/model/user
            // alternation and a violation surfaces as a 400 that maps to the
            // misleading KeyInvalid error.
            val history =
                priorTurns(sessionId, excludingLatest = true)
                    .let(::collapseConsecutiveSameRole)
                    .takeLast(MAX_HISTORY_TURNS)

            val answer =
                chat.chat(
                    apiKey = key,
                    model = model,
                    systemPrompt = DiscussPrompts.systemPrompt(currentLocaleTag()),
                    context = chatContext,
                    history = history,
                    question = question,
                ).getOrElse { throwable ->
                    surface(episodeId, throwable.toAiError())
                    return
                }

            // Defence in depth: if the user disconnected while the network
            // call was in flight, drop the result rather than persist content
            // generated under a now-revoked key.
            if (aiConfig.currentKey().isNullOrBlank()) return

            insertMessage(
                sessionId = sessionId,
                role = DiscussRole.Model,
                content = answer.answer,
                citations = answer.citations.map { DiscussCitation(it.label, it.timestampMs) },
            )
            transientErrors.update { it - episodeId }
        } finally {
            // NonCancellable mirrors AiSummaryRepository's defence in depth —
            // SQLDelight calls are synchronous today, but we don't want a
            // future driver upgrade to leave inFlight stuck on cancel.
            withContext(NonCancellable) {
                inFlight.update { it - episodeId }
                uploadProgress.update { it - episodeId }
                // Drop our own DiscussUpload marker on every terminal state.
                // The marker only exists to flag that an upload was attempted;
                // we never act on it for recovery (per locked design choice),
                // so leaving it after success/error/cancel would just confuse
                // a future reader of the table.
                db.pendingAiOperationQueries.deleteByEpisodeAndKind(
                    episodeId = episodeId,
                    kind = PendingOperationKind.DiscussUpload.wire,
                )
            }
        }
    }

    /**
     * Resolves an [AudioReady] context to a [ChatContext.Audio] — either by
     * reusing a cached Files API URI (zero network) or by uploading the local
     * audio file and persisting the resulting URI for future reuse.
     *
     * Returns [Result.failure] with the underlying [AiErrorException] so the
     * caller can map it to [AiError] uniformly with the rest of the file's
     * error flow. The marker write is gated on [AcquiredAudioFile.fromCache]
     * — cache hits skip the breadcrumb because no upload was attempted; the
     * marker would only confuse the worker's stale-row sweep.
     */
    private suspend fun acquireAudioContext(
        episodeId: String,
        episode: app.kofipod.db.Episode,
        download: app.kofipod.db.Download?,
        ready: DiscussContext.AudioReady,
        apiKey: String,
    ): Result<ChatContext.Audio> {
        // pickSource on the source side guarantees download is non-null and
        // localPath is set whenever AudioReady is returned. Re-snapshot under
        // the pipeline so a download deletion mid-call surfaces explicitly
        // rather than hitting a !! later.
        val downloadSnap =
            download
                ?: return Result.failure(AiErrorException(AiError.TranscriptUnavailable))

        val acquired =
            coordinator
                .acquire(
                    apiKey = apiKey,
                    episode = episode,
                    download = downloadSnap,
                    onStage = { stage ->
                        // Surface the staged-progress payload only on a fresh
                        // upload. Cache hits don't fire onStage, so the panel
                        // stays on the regular in-flight indicator.
                        uploadProgress.update {
                            it +
                                (
                                    episodeId to
                                        DiscussProgress(
                                            stage = stage.toDiscussProgressStage(),
                                            sizeBytes = ready.sizeBytes,
                                        )
                                )
                        }
                    },
                ).getOrElse { return Result.failure(it) }

        // Persist the upload-attempt breadcrumb only when we actually
        // uploaded — a cache hit didn't touch the network so a process
        // death wouldn't have anything to recover from. The finally in
        // runSend deletes any marker on every terminal state, regardless
        // of whether we wrote one here.
        if (!acquired.fromCache) {
            withContext(ioContext) {
                db.pendingAiOperationQueries.upsert(
                    episodeId = episodeId,
                    kind = PendingOperationKind.DiscussUpload.wire,
                    requestedAtMs = clock.now().toEpochMilliseconds(),
                )
            }
        }
        return Result.success(
            ChatContext.Audio(fileUri = acquired.fileUri, mimeType = acquired.mimeType),
        )
    }

    private suspend fun ensureSession(episodeId: String): String {
        val existing =
            withContext(ioContext) {
                db.discussSessionQueries.selectByEpisode(episodeId).executeAsOneOrNull()
            }
        if (existing != null) {
            withContext(ioContext) {
                db.discussSessionQueries.touch(updatedAtMs = clock.now().toEpochMilliseconds(), id = existing.id)
            }
            return existing.id
        }
        val id = idGen()
        val now = clock.now().toEpochMilliseconds()
        withContext(ioContext) {
            db.discussSessionQueries.insert(id = id, episodeId = episodeId, createdAtMs = now, updatedAtMs = now)
        }
        return id
    }

    private suspend fun insertMessage(
        sessionId: String,
        role: DiscussRole,
        content: String,
        citations: List<DiscussCitation>,
    ) {
        val now = clock.now().toEpochMilliseconds()
        val citationsJson = encodeCitationList(citations.map { CitationJson(it.label, it.timestampMs) })
        withContext(ioContext) {
            db.discussMessageQueries.insert(
                id = idGen(),
                sessionId = sessionId,
                role = role.wire,
                content = content,
                citationsJson = citationsJson,
                createdAtMs = now,
            )
            db.discussSessionQueries.touch(updatedAtMs = now, id = sessionId)
        }
    }

    private suspend fun priorTurns(
        sessionId: String,
        excludingLatest: Boolean,
    ): List<DiscussTurn> {
        val rows =
            withContext(ioContext) {
                db.discussMessageQueries.selectBySessionFlow(sessionId).executeAsList()
            }
        val capped = if (excludingLatest && rows.isNotEmpty()) rows.dropLast(1) else rows
        return capped.mapNotNull { row ->
            val role = DiscussRole.fromWire(row.role) ?: return@mapNotNull null
            DiscussTurn(role = role, text = row.content)
        }
    }

    private fun surface(
        episodeId: String,
        error: AiError,
    ) {
        transientErrors.update { it + (episodeId to error) }
    }

    private data class Transient(
        val keyOk: Boolean,
        val running: Boolean,
        val error: AiError?,
        val summary: AiSummary?,
        val progress: DiscussProgress?,
    )

    private data class SourceState(
        val episode: app.kofipod.db.Episode?,
        val download: app.kofipod.db.Download?,
        val messages: List<DiscussMessage>,
    )

    private fun DbDiscussMessage.toDomain(): DiscussMessage =
        DiscussMessage(
            id = id,
            role = DiscussRole.fromWire(role) ?: DiscussRole.Model,
            content = content,
            citations = decodeCitationList(citationsJson),
            createdAtMs = createdAtMs,
        )

    /**
     * Drops any turn whose role matches the previous one's, keeping only the
     * earliest of a same-role run. Gemini's chat API rejects a `contents`
     * array that doesn't strictly alternate `user` / `model`, and the wrong
     * status code (400 → mapped to KeyInvalid) would mislead the user.
     * Cheap enough to run on every send — typical history is ≤ 20 turns.
     */
    private fun collapseConsecutiveSameRole(turns: List<DiscussTurn>): List<DiscussTurn> =
        buildList(turns.size) {
            for (turn in turns) {
                if (isEmpty() || last().role != turn.role) add(turn)
            }
        }

    companion object {
        /**
         * History cap. 20 turns (10 user + 10 model) is plenty for any real
         * Q&A session and bounds growth so a long-running chat doesn't push
         * the token count up indefinitely. Older messages stay in the DB but
         * don't go to the model.
         */
        private const val MAX_HISTORY_TURNS = 20

        /**
         * Threshold for the "long audio chat" warning banner. Audio sessions
         * re-send the audio context to Gemini on every turn, so quota burns
         * faster than transcript chats. Five user turns is comfortably below
         * the free-tier daily limit on Flash 2.5 but high enough that casual
         * chats never see the banner.
         */
        private const val AUDIO_TURN_WARNING_THRESHOLD = 5

        private val citationJson: Json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
        private val citationListSerializer = ListSerializer(CitationJson.serializer())

        fun encodeCitationList(values: List<CitationJson>): String = citationJson.encodeToString(citationListSerializer, values)

        fun decodeCitationList(raw: String): List<DiscussCitation> =
            runCatching {
                citationJson
                    .decodeFromString(citationListSerializer, raw)
                    .map { DiscussCitation(it.label, it.timestampMs) }
            }.getOrDefault(emptyList())

        /** Module-private id generator — keeps the public surface free of one-off helpers. */
        internal fun defaultId(): String {
            val now = Clock.System.now().toEpochMilliseconds().toString(36)
            val rand = (1..8).map { ALPHABET[Random.nextInt(ALPHABET.length)] }.joinToString("")
            return "$now-$rand"
        }

        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
}
