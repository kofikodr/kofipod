// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

import com.kofikodr.kofipod.db.Download
import com.kofikodr.kofipod.db.Episode
import com.kofikodr.kofipod.testing.inMemoryDatabase
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [AudioUploadCoordinator]'s upload-or-reuse contract — the single
 * decision point both Summary and Discuss depend on. Bugs here would either
 * (a) re-upload the same audio twice within Gemini's 48h Files API TTL,
 * burning bandwidth + quota, or (b) hand back a stale URI that Gemini has
 * already auto-purged, surfacing as a confusing 400.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioUploadCoordinatorTest {
    @Test
    fun acquire_cacheMiss_uploadsAndPersists() =
        runTest {
            // Cold cache → coordinator must invoke the uploader and write the
            // returned URI + expiry into AudioUploadCache.
            val db = inMemoryDatabase()
            val uploader = RecordingUploader()
            val coordinator = buildCoordinator(db, uploader, nowMs = 1_000L)

            insertEpisode(db, "ep1")
            val acquired =
                coordinator
                    .acquire(
                        apiKey = "k",
                        episode = episode("ep1", mime = "audio/mpeg"),
                        download = download("ep1", bytes = 5_000_000L),
                    ).getOrThrow()

            assertEquals(false, acquired.fromCache, "Cache miss must report fromCache=false")
            assertEquals(1, uploader.calls.size, "Cache miss must trigger exactly one upload call")
            assertEquals(5_000_000L, uploader.calls[0].sizeBytes)
            val cached = db.audioUploadCacheQueries.selectByEpisode("ep1").executeAsOneOrNull()
            assertNotNull(cached, "Successful upload must persist a cache row for reuse")
            assertEquals(acquired.fileUri, cached.geminiUri)
            assertEquals(1_000L + AudioUploadCoordinator.DEFAULT_TTL_MS, cached.expiresAtMs)
            assertEquals("5000000", cached.fingerprint)
        }

    @Test
    fun acquire_cacheHit_skipsUpload() =
        runTest {
            // A non-expired cache row with matching fingerprint → coordinator
            // returns immediately without touching the uploader.
            val db = inMemoryDatabase()
            val uploader = RecordingUploader()
            val coordinator = buildCoordinator(db, uploader, nowMs = 1_000L)
            insertEpisode(db, "ep1")
            db.audioUploadCacheQueries.upsert(
                episodeId = "ep1",
                geminiUri = "https://gemini/cached",
                geminiName = "files/cached",
                mimeType = "audio/mpeg",
                fingerprint = "5000000",
                uploadedAtMs = 0L,
                expiresAtMs = 1_000L + AudioUploadCoordinator.DEFAULT_TTL_MS,
            )

            val acquired =
                coordinator
                    .acquire(
                        apiKey = "k",
                        episode = episode("ep1"),
                        download = download("ep1", bytes = 5_000_000L),
                    ).getOrThrow()

            assertEquals(true, acquired.fromCache, "Reused row must report fromCache=true")
            assertEquals("https://gemini/cached", acquired.fileUri)
            assertTrue(uploader.calls.isEmpty(), "Cache hit must NOT call the uploader")
        }

    @Test
    fun acquire_cacheHit_butFingerprintMismatch_uploadsFresh() =
        runTest {
            // Same episode, different byte count (re-encoded download) →
            // cached URI points at the wrong audio. Must invalidate.
            val db = inMemoryDatabase()
            val uploader = RecordingUploader()
            val coordinator = buildCoordinator(db, uploader, nowMs = 1_000L)
            insertEpisode(db, "ep1")
            db.audioUploadCacheQueries.upsert(
                episodeId = "ep1",
                geminiUri = "https://gemini/old",
                geminiName = "files/old",
                mimeType = "audio/mpeg",
                fingerprint = "1000000",
                uploadedAtMs = 0L,
                expiresAtMs = 1_000L + AudioUploadCoordinator.DEFAULT_TTL_MS,
            )

            val acquired =
                coordinator
                    .acquire(
                        apiKey = "k",
                        episode = episode("ep1"),
                        // Different byte count → fingerprint mismatch.
                        download = download("ep1", bytes = 5_000_000L),
                    ).getOrThrow()

            assertEquals(false, acquired.fromCache)
            assertEquals(1, uploader.calls.size, "Fingerprint mismatch must force a fresh upload")
            val cached = db.audioUploadCacheQueries.selectByEpisode("ep1").executeAsOneOrNull()
            assertEquals("5000000", cached?.fingerprint, "Fresh upload must replace the stale fingerprint")
        }

    @Test
    fun acquire_cacheHit_butWithinSafetyMargin_uploadsFresh() =
        runTest {
            // Cached row's expiry is within 1h of now. We refuse to hand back
            // the URI to absorb clock skew + Gemini-side propagation lag.
            val db = inMemoryDatabase()
            val uploader = RecordingUploader()
            // Set the clock so the cached row expires in 30 minutes —
            // inside the 1h safety margin baked into the coordinator.
            val nowMs = 100_000L
            val coordinator = buildCoordinator(db, uploader, nowMs = nowMs)
            insertEpisode(db, "ep1")
            db.audioUploadCacheQueries.upsert(
                episodeId = "ep1",
                geminiUri = "https://gemini/about-to-expire",
                geminiName = "files/about-to-expire",
                mimeType = "audio/mpeg",
                fingerprint = "5000000",
                uploadedAtMs = nowMs - 47L * 3600 * 1000,
                expiresAtMs = nowMs + 30L * 60 * 1000,
            )

            val acquired =
                coordinator
                    .acquire(
                        apiKey = "k",
                        episode = episode("ep1"),
                        download = download("ep1", bytes = 5_000_000L),
                    ).getOrThrow()

            assertEquals(false, acquired.fromCache, "Within safety margin → fresh upload, not reuse")
            assertEquals(1, uploader.calls.size)
        }

    @Test
    fun acquire_uploadFailure_surfacesAsAiError_andDoesNotCacheRow() =
        runTest {
            // Network failure during upload must surface the underlying AiError
            // and leave the cache empty so a retry hits the upload path again.
            val db = inMemoryDatabase()
            val uploader =
                RecordingUploader(handler = {
                    Result.failure(AiErrorException(AiError.Network))
                })
            val coordinator = buildCoordinator(db, uploader)
            insertEpisode(db, "ep1")

            val result =
                coordinator.acquire(
                    apiKey = "k",
                    episode = episode("ep1"),
                    download = download("ep1", bytes = 1_000L),
                )

            assertTrue(result.isFailure, "Upload failure must propagate as Result.failure")
            val err = assertFailsWith<AiErrorException> { result.getOrThrow() }
            assertEquals(AiError.Network, err.error)
            assertNull(
                db.audioUploadCacheQueries.selectByEpisode("ep1").executeAsOneOrNull(),
                "Failed upload must NOT populate the cache",
            )
        }

    @Test
    fun clearAll_emptiesCacheTable() =
        runTest {
            val db = inMemoryDatabase()
            val coordinator = buildCoordinator(db, RecordingUploader())
            insertEpisode(db, "ep1")
            insertEpisode(db, "ep2")
            db.audioUploadCacheQueries.upsert("ep1", "u1", "n1", "audio/mpeg", "1", 0L, Long.MAX_VALUE)
            db.audioUploadCacheQueries.upsert("ep2", "u2", "n2", "audio/mpeg", "2", 0L, Long.MAX_VALUE)

            coordinator.clearAll()

            assertNull(db.audioUploadCacheQueries.selectByEpisode("ep1").executeAsOneOrNull())
            assertNull(db.audioUploadCacheQueries.selectByEpisode("ep2").executeAsOneOrNull())
        }

    // -----------------------------------------------------------------------

    private fun buildCoordinator(
        db: com.kofikodr.kofipod.db.KofipodDatabase,
        uploader: RecordingUploader,
        nowMs: Long = 1_000L,
    ): AudioUploadCoordinator =
        AudioUploadCoordinator(
            uploader = uploader,
            db = db,
            openFile = { ByteReadChannel.Empty },
            clock = FixedClock(nowMs),
            ioContext = kotlinx.coroutines.Dispatchers.Unconfined,
        )

    private fun insertEpisode(
        db: com.kofikodr.kofipod.db.KofipodDatabase,
        episodeId: String,
    ) {
        db.podcastQueries.insert(
            id = "pod1",
            title = "Pod",
            author = "Author",
            description = "",
            artworkUrl = "",
            feedUrl = "https://example.com/feed",
            listId = null,
            autoDownloadEnabled = 0L,
            notifyNewEpisodesEnabled = 0L,
            lastCheckedAt = 0L,
            addedAt = 0L,
            primaryCategory = "",
        )
        db.episodeQueries.insert(
            id = episodeId,
            podcastId = "pod1",
            guid = "g-$episodeId",
            title = "T",
            description = "",
            publishedAt = 0L,
            durationSec = 600L,
            enclosureUrl = "https://example.com/audio.mp3",
            enclosureMimeType = "audio/mpeg",
            fileSizeBytes = 0L,
            seasonNumber = null,
            episodeNumber = null,
            imageUrl = "",
            chaptersUrl = null,
            transcriptUrl = null,
        )
    }

    private fun episode(
        id: String,
        mime: String = "audio/mpeg",
    ): Episode =
        Episode(
            id = id,
            podcastId = "pod1",
            guid = "g-$id",
            title = "T",
            description = "",
            publishedAt = 0L,
            durationSec = 600L,
            enclosureUrl = "https://example.com/audio.mp3",
            enclosureMimeType = mime,
            fileSizeBytes = 0L,
            seasonNumber = null,
            episodeNumber = null,
            imageUrl = "",
            chaptersUrl = null,
            transcriptUrl = null,
        )

    private fun download(
        episodeId: String,
        bytes: Long,
    ): Download =
        Download(
            episodeId = episodeId,
            state = "Completed",
            localPath = "/tmp/$episodeId.mp3",
            downloadedBytes = bytes,
            totalBytes = bytes,
            source = "manual",
            startedAt = 0L,
            completedAt = 0L,
            errorMessage = null,
        )

    private class RecordingUploader(
        private val handler: suspend (StubUpload) -> Result<UploadedFile> = { call ->
            Result.success(
                UploadedFile(
                    name = "files/${call.displayName}",
                    uri = "https://gemini/${call.displayName}",
                    mimeType = call.mimeType,
                    state = "ACTIVE",
                ),
            )
        },
    ) : AudioUploader {
        val calls: MutableList<StubUpload> = mutableListOf()

        override suspend fun upload(
            apiKey: String,
            channel: ByteReadChannel,
            mimeType: String,
            sizeBytes: Long,
            displayName: String,
        ): Result<UploadedFile> {
            val call = StubUpload(apiKey, mimeType, sizeBytes, displayName)
            calls += call
            return handler(call)
        }
    }

    private data class StubUpload(
        val apiKey: String,
        val mimeType: String,
        val sizeBytes: Long,
        val displayName: String,
    )

    private class FixedClock(private val ms: Long) : kotlinx.datetime.Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(ms)
    }
}
