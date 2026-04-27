// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

import app.kofipod.data.repo.EpisodeSource
import app.kofipod.data.repo.RefreshResult
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.db.Episode
import app.kofipod.db.KofipodDatabase
import app.kofipod.testing.inMemoryDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins [AiSummaryRepository]'s observable contract and the transcript pipeline.
 *
 * The repository is the single chokepoint between the Summary tab UI and Gemini —
 * misclassifying a state (e.g. emitting Idle while in-flight, or Hidden when a key
 * is configured) lights up the wrong branch in the panel composable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiSummaryRepositoryTest {
    @Test
    fun observeFor_returnsHidden_whenNoKeyConfigured() =
        runTest {
            val (repo, db) = build(initialKey = null)
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")

            val state = repo.observeFor("ep1").first()

            assertEquals(
                AiSummaryUiState.Hidden,
                state,
                "Without a Gemini key the Summary tab is hidden — the panel must not render anything",
            )
        }

    @Test
    fun observeFor_returnsIdleTranscript_whenKeyConfigured_andTranscriptUrlPresent() =
        runTest {
            val (repo, db) = build(initialKey = "k")
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")

            val state = repo.observeFor("ep1").first()

            assertEquals(AiSummaryUiState.Idle(AiSourceKind.Transcript), state)
        }

    @Test
    fun observeFor_returnsIdleNull_whenKeyConfigured_butNoTranscript() =
        runTest {
            // Slice 2: no transcript means the panel offers no Generate button.
            // Slice 2.5 widens this to Audio when the episode is downloaded.
            val (repo, db) = build(initialKey = "k")
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "")

            val state = repo.observeFor("ep1").first()

            assertEquals(AiSummaryUiState.Idle(available = null), state)
        }

    @Test
    fun observeFor_returnsReadyNotStale_whenCachedFingerprintMatches() =
        runTest {
            val (repo, db) = build(initialKey = "k")
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")
            db.episodeAiSummaryQueries.upsert(
                episodeId = "ep1",
                generatedAtMs = 100L,
                modelId = GeminiModel.Flash.apiId,
                sourceKind = AiSourceKind.Transcript.wire,
                sourceFingerprint = "https://example.com/t.vtt",
                summary = "S",
                peopleJson = "[]",
                thingsJson = "[]",
                linksJson = "[]",
            )

            val state = repo.observeFor("ep1").first()

            val ready = assertIs<AiSummaryUiState.Ready>(state)
            assertEquals(false, ready.stale, "Matching fingerprint must not flag as stale")
        }

    @Test
    fun observeFor_returnsReadyStale_whenTranscriptUrlChanged() =
        runTest {
            // The publisher swapped the transcript URL — the cached summary covers the
            // old version. UI must surface Regenerate, not silently keep showing the old.
            val (repo, db) = build(initialKey = "k")
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/v2.vtt")
            db.episodeAiSummaryQueries.upsert(
                episodeId = "ep1",
                generatedAtMs = 100L,
                modelId = GeminiModel.Flash.apiId,
                sourceKind = AiSourceKind.Transcript.wire,
                sourceFingerprint = "https://example.com/v1.vtt",
                summary = "S",
                peopleJson = "[]",
                thingsJson = "[]",
                linksJson = "[]",
            )

            val state = repo.observeFor("ep1").first()

            val ready = assertIs<AiSummaryUiState.Ready>(state)
            assertEquals(true, ready.stale, "Different transcriptUrl must flag the cache as stale")
        }

    @Test
    fun generate_persistsSummary_onTranscriptHappyPath() =
        runTest {
            val (repo, db) =
                build(
                    initialKey = "k",
                    transcripts = StubTranscriptFetcher.success("WEBVTT\n\n00:00.000 --> 00:01.000\nHello world."),
                    summariser = StubSummariser(returns = Result.success("Episode summary body.")),
                )
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")

            repo.generate("ep1")
            advanceUntilIdle()

            val state = repo.observeFor("ep1").first()
            val ready = assertIs<AiSummaryUiState.Ready>(state, "Happy path must end in Ready, got $state")
            assertEquals("Episode summary body.", ready.summary.summary)
            assertEquals(AiSourceKind.Transcript, ready.summary.sourceKind)
            assertEquals("https://example.com/t.vtt", ready.summary.sourceFingerprint)
            assertEquals(GeminiModel.Flash.apiId, ready.summary.modelId)
            assertEquals(
                false,
                ready.stale,
                "Freshly-persisted summary must NOT be flagged stale — closes the round-trip " +
                    "so a fingerprint-comparison regression can't slip past the pipeline test",
            )
        }

    @Test
    fun generate_emitsTranscriptUnavailable_whenTranscriptFetch4xx() =
        runTest {
            // The publisher's transcript URL 404s. We must NOT silently fall back to
            // audio — that would burn the user's quota without their consent.
            val (repo, db) =
                build(
                    initialKey = "k",
                    transcripts = StubTranscriptFetcher.failure(AiError.TranscriptUnavailable),
                    summariser = StubSummariser(returns = Result.success("never called")),
                )
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/missing.vtt")

            repo.generate("ep1")
            advanceUntilIdle()

            val state = repo.observeFor("ep1").first()
            val error = assertIs<AiSummaryUiState.Error>(state)
            assertEquals(AiError.TranscriptUnavailable, error.error)
        }

    @Test
    fun generate_emitsRateLimited_whenSummariserReturns429() =
        runTest {
            val (repo, db) =
                build(
                    initialKey = "k",
                    transcripts = StubTranscriptFetcher.success("plain transcript text"),
                    summariser = StubSummariser(returns = Result.failure(AiErrorException(AiError.RateLimited))),
                )
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")

            repo.generate("ep1")
            advanceUntilIdle()

            val state = repo.observeFor("ep1").first()
            val error = assertIs<AiSummaryUiState.Error>(state)
            assertEquals(AiError.RateLimited, error.error)
        }

    @Test
    fun generate_whileAlreadyInFlight_isNoOp_andDoesNotDoubleFire() =
        runTest {
            // Two `generate("ep1")` calls in quick succession — e.g. a tap on
            // Generate followed by a process-recreate that re-binds the screen
            // and re-issues the auto-generate — must collapse to a single
            // network round-trip. Otherwise we'd race on `upsert()` and burn
            // double the user's quota / rate-limit budget.
            val gate = CompletableDeferred<Result<String>>()
            val transcripts = StubTranscriptFetcher { gate.await() }
            val summariser = StubSummariser(returns = Result.success("done"))
            val (repo, db) = build(initialKey = "k", transcripts = transcripts, summariser = summariser)
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")

            repo.generate("ep1")
            repo.generate("ep1") // must short-circuit on the inFlight guard
            // Allow both launches to dispatch through the lock + register
            // inFlight before we release the gate.
            testScheduler.runCurrent()
            gate.complete(Result.success("WEBVTT\n\nbody"))
            advanceUntilIdle()

            assertEquals(
                1,
                summariser.callCount,
                "Synchronous double-tap must collapse to a single Gemini call — " +
                    "otherwise concurrent upserts race and quota is wasted",
            )
        }

    @Test
    fun generate_clearsTransientError_whenFollowupSucceeds() =
        runTest {
            // First attempt returns RateLimited → UI shows Error card. User taps
            // Retry. The retry must end in Ready, not a lingering Error — the
            // combine() check has Error before the cached branch, so without an
            // explicit clear on persist (or on next-run reset) the Error would
            // win even though a fresh summary just landed in the DB.
            var attempt = 0
            val summariser =
                StubSummariser {
                    attempt += 1
                    if (attempt == 1) {
                        Result.failure(AiErrorException(AiError.RateLimited))
                    } else {
                        Result.success("Episode summary body.")
                    }
                }
            val (repo, db) =
                build(
                    initialKey = "k",
                    transcripts = StubTranscriptFetcher.success("plain transcript text"),
                    summariser = summariser,
                )
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")

            repo.generate("ep1")
            advanceUntilIdle()
            assertIs<AiSummaryUiState.Error>(
                repo.observeFor("ep1").first(),
                "Fixture: first attempt must surface Error before the retry runs",
            )

            repo.generate("ep1")
            advanceUntilIdle()

            val terminal = repo.observeFor("ep1").first()
            val ready = assertIs<AiSummaryUiState.Ready>(terminal, "Successful retry must end in Ready, got $terminal")
            assertEquals("Episode summary body.", ready.summary.summary)
            assertEquals(2, summariser.callCount, "Both attempts must reach Gemini — single-flight only blocks concurrent overlap")
        }

    @Test
    fun clearAll_emptiesAllCachedSummaries() =
        runTest {
            // Slice 4 wires Disconnect to call this. Verifying the contract here so
            // the Slice 4 task stays a one-line wire-up.
            val (repo, db) = build(initialKey = "k")
            db.episodeAiSummaryQueries.upsert(
                episodeId = "ep1",
                generatedAtMs = 1L,
                modelId = GeminiModel.Flash.apiId,
                sourceKind = AiSourceKind.Transcript.wire,
                sourceFingerprint = "u",
                summary = "S",
                peopleJson = "[]",
                thingsJson = "[]",
                linksJson = "[]",
            )

            repo.clearAll()

            val rows = db.episodeAiSummaryQueries.selectByEpisode("ep1").executeAsList()
            assertTrue(rows.isEmpty(), "clearAll must wipe the table — Disconnect promises this")
        }

    // -----------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------

    private data class Fixture(
        val repo: AiSummaryRepository,
        val db: KofipodDatabase,
    )

    private fun TestScope.build(
        initialKey: String?,
        transcripts: TranscriptFetcher = StubTranscriptFetcher.success(""),
        summariser: TextSummariser = StubSummariser(returns = Result.success("default")),
    ): Fixture {
        // Use the test scheduler for SQLDelight flow emissions too — without this,
        // SettingsRepository's `Dispatchers.Default` flowContext prevents
        // `runTest`'s scheduler from observing model() / aiModel() emissions and
        // the pipeline parks forever inside `aiConfig.model().first()`.
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val db = inMemoryDatabase()
        val vault = SimpleFakeVault(initialKey)
        val settings = SettingsRepository(db, flowContext = testDispatcher)
        val coroutineScope = CoroutineScope(testDispatcher)
        val aiConfig = AiConfigRepository(keyVault = vault, settings = settings, appScope = coroutineScope)
        testScheduler.runCurrent()
        val repo =
            AiSummaryRepository(
                db = db,
                aiConfig = aiConfig,
                summariser = summariser,
                transcripts = transcripts,
                episodes = DbEpisodeSource(db),
                appScope = coroutineScope,
                ioContext = testDispatcher,
            )
        return Fixture(repo, db)
    }

    private fun insertEpisode(
        db: KofipodDatabase,
        episodeId: String,
        transcriptUrl: String,
    ) {
        // Prerequisite: a Podcast row, since Episode FKs into it.
        db.podcastQueries.insert(
            id = "pod1",
            title = "Test Pod",
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
            description = "D",
            publishedAt = 0L,
            durationSec = 600L,
            enclosureUrl = "https://example.com/audio.mp3",
            enclosureMimeType = "audio/mpeg",
            fileSizeBytes = 0L,
            seasonNumber = null,
            episodeNumber = null,
            imageUrl = "",
            chaptersUrl = null,
            transcriptUrl = transcriptUrl.takeIf { it.isNotBlank() },
        )
    }
}

private class SimpleFakeVault(initial: String?) : KeyVault {
    private var stored: String? = initial

    override suspend fun get(): String? = stored?.takeIf { it.isNotBlank() }

    override suspend fun set(value: String) {
        stored = value
    }

    override suspend fun clear() {
        stored = null
    }
}

private class StubSummariser(
    private val handler: suspend () -> Result<String>,
) : TextSummariser {
    constructor(returns: Result<String>) : this({ returns })

    var callCount: Int = 0
        private set

    override suspend fun generateFromText(
        apiKey: String,
        model: GeminiModel,
        prompt: String,
        content: String,
    ): Result<String> {
        callCount += 1
        return handler()
    }
}

private class StubTranscriptFetcher(
    private val handler: suspend () -> Result<String>,
) : TranscriptFetcher {
    constructor(returns: Result<String>) : this({ returns })

    override suspend fun fetch(url: String): Result<String> = handler()

    companion object {
        fun success(body: String): StubTranscriptFetcher = StubTranscriptFetcher(Result.success(body))

        fun failure(error: AiError): StubTranscriptFetcher = StubTranscriptFetcher(Result.failure(AiErrorException(error)))
    }
}

private class DbEpisodeSource(private val db: KofipodDatabase) : EpisodeSource {
    override fun episodesFlow(podcastId: String): Flow<List<Episode>> = flowOf(emptyList())

    override fun episodeFlow(episodeId: String): Flow<Episode?> = flowOf(db.episodeQueries.selectById(episodeId).executeAsOneOrNull())

    override fun newEpisodeCountsFlow(): Flow<Map<String, Int>> = flowOf(emptyMap())

    override suspend fun refresh(
        podcastId: String,
        feedId: Long,
        nowMillis: Long,
    ): RefreshResult = RefreshResult(emptyList(), 0)
}
