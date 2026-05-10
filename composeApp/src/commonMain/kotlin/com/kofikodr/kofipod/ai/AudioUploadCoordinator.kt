// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

import com.kofikodr.kofipod.db.Download
import com.kofikodr.kofipod.db.Episode
import com.kofikodr.kofipod.db.KofipodDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlin.coroutines.CoroutineContext

/**
 * Seam over the Files API "chunked upload + poll until ACTIVE" pair so the
 * coordinator can be unit-tested without standing up Ktor's MockEngine
 * (whose internal dispatcher doesn't compose with `runTest`'s virtual
 * scheduler). Production binding is a thin lambda that calls
 * [GeminiClient.uploadAudio] and then [GeminiClient.pollUntilActive].
 *
 * @param onProgress invoked monotonically with the cumulative byte count
 *   the server has confirmed for the upload portion. Reaches `sizeBytes`
 *   when the last chunk lands; `pollUntilActive` does not fire it. UI
 *   layers translate this into a progress bar on the "Uploading audio" row.
 */
fun interface AudioUploader {
    suspend fun upload(
        apiKey: String,
        localPath: String,
        mimeType: String,
        sizeBytes: Long,
        displayName: String,
        onProgress: (uploadedBytes: Long) -> Unit,
    ): Result<UploadedFile>
}

/**
 * Owns the "give me a Gemini Files API URI for this episode's audio" decision.
 *
 * Both [AiSummaryRepository] and [DiscussRepository] depend on this. Neither
 * touches [GeminiClient.uploadAudio] directly anymore — that primitive is now
 * an implementation detail of [acquire]. The cache lookup, the conditional
 * upload, the post-upload write to [AudioUploadCache], and the per-episode
 * deduplication all live here so neither repo can drift out of sync with the
 * other.
 *
 * The 48h Files API TTL is enforced **locally** via [AudioUploadCache]'s
 * `expiresAtMs` column (set to `uploadedAtMs + ttlMs`). We refuse to hand
 * back a URI within an hour of expiry to absorb clock skew + Gemini-side
 * propagation lag — a stale URI presented to `generateContent` would surface
 * as an unhelpful 400.
 *
 * Single-flight per-episodeId via [uploadLocks]: two repos racing on the
 * same episode collapse to one upload. The lock is released as soon as the
 * cache row is written, so a third call ms later finds the freshly-cached
 * URI rather than blocking on the same Mutex.
 */
class AudioUploadCoordinator(
    private val uploader: AudioUploader,
    private val db: KofipodDatabase,
    private val clock: Clock = Clock.System,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val ioContext: CoroutineContext = Dispatchers.Default,
) {
    /**
     * Per-episode mutexes. Created lazily on first acquire; never removed.
     * Cardinality is bounded by the user's library size and each entry is a
     * few bytes, so a long-lived map is cheaper than wrapping every lookup
     * in a global mutex.
     */
    private val uploadLocks = mutableMapOf<String, Mutex>()
    private val locksMutex = Mutex()

    private suspend fun lockFor(episodeId: String): Mutex = locksMutex.withLock { uploadLocks.getOrPut(episodeId) { Mutex() } }

    /**
     * Returns an active Gemini Files API URI for the episode's downloaded
     * audio. Hits the local cache when a non-expired entry with the same
     * fingerprint exists; otherwise uploads, polls until ACTIVE, persists,
     * and returns. Either path produces the same [AcquiredAudioFile] shape
     * so callers can't tell whether a network round-trip happened.
     *
     * @param onStage receives [GenerationStage.Preparing] before upload,
     *   [GenerationStage.Analysing] after upload finalises and polling
     *   begins. **Not invoked on cache hits** — the caller's UI uses this
     *   to decide whether to render the staged progress card.
     * @param onUploadProgress monotonic byte count of upload bytes the
     *   server has confirmed. Fires only on a fresh upload (cache hits
     *   skip it). UI receivers render this as `received / sizeBytes` on
     *   the Preparing row's progress bar.
     */
    suspend fun acquire(
        apiKey: String,
        episode: Episode,
        download: Download,
        onStage: (GenerationStage) -> Unit = {},
        onUploadProgress: (uploadedBytes: Long) -> Unit = {},
    ): Result<AcquiredAudioFile> {
        val mimeType = episode.enclosureMimeType.ifBlank { DEFAULT_AUDIO_MIME }
        val fingerprint = download.downloadedBytes.toString()

        // Fast path BEFORE taking the per-episode lock. A typical case is two
        // repos racing on the first send right after a Summary finished —
        // the cache has just been populated and we want the second caller to
        // skip the lock entirely. The cached check also runs again under the
        // lock to close the race where the lock is contested.
        cachedHit(episode.id, fingerprint)?.let { return Result.success(it) }

        val lock = lockFor(episode.id)
        return lock.withLock {
            cachedHit(episode.id, fingerprint)?.let { return@withLock Result.success(it) }
            performUpload(
                apiKey = apiKey,
                episodeId = episode.id,
                localPath =
                    download.localPath
                        ?: return@withLock Result.failure(AiErrorException(AiError.AudioTooLong)),
                mimeType = mimeType,
                sizeBytes = download.downloadedBytes,
                fingerprint = fingerprint,
                onStage = onStage,
                onUploadProgress = onUploadProgress,
            )
        }
    }

    /**
     * Wipes every cached upload row. Called from [AiSummaryRepository.clearAll]
     * (Disconnect). We do **not** call [GeminiClient.deleteFile] — locked
     * decision: let Gemini's 48h TTL clean up server-side files. Removing the
     * local row is enough to make the next acquire start fresh.
     */
    suspend fun clearAll() =
        withContext(ioContext) {
            db.audioUploadCacheQueries.deleteAll()
        }

    /** Drop the cache row for one episode. Used when an episode is deleted. */
    suspend fun clearForEpisode(episodeId: String) =
        withContext(ioContext) {
            db.audioUploadCacheQueries.deleteByEpisode(episodeId)
        }

    private suspend fun cachedHit(
        episodeId: String,
        fingerprint: String,
    ): AcquiredAudioFile? =
        withContext(ioContext) {
            val row = db.audioUploadCacheQueries.selectByEpisode(episodeId).executeAsOneOrNull() ?: return@withContext null
            if (row.fingerprint != fingerprint) return@withContext null
            // Operate one full hour inside Gemini's claimed 48h TTL — small
            // enough that real users almost never hit the boundary, large
            // enough to absorb clock skew between the device and Gemini.
            if (row.expiresAtMs - clock.now().toEpochMilliseconds() < REUSE_SAFETY_MARGIN_MS) {
                return@withContext null
            }
            AcquiredAudioFile(
                fileUri = row.geminiUri,
                geminiName = row.geminiName,
                mimeType = row.mimeType,
                fromCache = true,
            )
        }

    private suspend fun performUpload(
        apiKey: String,
        episodeId: String,
        localPath: String,
        mimeType: String,
        sizeBytes: Long,
        fingerprint: String,
        onStage: (GenerationStage) -> Unit,
        onUploadProgress: (uploadedBytes: Long) -> Unit,
    ): Result<AcquiredAudioFile> {
        onStage(GenerationStage.Preparing)
        // The uploader handles the chunked upload + active-state poll as one
        // suspend call so the coordinator only sees the final ACTIVE
        // [UploadedFile]. Stage flips to Analysing as soon as the upload's
        // done — production wiring (CommonModule) interleaves the two calls
        // and fires Analysing between them; tests are free to fire it
        // whenever they like.
        val active =
            uploader.upload(
                apiKey = apiKey,
                localPath = localPath,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                displayName = "kofipod-$episodeId",
                onProgress = onUploadProgress,
            ).getOrElse { return Result.failure(it) }
        onStage(GenerationStage.Analysing)

        val now = clock.now().toEpochMilliseconds()
        withContext(ioContext) {
            db.audioUploadCacheQueries.upsert(
                episodeId = episodeId,
                geminiUri = active.uri,
                geminiName = active.name,
                mimeType = active.mimeType,
                fingerprint = fingerprint,
                uploadedAtMs = now,
                expiresAtMs = now + ttlMs,
            )
        }
        return Result.success(
            AcquiredAudioFile(
                fileUri = active.uri,
                geminiName = active.name,
                mimeType = active.mimeType,
                fromCache = false,
            ),
        )
    }

    companion object {
        // 47h. Gemini's documented Files API TTL is 48h; we let go of the URI
        // an hour before that so a tail-of-the-window chat doesn't get a 404.
        const val DEFAULT_TTL_MS: Long = 47L * 60 * 60 * 1000

        // Don't hand back a cached URI within this margin of expiry. Same
        // motivation as the TTL itself — better to upload fresh than to
        // surface an unhelpful 400 to the user.
        private const val REUSE_SAFETY_MARGIN_MS: Long = 60L * 60 * 1000
    }
}

/**
 * Output of [AudioUploadCoordinator.acquire]. [fromCache] tells the caller
 * whether to render the staged progress card (skip on cache hit) but does
 * not affect the chat call itself — the URI is identical either way.
 */
data class AcquiredAudioFile(
    val fileUri: String,
    val geminiName: String,
    val mimeType: String,
    val fromCache: Boolean,
)
