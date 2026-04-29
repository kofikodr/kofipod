// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.kofipod.background.AiSummaryScheduler
import app.kofipod.data.repo.EpisodeSource
import app.kofipod.data.repo.RefreshResult
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.db.Download
import app.kofipod.db.Episode
import app.kofipod.db.KofipodDatabase
import app.kofipod.testing.inMemoryDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
    fun observeFor_returnsIdleAudio_whenNoTranscript_butEpisodeIsDownloaded() =
        runTest {
            // Audio fallback (Slice 2.5) widens the previous "Idle(null)" branch:
            // a downloaded episode without a publisher transcript can still be
            // summarised by sending the audio file to Gemini's Files API.
            val (repo, db) = build(initialKey = "k")
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "")
            insertCompletedDownload(db, episodeId = "ep1", localPath = "/tmp/ep1.mp3", downloadedBytes = 12_345)

            val state = repo.observeFor("ep1").first()

            assertEquals(AiSummaryUiState.Idle(AiSourceKind.Audio), state)
        }

    @Test
    fun observeFor_returnsIdleNull_whenAudioFallbackDisabled_onIosLikePlatform() =
        runTest {
            // iOS does not yet wire `openLocalFileChannel`. The repo must NOT
            // surface Idle(Audio) on platforms where the pipeline can't run —
            // otherwise the user gets a Generate button that always fails.
            val (repo, db) = build(initialKey = "k", audioFallbackEnabled = false)
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "")
            insertCompletedDownload(db, episodeId = "ep1", localPath = "/tmp/ep1.mp3", downloadedBytes = 1_000)

            val state = repo.observeFor("ep1").first()

            assertEquals(AiSummaryUiState.Idle(available = null), state)
        }

    @Test
    fun observeFor_returnsIdleNull_whenDownloadIsNotCompleted() =
        runTest {
            // Partial / paused / errored downloads aren't summarisable — Gemini
            // would receive a truncated file and emit a 400 INVALID_ARGUMENT.
            // pickSource must keep the Generate button hidden until the file
            // is fully on disk.
            val (repo, db) = build(initialKey = "k")
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "")
            db.downloadQueries.upsert(
                episodeId = "ep1",
                state = "Downloading",
                localPath = "/tmp/ep1.partial",
                downloadedBytes = 500,
                totalBytes = 12_345,
                source = "manual",
                startedAt = 0L,
                completedAt = null,
                errorMessage = null,
            )

            val state = repo.observeFor("ep1").first()

            assertEquals(AiSummaryUiState.Idle(available = null), state)
        }

    @Test
    fun observeFor_returnsReadyStale_whenAudioByteCountChanged() =
        runTest {
            // Re-downloading the same episode (publisher re-encoded, partial
            // repair, etc.) lands a different byte count. The cached audio
            // summary covers the old bytes — UI must surface Regenerate.
            val (repo, db) = build(initialKey = "k")
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "")
            insertCompletedDownload(db, episodeId = "ep1", localPath = "/tmp/ep1.mp3", downloadedBytes = 99_999)
            db.episodeAiSummaryQueries.upsert(
                episodeId = "ep1",
                generatedAtMs = 100L,
                modelId = GeminiModel.Flash.apiId,
                sourceKind = AiSourceKind.Audio.wire,
                // 12345 = pre-redownload byte count; current download lands at 99999 → must be stale.
                sourceFingerprint = "12345",
                summary = "S",
                peopleJson = "[]",
                thingsJson = "[]",
                linksJson = "[]",
            )

            val state = repo.observeFor("ep1").first()

            val ready = assertIs<AiSummaryUiState.Ready>(state)
            assertEquals(true, ready.stale, "Different downloadedBytes must flag the cached audio summary as stale")
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
                    summariser = StubSummariser(returns = StubSummariser.summary("Episode summary body.")),
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
    fun generate_audioPath_persistsAudioSummary_andClearsTransientError() =
        runTest {
            // Audio happy path: no transcript URL, episode is downloaded → repo
            // routes through AudioSummariser, persists with sourceKind=Audio
            // and the byte-count fingerprint, and ends in Ready (not stale).
            // Pins the load-bearing wiring: AudioSummariser receives the right
            // path / mime / size, the persisted row keeps the fingerprint we
            // just summarised, and the source kind is preserved.
            val audio = StubAudioSummariser(returns = StubAudioSummariser.summary("Audio episode summary."))
            val (repo, db) = build(initialKey = "k", audio = audio)
            insertEpisode(
                db,
                episodeId = "ep1",
                transcriptUrl = "",
                // 30 min — well under the 8h soft cap.
                durationSec = 1800L,
                enclosureMimeType = "audio/x-m4a",
            )
            insertCompletedDownload(db, episodeId = "ep1", localPath = "/tmp/ep1.m4a", downloadedBytes = 9_876_543)

            repo.generate("ep1")
            advanceUntilIdle()

            assertEquals(1, audio.calls.size, "Audio path must reach AudioSummariser exactly once")
            val call = audio.calls[0]
            assertEquals("k", call.apiKey)
            assertEquals("/tmp/ep1.m4a", call.localPath)
            assertEquals("audio/x-m4a", call.mimeType)
            assertEquals(9_876_543L, call.sizeBytes)

            val state = repo.observeFor("ep1").first()
            val ready = assertIs<AiSummaryUiState.Ready>(state, "Audio happy path must end in Ready, got $state")
            assertEquals("Audio episode summary.", ready.summary.summary)
            assertEquals(AiSourceKind.Audio, ready.summary.sourceKind)
            assertEquals(
                "9876543",
                ready.summary.sourceFingerprint,
                "Audio fingerprint must be the decimal byte count — drives stale detection on re-download",
            )
            assertEquals(false, ready.stale, "Freshly-persisted audio summary must NOT be stale")
        }

    @Test
    fun generate_audioPath_surfacesError_whenSummariserFails() =
        runTest {
            // The runAudio `getOrElse { surface(...) }` block is the only path
            // that takes the repository from Generating → Error on the audio
            // side. Without this test, a regression that drops or rewires the
            // error surfacing would leave the panel stuck in Generating
            // forever (no exception, no error card, no retry).
            val audio =
                StubAudioSummariser(
                    returns = Result.failure(AiErrorException(AiError.RateLimited)),
                )
            val (repo, db) = build(initialKey = "k", audio = audio)
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "", durationSec = 1800L)
            insertCompletedDownload(db, episodeId = "ep1", localPath = "/tmp/ep1.mp3", downloadedBytes = 1_000_000)

            repo.generate("ep1")
            advanceUntilIdle()

            val state = repo.observeFor("ep1").first()
            val error = assertIs<AiSummaryUiState.Error>(state, "Audio summariser failure must surface as Error, got $state")
            assertEquals(AiError.RateLimited, error.error)
        }

    @Test
    fun generate_audioPath_allowsExactly8h_atTheBoundary() =
        runTest {
            // The cap uses strict `>`, so an episode of exactly 8h must be
            // allowed through to the summariser. Pins the boundary so a
            // refactor flipping `>` to `>=` doesn't silently exclude
            // 8-hour-on-the-nose episodes the spec says are permitted.
            val audio = StubAudioSummariser(returns = StubAudioSummariser.summary("Boundary summary."))
            val (repo, db) = build(initialKey = "k", audio = audio)
            insertEpisode(
                db,
                episodeId = "ep1",
                transcriptUrl = "",
                durationSec = 8L * 3600,
            )
            insertCompletedDownload(db, episodeId = "ep1", localPath = "/tmp/ep1.mp3", downloadedBytes = 1_000_000)

            repo.generate("ep1")
            advanceUntilIdle()

            assertEquals(1, audio.calls.size, "8h-on-the-nose must be allowed — strict-greater-than boundary")
            val state = repo.observeFor("ep1").first()
            assertIs<AiSummaryUiState.Ready>(state, "Boundary case must complete to Ready, got $state")
        }

    @Test
    fun generate_audioPath_emitsAudioTooLong_when8hCap() =
        runTest {
            // A 12-hour episode would survive the upload only to be rejected by
            // Gemini for exceeding the context window. We fail fast here so the
            // user doesn't pay 30s of wait + bandwidth for an inevitable error.
            val audio = StubAudioSummariser(returns = StubAudioSummariser.summary("never called"))
            val (repo, db) = build(initialKey = "k", audio = audio)
            insertEpisode(
                db,
                episodeId = "ep1",
                transcriptUrl = "",
                // 12 hours — past the 8h soft cap.
                durationSec = 12L * 3600,
            )
            insertCompletedDownload(db, episodeId = "ep1", localPath = "/tmp/ep1.mp3", downloadedBytes = 1_000_000)

            repo.generate("ep1")
            advanceUntilIdle()

            assertTrue(
                audio.calls.isEmpty(),
                "Episodes past the 8h cap must not reach the upload step — burns user quota for no gain",
            )
            val state = repo.observeFor("ep1").first()
            val error = assertIs<AiSummaryUiState.Error>(state)
            assertEquals(AiError.AudioTooLong, error.error)
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
                    summariser = StubSummariser(returns = StubSummariser.summary("never called")),
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
            val summariser = StubSummariser(returns = StubSummariser.summary("done"))
            val (repo, db, _, scheduler) =
                build(initialKey = "k", transcripts = transcripts, summariser = summariser)
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
            // The scheduler call is intentionally outside the single-flight
            // guard — bursts of taps are expected to enqueue twice, but
            // WorkManager's KEEP policy collapses them to one queued worker.
            // Pinning the count here prevents a future "guard the enqueue
            // too" change from being a silent semantic flip.
            assertEquals(
                2,
                scheduler.enqueueCount,
                "Double-tap must enqueue twice — KEEP policy on the WorkManager side absorbs the duplication",
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
                        StubSummariser.summary("Episode summary body.")
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
    fun clearAll_cancelsInFlightGeneration_andDoesNotPersistRevokedRow() =
        runTest {
            // Disconnect race: a long upload is mid-flight when the user hits
            // Disconnect. Without explicit cancel + a key re-check before
            // upsert, the job would complete after `clearAll()` had wiped the
            // table and silently insert a row generated under the old key.
            // On the next `connect()` with a different key, that row would
            // resurface — content from key K1 visible under key K2.
            val gate = CompletableDeferred<Result<AiSummaryJson>>()
            val audio = StubAudioSummariser { gate.await() }
            val fixture =
                build(
                    initialKey = "k",
                    audio = audio,
                )
            val (repo, db, aiConfig) = fixture
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "", durationSec = 1800L)
            insertCompletedDownload(db, episodeId = "ep1", localPath = "/tmp/ep1.mp3", downloadedBytes = 1_000_000)

            repo.generate("ep1")
            // Let the pipeline reach `audio.summariseAudio`, where it parks on
            // the gate awaiting Disconnect — without `runCurrent` the launch
            // hasn't dispatched yet and clearAll has nothing to cancel.
            testScheduler.runCurrent()

            // User taps Disconnect → AiConfigRepository wipes the vault, then
            // AiSummaryRepository.clearAll cancels jobs + wipes the table.
            // This is the order AiSetupViewModel.confirmDisconnect uses.
            aiConfig.disconnect()
            repo.clearAll()
            // Release the gate AFTER clearAll has returned. Any upsert behind
            // it must be a no-op — either because the job was cancelled, or
            // because the in-pipeline currentKey() check sees a null vault.
            gate.complete(StubAudioSummariser.summary("LATE summary that must NOT be persisted"))
            advanceUntilIdle()

            val rows = db.episodeAiSummaryQueries.selectByEpisode("ep1").executeAsList()
            assertTrue(
                rows.isEmpty(),
                "clearAll must cancel in-flight pipelines so a late upsert can't leak old-key content past Disconnect",
            )
        }

    @Test
    fun cancel_dropsInFlightAudio_andDoesNotPersistRow() =
        runTest {
            // Cancel must short-circuit a long upload without surfacing an error
            // — the user explicitly opted out, so we land back on Idle (or
            // whatever cached state existed). Unlike clearAll, the cached
            // summary table is untouched: cancelling a regenerate must not
            // wipe the prior summary.
            val gate = CompletableDeferred<Result<AiSummaryJson>>()
            val audio = StubAudioSummariser { gate.await() }
            val (repo, db) = build(initialKey = "k", audio = audio)
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "", durationSec = 1800L)
            insertCompletedDownload(db, episodeId = "ep1", localPath = "/tmp/ep1.mp3", downloadedBytes = 1_000_000)

            repo.generate("ep1")
            // Park the pipeline inside the summariser so cancel has something to interrupt.
            testScheduler.runCurrent()

            // Pin the precondition: the panel sees Generating before we cancel.
            // Without this assertion, the test would also pass if `generate()`
            // silently never started — making the "cancel works" claim weak.
            val before = repo.observeFor("ep1").first()
            assertIs<AiSummaryUiState.Generating>(
                before,
                "Pipeline must reach Generating before cancel — otherwise we're not exercising the abort path",
            )

            repo.cancel("ep1")
            // Release the gate AFTER cancel. The cancelled job's `finally` is
            // already running by this point — the late completion must be a no-op.
            gate.complete(StubAudioSummariser.summary("LATE"))
            advanceUntilIdle()

            val rows = db.episodeAiSummaryQueries.selectByEpisode("ep1").executeAsList()
            assertTrue(
                rows.isEmpty(),
                "cancel() must drop the in-flight pipeline so a late upsert can't persist after the user opted out",
            )
            val state = repo.observeFor("ep1").first()
            // No prior cached summary → falls back to Idle. The Generating
            // chrome must be gone.
            assertEquals(AiSummaryUiState.Idle(AiSourceKind.Audio), state)
        }

    @Test
    fun generate_audioPath_emitsAnalysingStage_afterUploadFinalises() =
        runTest {
            // The whole point of the staged progress card is that the panel
            // can see *which* step is running. The repo wires the Audio path's
            // onStage callback into setStage(); without an assertion that the
            // observable Generating state actually flips to Analysing when
            // the summariser invokes onStage, that wiring could regress
            // silently — the panel would just sit on Preparing forever.
            val gate = CompletableDeferred<Result<AiSummaryJson>>()
            val audio =
                StubAudioSummariser { call ->
                    // Simulate the GeminiClient calling back as soon as the
                    // resumable upload finalises and the poll/generate phase
                    // begins. Real production wiring lives in
                    // `GeminiClient.summariseAudio` — this stub is just standing
                    // in for that call site.
                    call.onStage(GenerationStage.Analysing)
                    gate.await()
                }
            val (repo, db) = build(initialKey = "k", audio = audio)
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "", durationSec = 1800L)
            insertCompletedDownload(db, episodeId = "ep1", localPath = "/tmp/ep1.mp3", downloadedBytes = 5_000_000)

            repo.generate("ep1")
            testScheduler.runCurrent()

            val state = repo.observeFor("ep1").first()
            val generating = assertIs<AiSummaryUiState.Generating>(state)
            assertEquals(GenerationStage.Analysing, generating.stage, "onStage(Analysing) must surface in the observable Generating")
            assertEquals(5_000_000L, generating.sizeBytes, "Audio path must carry the upload size into the Generating state")

            // Tidy up so runTest doesn't trip the "uncompleted job" check.
            gate.complete(StubAudioSummariser.summary("done"))
            advanceUntilIdle()
        }

    @Test
    fun generate_clearsProgressStage_onError() =
        runTest {
            // The runGenerate `finally` block clears `progress` — but if a
            // future refactor moved the `setStage(Formatting)` call into a
            // path that runs alongside the error surface, we'd briefly emit
            // Generating(Formatting) before the Error card. This test pins
            // the contract that Error is the terminal state and there's no
            // residual Formatting bleed-through.
            val (repo, db) =
                build(
                    initialKey = "k",
                    transcripts = StubTranscriptFetcher.success("body"),
                    summariser = StubSummariser(returns = Result.failure(AiErrorException(AiError.Network))),
                )
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")

            repo.generate("ep1")
            advanceUntilIdle()

            val state = repo.observeFor("ep1").first()
            assertIs<AiSummaryUiState.Error>(state, "Errored pipeline must end in Error, not a stuck Generating stage")
        }

    @Test
    fun generate_writesPendingMarker_andSchedulesResumeWorker() =
        runTest {
            // Pin the contract: every Generate tap drops a row in
            // PendingAiSummary AND asks the platform scheduler to enqueue the
            // resume worker. Without both, a process death between launch and
            // first dispatch would leave nothing for next-launch recovery.
            val gate = CompletableDeferred<Result<AiSummaryJson>>()
            val audio = StubAudioSummariser { gate.await() }
            val (repo, db, _, scheduler) = build(initialKey = "k", audio = audio)
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "", durationSec = 1800L)
            insertCompletedDownload(db, episodeId = "ep1", localPath = "/tmp/ep1.mp3", downloadedBytes = 1_000)

            repo.generate("ep1")
            testScheduler.runCurrent()

            val pending = db.pendingAiSummaryQueries.selectAll().executeAsList()
            assertEquals(1, pending.size, "Generate must persist a pending marker for resume coverage")
            assertEquals("ep1", pending[0].episodeId)
            assertEquals(1, scheduler.enqueueCount, "Generate must enqueue the resume worker exactly once")

            // Tidy: let the pipeline complete so runTest doesn't gripe.
            gate.complete(StubAudioSummariser.summary("done"))
            advanceUntilIdle()
        }

    @Test
    fun pendingMarker_isDeletedOnSuccess() =
        runTest {
            // Happy-path round-trip: marker exists during the run, gone on
            // success. Without the delete, the worker would re-fire on next
            // launch and re-burn the user's quota for an episode that's
            // already cached.
            val (repo, db) =
                build(
                    initialKey = "k",
                    transcripts = StubTranscriptFetcher.success("body"),
                    summariser = StubSummariser(returns = StubSummariser.summary("S")),
                )
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")

            repo.generate("ep1")
            advanceUntilIdle()

            assertTrue(
                db.pendingAiSummaryQueries.selectAll().executeAsList().isEmpty(),
                "Successful summarisation must clear the pending marker",
            )
        }

    @Test
    fun pendingMarker_isDeletedOnError() =
        runTest {
            // Surfaced errors are the user's call to retry — auto-retrying via
            // the worker would burn quota silently. The marker must be gone
            // by the time the Error card lands.
            val (repo, db) =
                build(
                    initialKey = "k",
                    transcripts = StubTranscriptFetcher.success("body"),
                    summariser = StubSummariser(returns = Result.failure(AiErrorException(AiError.Network))),
                )
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")

            repo.generate("ep1")
            advanceUntilIdle()

            assertTrue(
                db.pendingAiSummaryQueries.selectAll().executeAsList().isEmpty(),
                "Surfaced error must clear the pending marker — user owns the retry decision",
            )
        }

    @Test
    fun pendingMarker_isDeletedOnCancel() =
        runTest {
            // Cancel = user opted out. Worker must not pick this back up and
            // re-run the request without consent.
            val gate = CompletableDeferred<Result<AiSummaryJson>>()
            val audio = StubAudioSummariser { gate.await() }
            val (repo, db) = build(initialKey = "k", audio = audio)
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "", durationSec = 1800L)
            insertCompletedDownload(db, episodeId = "ep1", localPath = "/tmp/ep1.mp3", downloadedBytes = 1_000)

            repo.generate("ep1")
            testScheduler.runCurrent()
            assertTrue(
                db.pendingAiSummaryQueries.selectAll().executeAsList().isNotEmpty(),
                "Fixture: marker must exist during the run for the delete-on-cancel claim to be meaningful",
            )

            repo.cancel("ep1")
            gate.complete(StubAudioSummariser.summary("LATE"))
            advanceUntilIdle()

            assertTrue(
                db.pendingAiSummaryQueries.selectAll().executeAsList().isEmpty(),
                "Cancel must clear the pending marker — worker must not re-fire after user opted out",
            )
        }

    @Test
    fun resumePending_runsMarkersSequentially_notInParallel() =
        runTest {
            // The repo's kdoc explicitly opts for sequential resume to keep
            // metered-network burn predictable — two simultaneous 58 MB audio
            // uploads on cellular would surprise the user. A refactor flipping
            // to `pending.map { async { ... } }.awaitAll()` would not be caught
            // without this test, since a single-marker fixture can't tell
            // sequential from parallel.
            val gateA = CompletableDeferred<String>()
            val gateB = CompletableDeferred<String>()
            val callOrder = mutableListOf<String>()
            // The shared StubTranscriptFetcher discards the URL — use a bare
            // TranscriptFetcher so we can branch on which marker is being
            // resumed, then gate each branch on a separate CompletableDeferred.
            val transcripts =
                TranscriptFetcher { url ->
                    callOrder += url
                    val gate = if (url.endsWith("a.vtt")) gateA else gateB
                    Result.success(gate.await())
                }
            val (repo, db) =
                build(
                    initialKey = "k",
                    transcripts = transcripts,
                    summariser = StubSummariser(returns = StubSummariser.summary("S")),
                )
            insertEpisode(db, episodeId = "epA", transcriptUrl = "https://example.com/a.vtt")
            insertEpisode(
                db,
                episodeId = "epB",
                transcriptUrl = "https://example.com/b.vtt",
                // Different podcast id would normally be needed; the fixture
                // uses the same Podcast row across episodes (insertEpisode
                // re-upserts pod1) so this is fine.
            )
            db.pendingAiSummaryQueries.upsert("epA", 0L)
            db.pendingAiSummaryQueries.upsert("epB", 0L)

            val resume = backgroundScope.launch { repo.resumePending() }
            // Park inside the first gate; without sequential semantics, the
            // second fetch would also be in flight by now.
            testScheduler.runCurrent()
            assertEquals(listOf("https://example.com/a.vtt"), callOrder, "Sequential resume must not start B before A completes")

            // Release A → B should now run.
            gateA.complete("body-a")
            testScheduler.runCurrent()
            assertEquals(
                listOf("https://example.com/a.vtt", "https://example.com/b.vtt"),
                callOrder,
                "B must start only after A's pipeline completes",
            )

            gateB.complete("body-b")
            resume.join()
        }

    @Test
    fun resumePending_drivesPipelineForEachMarker() =
        runTest {
            // The worker (and on-init resume hook) call this directly. Any
            // marker present at the moment of the call must drive the
            // corresponding pipeline through to terminal state.
            val (repo, db) =
                build(
                    initialKey = "k",
                    transcripts = StubTranscriptFetcher.success("body"),
                    summariser = StubSummariser(returns = StubSummariser.summary("recovered")),
                )
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")
            // Simulate "process died last time before runGenerate's finally
            // could clear the marker" by writing one directly without going
            // through generate().
            db.pendingAiSummaryQueries.upsert("ep1", 0L)

            repo.resumePending()
            advanceUntilIdle()

            val state = repo.observeFor("ep1").first()
            val ready = assertIs<AiSummaryUiState.Ready>(state, "resumePending must drive the pipeline to Ready, got $state")
            assertEquals("recovered", ready.summary.summary)
            assertTrue(
                db.pendingAiSummaryQueries.selectAll().executeAsList().isEmpty(),
                "Successful resume must clear the marker, just like a fresh generate()",
            )
        }

    @Test
    fun clearAll_alsoWipesPendingMarkers() =
        runTest {
            // Disconnect must not leave behind markers — the worker would
            // resume requests against a vault that no longer holds a key.
            val (repo, db) = build(initialKey = "k")
            db.pendingAiSummaryQueries.upsert("ep1", 0L)

            repo.clearAll()

            assertTrue(
                db.pendingAiSummaryQueries.selectAll().executeAsList().isEmpty(),
                "clearAll must wipe pending markers alongside the cached summary table",
            )
        }

    @Test
    fun cancel_isNoOp_whenNoActivePipeline() =
        runTest {
            // A bare cancel with nothing in flight must not crash, fire stale
            // state changes, or otherwise leave the repo in a bad place — UI
            // can call this defensively (e.g. on screen disposal).
            val (repo, db) = build(initialKey = "k")
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")

            repo.cancel("ep1")
            advanceUntilIdle()

            val state = repo.observeFor("ep1").first()
            assertEquals(AiSummaryUiState.Idle(AiSourceKind.Transcript), state)
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
        val aiConfig: AiConfigRepository,
        val scheduler: RecordingScheduler,
    )

    private class RecordingScheduler : AiSummaryScheduler {
        var enqueueCount: Int = 0
            private set

        override fun enqueueResume() {
            enqueueCount += 1
        }
    }

    private fun TestScope.build(
        initialKey: String?,
        transcripts: TranscriptFetcher = StubTranscriptFetcher.success(""),
        summariser: TextSummariser = StubSummariser(returns = StubSummariser.summary("default")),
        audio: AudioSummariser = StubAudioSummariser(returns = StubAudioSummariser.summary("audio default")),
        audioFallbackEnabled: Boolean = true,
        scheduler: RecordingScheduler = RecordingScheduler(),
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
                audio = audio,
                transcripts = transcripts,
                episodes = DbEpisodeSource(db),
                downloads = DbDownloadSource(db),
                appScope = coroutineScope,
                scheduler = scheduler,
                ioContext = testDispatcher,
                audioFallbackEnabled = audioFallbackEnabled,
            )
        return Fixture(repo, db, aiConfig, scheduler)
    }

    private fun insertEpisode(
        db: KofipodDatabase,
        episodeId: String,
        transcriptUrl: String,
        durationSec: Long = 600L,
        enclosureMimeType: String = "audio/mpeg",
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
            durationSec = durationSec,
            enclosureUrl = "https://example.com/audio.mp3",
            enclosureMimeType = enclosureMimeType,
            fileSizeBytes = 0L,
            seasonNumber = null,
            episodeNumber = null,
            imageUrl = "",
            chaptersUrl = null,
            transcriptUrl = transcriptUrl.takeIf { it.isNotBlank() },
        )
    }

    private fun insertCompletedDownload(
        db: KofipodDatabase,
        episodeId: String,
        localPath: String,
        downloadedBytes: Long,
    ) {
        db.downloadQueries.upsert(
            episodeId = episodeId,
            state = "Completed",
            localPath = localPath,
            downloadedBytes = downloadedBytes,
            totalBytes = downloadedBytes,
            source = "manual",
            startedAt = 0L,
            completedAt = 0L,
            errorMessage = null,
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
    private val handler: suspend () -> Result<AiSummaryJson>,
) : TextSummariser {
    constructor(returns: Result<AiSummaryJson>) : this({ returns })

    var callCount: Int = 0
        private set

    override suspend fun generateFromText(
        apiKey: String,
        model: GeminiModel,
        prompt: String,
        content: String,
    ): Result<AiSummaryJson> {
        callCount += 1
        return handler()
    }

    companion object {
        fun summary(text: String): Result<AiSummaryJson> = Result.success(AiSummaryJson(summary = text))
    }
}

private class StubAudioSummariser(
    private val handler: suspend (StubAudioCall) -> Result<AiSummaryJson>,
) : AudioSummariser {
    constructor(returns: Result<AiSummaryJson>) : this({ returns })

    val calls: MutableList<StubAudioCall> = mutableListOf()

    override suspend fun summariseAudio(
        apiKey: String,
        model: GeminiModel,
        prompt: String,
        localPath: String,
        mimeType: String,
        sizeBytes: Long,
        displayName: String,
        onStage: (GenerationStage) -> Unit,
    ): Result<AiSummaryJson> {
        val call = StubAudioCall(apiKey, model, prompt, localPath, mimeType, sizeBytes, displayName, onStage)
        calls += call
        return handler(call)
    }

    companion object {
        fun summary(text: String): Result<AiSummaryJson> = Result.success(AiSummaryJson(summary = text))
    }
}

// `data class` would synthesise componentN/equals over `onStage`, which is
// pointless for a function reference and noisy in failure messages.
private class StubAudioCall(
    val apiKey: String,
    val model: GeminiModel,
    val prompt: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val displayName: String,
    val onStage: (GenerationStage) -> Unit,
)

private class DbDownloadSource(private val db: KofipodDatabase) : DownloadSource {
    override fun forEpisodeFlow(episodeId: String): Flow<Download?> =
        db.downloadQueries.selectByEpisode(episodeId).asFlow().mapToOneOrNull(Dispatchers.Unconfined)
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
