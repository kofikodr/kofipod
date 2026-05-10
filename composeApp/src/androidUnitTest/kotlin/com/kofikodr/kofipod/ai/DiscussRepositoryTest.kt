// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

import com.kofikodr.kofipod.data.repo.EpisodeSource
import com.kofikodr.kofipod.data.repo.RefreshResult
import com.kofikodr.kofipod.data.repo.SettingsRepository
import com.kofikodr.kofipod.db.Download
import com.kofikodr.kofipod.db.Episode
import com.kofikodr.kofipod.db.KofipodDatabase
import com.kofikodr.kofipod.testing.inMemoryDatabase
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
 * Pins [DiscussRepository]'s observable contract and the chat pipeline.
 *
 * The repository is the single chokepoint between the Discuss/Q&A UI and
 * Gemini — misclassifying a state (Hidden when a key is configured, NoSource
 * when a transcript is present, Ready persistence skipped on disconnect race)
 * lights up the wrong branch in the panel composables.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscussRepositoryTest {
    @Test
    fun observeFor_returnsHidden_whenNoKeyConfigured() =
        runTest {
            val (repo, db) = build(initialKey = null)
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")

            val state = repo.observeFor("ep1").first()

            assertEquals(
                DiscussUiState.Hidden,
                state,
                "Without a Gemini key the Discuss tab is hidden — the panel must not render anything",
            )
        }

    @Test
    fun observeFor_returnsNoSource_whenKeyConfigured_butTranscriptMissing() =
        runTest {
            val (repo, db) = build(initialKey = "k")
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "")

            val state = repo.observeFor("ep1").first()

            assertEquals(
                DiscussUiState.NoSource,
                state,
                "Phase 1 only handles transcript episodes — audio-only must surface the no-source card",
            )
        }

    @Test
    fun observeFor_returnsReadyEmpty_whenKeyConfigured_andTranscriptPresent() =
        runTest {
            val (repo, db) = build(initialKey = "k")
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")

            val state = repo.observeFor("ep1").first()

            val ready = assertIs<DiscussUiState.Ready>(state)
            assertEquals(0, ready.messages.size, "Fresh episode must start with an empty chat")
            assertEquals(4, ready.suggestions.size, "Idle state must always present four suggestions")
            assertEquals(DiscussPrompts.QUICK_PROMPTS, ready.quickPrompts)
        }

    @Test
    fun send_persistsUserAndModelTurns_andClearsTransientErrorOnSuccess() =
        runTest {
            val chatStub = StubChatSummariser.success(answer = "A grounded reply.", citations = emptyList())
            val (repo, db) = build(initialKey = "k", chat = chatStub)
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")

            repo.send("ep1", "What did Toby push back on?")
            advanceUntilIdle()

            val state = repo.observeFor("ep1").first()
            val ready = assertIs<DiscussUiState.Ready>(state)
            assertEquals(2, ready.messages.size, "User turn + model turn must both persist")
            assertEquals(DiscussRole.User, ready.messages[0].role)
            assertEquals("What did Toby push back on?", ready.messages[0].content)
            assertEquals(DiscussRole.Model, ready.messages[1].role)
            assertEquals("A grounded reply.", ready.messages[1].content)
            assertEquals(false, ready.inFlight)
            assertEquals(null, ready.error)
        }

    @Test
    fun send_blankQuestion_isDroppedWithoutHittingTheModel() =
        runTest {
            val chatStub = StubChatSummariser.success("never called")
            val (repo, db) = build(initialKey = "k", chat = chatStub)
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")

            repo.send("ep1", "   \n  \t ")
            advanceUntilIdle()

            assertEquals(0, chatStub.callCount, "Whitespace-only must NEVER reach the chat summariser — wasted token budget")
            val state = repo.observeFor("ep1").first()
            val ready = assertIs<DiscussUiState.Ready>(state)
            assertEquals(0, ready.messages.size, "No turn must be persisted for a blank submit")
        }

    @Test
    fun send_surfacesNetworkError_andLeavesUserTurnPersisted() =
        runTest {
            // The user message lands BEFORE the network round-trip starts so
            // the UI can scroll the input out of the way. A subsequent network
            // failure must surface the error without rolling back the user
            // row — the user can read what they asked, see the error, and
            // tap retry without re-typing.
            val chatStub =
                StubChatSummariser { _, _, _, _, _, _ ->
                    Result.failure(AiErrorException(AiError.Network))
                }
            val (repo, db) = build(initialKey = "k", chat = chatStub)
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")

            repo.send("ep1", "Why?")
            advanceUntilIdle()

            val state = repo.observeFor("ep1").first()
            val ready = assertIs<DiscussUiState.Ready>(state)
            assertEquals(1, ready.messages.size, "User turn must persist even on network failure")
            assertEquals(DiscussRole.User, ready.messages[0].role)
            assertEquals(AiError.Network, ready.error)
        }

    @Test
    fun retry_afterFailure_resendsLastQuestion_withoutInsertingDuplicateUserRow() =
        runTest {
            // Recovery loop after a transient 5xx / blank response / parse
            // failure: the error bubble's Retry button must re-run the chat
            // call against the SAME user message — not insert a second copy
            // of "Why?" into the thread, and not silently drop the user's
            // intent. On success, the error clears and the model row appears
            // adjacent to the original user row.
            var attempt = 0
            val chatStub =
                StubChatSummariser { _, _, _, _, _, question ->
                    attempt += 1
                    when (attempt) {
                        1 -> Result.failure(AiErrorException(AiError.Unknown(503)))
                        else -> Result.success(DiscussAnswerJson(answer = "Recovered: $question", citations = emptyList()))
                    }
                }
            val (repo, db) = build(initialKey = "k", chat = chatStub)
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")

            repo.send("ep1", "Why?")
            advanceUntilIdle()
            // Sanity: first attempt failed, error surfaced, user row stayed.
            val afterFailure = repo.observeFor("ep1").first() as DiscussUiState.Ready
            assertEquals(AiError.Unknown(503), afterFailure.error)
            assertEquals(1, afterFailure.messages.size)

            repo.retry("ep1")
            advanceUntilIdle()

            assertEquals(2, chatStub.callCount, "Retry must re-fire the chat call exactly once")
            val afterRetry = repo.observeFor("ep1").first() as DiscussUiState.Ready
            assertEquals(null, afterRetry.error, "Successful retry must clear the transient error")
            assertEquals(
                2,
                afterRetry.messages.size,
                "Retry must NOT insert a duplicate user row — total = original user + new model",
            )
            assertEquals(DiscussRole.User, afterRetry.messages[0].role)
            assertEquals("Why?", afterRetry.messages[0].content, "Original user content must be preserved verbatim")
            assertEquals(DiscussRole.Model, afterRetry.messages[1].role)
            assertEquals("Recovered: Why?", afterRetry.messages[1].content)
        }

    @Test
    fun retry_withoutPriorSession_isNoOp() =
        runTest {
            // Defensive no-op: a stray Retry tap from a code path that shouldn't
            // be reachable (no chat exists yet for this episode) must not crash
            // and must not synthesise an empty user row. Surfaces here so a
            // future refactor that loosens the UI guard can't introduce a NPE
            // in production.
            val chatStub = StubChatSummariser.success("never called")
            val (repo, db) = build(initialKey = "k", chat = chatStub)
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")

            repo.retry("ep1")
            advanceUntilIdle()

            assertEquals(0, chatStub.callCount, "Retry without a prior message must NEVER reach the chat summariser")
            assertEquals(
                null,
                db.discussSessionQueries.selectByEpisode("ep1").executeAsOneOrNull(),
                "Retry must not synthesise an empty session",
            )
            val state = repo.observeFor("ep1").first() as DiscussUiState.Ready
            assertEquals(null, state.error, "No-op retry must not surface a transient error")
            assertEquals(false, state.inFlight, "No-op retry must not leave inFlight stuck")
        }

    @Test
    fun retry_thatAlsoFails_resurfacesError_withoutInflatingUserRowCount() =
        runTest {
            // Both the initial send and the retry call fail. The error must
            // re-surface rather than silently clear, and the user-row count
            // must remain at exactly one — the `insertUser = false` contract
            // on retry has to hold on the failure path the same way it holds
            // on the success path.
            val chatStub =
                StubChatSummariser { _, _, _, _, _, _ ->
                    Result.failure(AiErrorException(AiError.Unknown(503)))
                }
            val (repo, db) = build(initialKey = "k", chat = chatStub)
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")

            repo.send("ep1", "Will this work?")
            advanceUntilIdle()
            val afterFirstFail = repo.observeFor("ep1").first() as DiscussUiState.Ready
            assertEquals(AiError.Unknown(503), afterFirstFail.error)
            assertEquals(1, afterFirstFail.messages.size)

            repo.retry("ep1")
            advanceUntilIdle()

            assertEquals(2, chatStub.callCount, "Retry must re-fire the chat call exactly once")
            val afterRetryFail = repo.observeFor("ep1").first() as DiscussUiState.Ready
            assertEquals(
                AiError.Unknown(503),
                afterRetryFail.error,
                "A failed retry must re-surface the error — never silently clear it",
            )
            assertEquals(
                1,
                afterRetryFail.messages.size,
                "Failed retry must NOT insert a second user row — still exactly one user message",
            )
            assertEquals(DiscussRole.User, afterRetryFail.messages[0].role)
            assertEquals("Will this work?", afterRetryFail.messages[0].content)
        }

    @Test
    fun retry_whileSendIsInFlight_isDropped_andCancelStillTargetsTheRealSend() =
        runTest {
            // Two contracts in one test, both load-bearing for the Retry button:
            //
            //  1. A Retry tap landing while a previous send is parked at the
            //     network must NOT spawn a second Gemini call. `runSend`'s own
            //     inFlight guard already catches that, but contract #2 is the
            //     reason we ALSO short-circuit at retry()'s entry.
            //
            //  2. The retry tap must NOT corrupt `activeJobs[episodeId]`.
            //     Without the early-return at retry()'s entry, a bouncer Job
            //     would be launched, hit the inFlight guard, complete fast,
            //     and its `invokeOnCompletion { activeJobs - episodeId }`
            //     handler would clobber the real send's entry — leaving a
            //     subsequent `cancel(episodeId)` (or `clearForEpisode`) with
            //     a completed no-op job to cancel and the actual chat call
            //     running unsupervised. We pin contract #2 by issuing
            //     `cancel(episodeId)` after the racy retry tap and asserting
            //     that the real send was actually cancelled — which is only
            //     possible if `activeJobs[episodeId]` still points at it.
            val gate = CompletableDeferred<Result<DiscussAnswerJson>>()
            val chatStub = StubChatSummariser { _, _, _, _, _, _ -> gate.await() }
            val (repo, db) = build(initialKey = "k", chat = chatStub)
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")

            repo.send("ep1", "What happened?")
            testScheduler.runCurrent()
            // Send is parked at the gate; retry tap should be dropped at
            // retry()'s entry without launching a bouncer job.
            repo.retry("ep1")
            testScheduler.runCurrent()

            // Cancel must reach the real in-flight send. The cancellation
            // propagates through the parked `gate.await()`, finally clears
            // inFlight, and no model row is persisted.
            repo.cancel("ep1")
            advanceUntilIdle()

            val state = repo.observeFor("ep1").first() as DiscussUiState.Ready
            assertEquals(
                false,
                state.inFlight,
                "Cancel must clear inFlight — proves activeJobs[episodeId] still targeted the real send, " +
                    "i.e. retry() didn't corrupt the entry with a bouncer job",
            )
            assertEquals(
                1,
                chatStub.callCount,
                "Retry-during-in-flight-send must be dropped — only one chat call allowed",
            )
            assertEquals(
                1,
                state.messages.size,
                "Cancelled send must not insert a model row — only the original user turn persists",
            )
            assertEquals(DiscussRole.User, state.messages[0].role)

            // Drain the deferred so `runTest` doesn't see a dangling promise.
            // The cancelled coroutine has already unwound; this completion
            // has no consumer.
            gate.complete(Result.success(DiscussAnswerJson(answer = "ignored", citations = emptyList())))
        }

    @Test
    fun send_secondConcurrent_isDropped_byInFlightGuard() =
        runTest {
            // Single-flight per episodeId — a rapid double-tap must NOT spawn
            // two concurrent Gemini calls. Without the inFlight guard, both
            // would write a model row and the user would see duplicated
            // answers.
            val gate = CompletableDeferred<Result<DiscussAnswerJson>>()
            val chatStub = StubChatSummariser { _, _, _, _, _, _ -> gate.await() }
            val (repo, db) = build(initialKey = "k", chat = chatStub)
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")

            repo.send("ep1", "first")
            testScheduler.runCurrent()
            // Second send while the first is parked at the gate.
            repo.send("ep1", "second")
            testScheduler.runCurrent()

            // First call has the chat seam parked. The second send went
            // through repo.send and would have inserted a user row before
            // bouncing off the inFlight guard inside runSend's sendLock.
            // Hmm — actually, runSend's lock check is the gate; the user-
            // row insert happens inside try{} after the lock releases.
            // So the second send should insert NOTHING.
            val rowsAfterParked = db.discussMessageQueries.selectBySessionFlow("noop").executeAsList()
            // Use a real sessionId lookup once the first send creates one.
            val session = db.discussSessionQueries.selectByEpisode("ep1").executeAsOneOrNull()
            assertTrue(session != null, "First send must create a session even while parked at the chat seam")
            val rows = db.discussMessageQueries.selectBySessionFlow(session!!.id).executeAsList()
            assertEquals(
                1,
                rows.size,
                "Second send must be dropped by the inFlight guard — only the first user row should exist while parked",
            )

            gate.complete(Result.success(DiscussAnswerJson(answer = "done", citations = emptyList())))
            advanceUntilIdle()
            assertEquals(1, chatStub.callCount, "Single-flight contract: only one chat call per concurrent burst")
            assertTrue(rowsAfterParked.isEmpty(), "Sanity: the noop session id must never have rows")
        }

    @Test
    fun send_capsHistoryToLast20_butKeepsAllInDb() =
        runTest {
            // History cap is the input-token budget guard. 25 prior turns +
            // a new question must send the LAST 20 historical turns to the
            // model — but every row stays in the DB so the user's chat
            // doesn't visually truncate.
            val chatStub = StubChatSummariser.success("ok")
            val (repo, db) = build(initialKey = "k", chat = chatStub)
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")

            // Manually seed 25 prior turns alternating user/model so the
            // priorTurns trim has something to actually trim.
            val sessionId = "sess"
            db.discussSessionQueries.insert(id = sessionId, episodeId = "ep1", createdAtMs = 0L, updatedAtMs = 0L)
            for (i in 1..25) {
                db.discussMessageQueries.insert(
                    id = "m$i",
                    sessionId = sessionId,
                    role = if (i % 2 == 1) DiscussRole.User.wire else DiscussRole.Model.wire,
                    content = "msg-$i",
                    citationsJson = "[]",
                    createdAtMs = i.toLong(),
                )
            }

            repo.send("ep1", "fresh question")
            advanceUntilIdle()

            assertEquals(
                20,
                chatStub.lastHistorySize,
                "History sent to the model must be capped at 20 turns to bound the input-token cost",
            )
            // After the send, the DB has 25 prior + 1 user + 1 model = 27.
            val rowCount = db.discussMessageQueries.selectBySessionFlow(sessionId).executeAsList().size
            assertEquals(27, rowCount, "All historical rows must survive — cap is on what's sent, not what's stored")
        }

    @Test
    fun clearForEpisode_wipesSessionAndMessages_andCancelsInFlight() =
        runTest {
            // Per-episode clear (the trashcan affordance). Mirrors clearAll's
            // cancel-then-delete order: a still-running send can't write back
            // into the just-cleared session, leaking model output across the
            // wipe boundary.
            val gate = CompletableDeferred<Result<DiscussAnswerJson>>()
            val chatStub = StubChatSummariser { _, _, _, _, _, _ -> gate.await() }
            val (repo, db) = build(initialKey = "k", chat = chatStub)
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")

            repo.send("ep1", "in-flight question")
            testScheduler.runCurrent()
            val sessionBefore = db.discussSessionQueries.selectByEpisode("ep1").executeAsOneOrNull()
            assertTrue(sessionBefore != null, "Pipeline must reach session creation before we test the wipe")

            repo.clearForEpisode("ep1")
            // Release the gate AFTER the wipe. The cancelled job must be a no-op.
            gate.complete(Result.success(DiscussAnswerJson(answer = "LATE", citations = emptyList())))
            advanceUntilIdle()

            val sessionAfter = db.discussSessionQueries.selectByEpisode("ep1").executeAsOneOrNull()
            assertEquals(null, sessionAfter, "Session row must be gone after clearForEpisode")
        }

    @Test
    fun clearAll_wipesEveryEpisode_andCancelsInFlightOnAll() =
        runTest {
            // Disconnect contract: every chat across every episode goes in one
            // call. The cancel-then-delete order is what AiSetupViewModel.
            // confirmDisconnect relies on so a long Gemini call from before
            // disconnect can't write a row against the just-cleared key.
            val gate = CompletableDeferred<Result<DiscussAnswerJson>>()
            val chatStub = StubChatSummariser { _, _, _, _, _, _ -> gate.await() }
            val (repo, db) = build(initialKey = "k", chat = chatStub)
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")
            insertEpisode(db, episodeId = "ep2", transcriptUrl = "https://example.com/t2.vtt", podcastId = "pod1")

            repo.send("ep1", "q1")
            repo.send("ep2", "q2")
            testScheduler.runCurrent()

            repo.clearAll()
            gate.complete(Result.success(DiscussAnswerJson(answer = "LATE", citations = emptyList())))
            advanceUntilIdle()

            val sessions =
                listOf("ep1", "ep2").map {
                    db.discussSessionQueries.selectByEpisode(it).executeAsOneOrNull()
                }
            assertTrue(sessions.all { it == null }, "Every episode's session must be gone after clearAll")
        }

    // -------------------------------------------------------------------------
    // Audio fallback — Phase 2
    // -------------------------------------------------------------------------

    @Test
    fun observeFor_returnsReady_whenNoTranscript_butEpisodeIsDownloaded() =
        runTest {
            // Phase 2: a downloaded episode without a transcript must surface
            // Ready, not NoSource. Without this, the Discuss tab would never
            // open for audio-only episodes even when the audio is available.
            val download =
                downloadOf("ep1", state = "Completed", localPath = "/tmp/ep1.mp3", bytes = 1_000_000L)
            val (repo, db) = build(initialKey = "k", downloadSource = StaticDownloadSource(mapOf("ep1" to download)))
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "")

            val state = repo.observeFor("ep1").first()

            assertIs<DiscussUiState.Ready>(
                state,
                "Audio fallback (Phase 2): downloaded episode with no transcript must surface Ready",
            )
        }

    @Test
    fun observeFor_stillReturnsNoSource_whenNoTranscript_andDownloadIsPartial() =
        runTest {
            val download =
                downloadOf("ep1", state = "Downloading", localPath = "/tmp/ep1.partial", bytes = 5_000L)
            val (repo, db) = build(initialKey = "k", downloadSource = StaticDownloadSource(mapOf("ep1" to download)))
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "")

            val state = repo.observeFor("ep1").first()

            assertEquals(
                DiscussUiState.NoSource,
                state,
                "Partial download must NOT enable Discuss — Gemini would receive truncated audio",
            )
        }

    @Test
    fun send_audioPath_uploadsThenChatsWithAudioContext() =
        runTest {
            // Cache miss: send must call the uploader with the right size,
            // then call chat() with ChatContext.Audio carrying the URI the
            // uploader returned. Pinning the wire shape so a regression that
            // accidentally collapses Audio→Transcript surfaces here rather
            // than in production with a confusing chat answer.
            val chatStub = StubChatSummariser.success("audio answer")
            val download =
                downloadOf("ep1", state = "Completed", localPath = "/tmp/ep1.mp3", bytes = 5_000_000L)
            val recordingUploader = RecordingTestUploader()
            val (repo, db) =
                build(
                    initialKey = "k",
                    chat = chatStub,
                    source = StubAudioDiscussSource(sizeBytes = 5_000_000L),
                    downloadSource = StaticDownloadSource(mapOf("ep1" to download)),
                    uploader = recordingUploader,
                )
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "")

            repo.send("ep1", "What is this episode about?")
            advanceUntilIdle()

            assertEquals(1, recordingUploader.calls.size, "Cache miss must trigger one upload")
            assertEquals(5_000_000L, recordingUploader.calls[0].sizeBytes)
            val ctx = chatStub.lastContext
            assertIs<ChatContext.Audio>(ctx, "Audio path must hand chat() a ChatContext.Audio, got $ctx")
            assertTrue(ctx.fileUri.isNotBlank(), "ChatContext.Audio.fileUri must carry the uploaded URI")
        }

    @Test
    fun send_audioPath_secondTurn_skipsUpload_byReusingCache() =
        runTest {
            // Once the first turn caches a URI, a second turn against the
            // same episode must reach the chat call with the same URI and
            // NOT call the uploader again — the central design promise of
            // [AudioUploadCoordinator].
            val chatStub = StubChatSummariser.success("answer")
            val download =
                downloadOf("ep1", state = "Completed", localPath = "/tmp/ep1.mp3", bytes = 5_000_000L)
            val recordingUploader = RecordingTestUploader()
            val (repo, db) =
                build(
                    initialKey = "k",
                    chat = chatStub,
                    source = StubAudioDiscussSource(sizeBytes = 5_000_000L),
                    downloadSource = StaticDownloadSource(mapOf("ep1" to download)),
                    uploader = recordingUploader,
                )
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "")

            repo.send("ep1", "first")
            advanceUntilIdle()
            repo.send("ep1", "second")
            advanceUntilIdle()

            assertEquals(
                1,
                recordingUploader.calls.size,
                "Second turn must reuse the cached URI — uploader must NOT fire twice",
            )
            assertEquals(2, chatStub.callCount, "Both turns must reach the chat seam regardless of cache state")
        }

    @Test
    fun observeFor_audioSession_setsTurnWarning_atFifthUserTurn() =
        runTest {
            // Audio chats burn quota faster (Gemini re-reads the audio every
            // turn), so we surface a warning at the threshold. Under the
            // threshold or on transcript paths, the warning stays off.
            val chatStub = StubChatSummariser.success("ok")
            val download =
                downloadOf("ep1", state = "Completed", localPath = "/tmp/ep1.mp3", bytes = 1_000_000L)
            val (repo, db) =
                build(
                    initialKey = "k",
                    chat = chatStub,
                    source = StubAudioDiscussSource(sizeBytes = 1_000_000L),
                    downloadSource = StaticDownloadSource(mapOf("ep1" to download)),
                    uploader = RecordingTestUploader(),
                )
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "")

            // 4 user turns → still under the threshold.
            repeat(4) { repo.send("ep1", "q$it") }
            advanceUntilIdle()
            val under = repo.observeFor("ep1").first() as DiscussUiState.Ready
            assertEquals(false, under.audioTurnWarningVisible, "Under 5 user turns → warning stays off")

            // 5th user turn → warning fires.
            repo.send("ep1", "fifth")
            advanceUntilIdle()
            val at = repo.observeFor("ep1").first() as DiscussUiState.Ready
            assertEquals(true, at.audioTurnWarningVisible, "At 5 user turns the audio-quota banner must surface")
        }

    @Test
    fun clearAll_cancelsInFlightAudioSend_andDoesNotPersistModelTurn() =
        runTest {
            // Mirrors AiSummaryRepository's cancel-during-upload race test for
            // the Discuss audio path. A late-completing upload + chat call
            // arriving after Disconnect must NOT insert a model row against
            // the just-cleared session — that would leak content generated
            // under the now-revoked key.
            val gate = CompletableDeferred<Result<UploadedFile>>()
            val gatedUploader =
                AudioUploader { _, _, mimeType, _, displayName ->
                    // Park the upload until the test releases the gate. By the
                    // time we release, clearAll will have already wiped the
                    // session, exercising the race.
                    gate.await().also { /* ensure suspension is real */ }
                    Result.success(
                        UploadedFile(
                            name = "files/$displayName",
                            uri = "https://gemini/$displayName",
                            mimeType = mimeType,
                            state = "ACTIVE",
                        ),
                    )
                }
            val download = downloadOf("ep1", state = "Completed", localPath = "/tmp/ep1.mp3", bytes = 5_000_000L)
            val (repo, db) =
                build(
                    initialKey = "k",
                    chat = StubChatSummariser.success("LATE"),
                    source = StubAudioDiscussSource(sizeBytes = 5_000_000L),
                    downloadSource = StaticDownloadSource(mapOf("ep1" to download)),
                    uploader = gatedUploader,
                )
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "")

            repo.send("ep1", "q1")
            testScheduler.runCurrent()

            repo.clearAll()
            // Release the upload — chat call would otherwise complete and
            // try to write a model row. The repository's currentKey re-check
            // (and the cancellation drain inside clearAll) must intercept it.
            gate.complete(
                Result.success(
                    UploadedFile(
                        name = "files/late",
                        uri = "https://gemini/late",
                        mimeType = "audio/mpeg",
                        state = "ACTIVE",
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals(
                null,
                db.discussSessionQueries.selectByEpisode("ep1").executeAsOneOrNull(),
                "clearAll must wipe the session even when an audio upload was in flight",
            )
        }

    @Test
    fun cleanStaleDiscussUploads_deletesOnlyDiscussUploadKind() =
        runTest {
            // The worker calls cleanStaleDiscussUploads on every fire — it
            // must delete every discuss_upload row but leave summary markers
            // alone (those belong to AiSummaryRepository.resumePending).
            val (repo, db) = build(initialKey = "k")
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")
            insertEpisode(db, episodeId = "ep2", transcriptUrl = "")
            db.pendingAiOperationQueries.upsert("ep1", PendingOperationKind.Summary.wire, 0L)
            db.pendingAiOperationQueries.upsert("ep2", PendingOperationKind.DiscussUpload.wire, 0L)

            repo.cleanStaleDiscussUploads()

            val rows = db.pendingAiOperationQueries.selectAll().executeAsList()
            assertEquals(1, rows.size, "Only the DiscussUpload marker must be swept")
            assertEquals(PendingOperationKind.Summary.wire, rows[0].kind)
            assertEquals("ep1", rows[0].episodeId)
        }

    @Test
    fun send_audioPath_cacheHit_doesNotWriteDiscussUploadMarker() =
        runTest {
            // Cache hit means no upload happened, so there's nothing to
            // breadcrumb. Writing the marker anyway would teach the worker
            // sweep to see ghost rows for chats that never uploaded —
            // confusing the table's semantic ("a marker means an upload was
            // attempted").
            val download = downloadOf("ep1", state = "Completed", localPath = "/tmp/ep1.mp3", bytes = 1_000_000L)
            val recordingUploader = RecordingTestUploader()
            val (repo, db) =
                build(
                    initialKey = "k",
                    chat = StubChatSummariser.success("ok"),
                    source = StubAudioDiscussSource(sizeBytes = 1_000_000L),
                    downloadSource = StaticDownloadSource(mapOf("ep1" to download)),
                    uploader = recordingUploader,
                )
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "")

            // First turn populates the cache.
            repo.send("ep1", "first")
            advanceUntilIdle()
            // Second turn — cache hit; coordinator must skip the uploader,
            // and acquireAudioContext must NOT write a discuss_upload marker.
            repo.send("ep1", "second")
            advanceUntilIdle()

            assertEquals(1, recordingUploader.calls.size, "Cache hit must skip the uploader on the second turn")
            // No discuss_upload marker should remain. The first turn's marker
            // was deleted by runSend's `finally`; the second turn never wrote
            // one because it was a cache hit.
            val markers =
                db.pendingAiOperationQueries
                    .selectByKind(PendingOperationKind.DiscussUpload.wire)
                    .executeAsList()
            assertTrue(
                markers.isEmpty(),
                "After a cache-hit turn, no discuss_upload marker must remain in the table",
            )
        }

    @Test
    fun observeFor_transcriptSession_neverShowsTurnWarning() =
        runTest {
            // Transcript chats replay text — cheap. The warning is for audio
            // only; lighting it up on transcript chats would teach users to
            // ignore it everywhere.
            val chatStub = StubChatSummariser.success("ok")
            val (repo, db) = build(initialKey = "k", chat = chatStub)
            insertEpisode(db, episodeId = "ep1", transcriptUrl = "https://example.com/t.vtt")

            repeat(6) { repo.send("ep1", "q$it") }
            advanceUntilIdle()

            val state = repo.observeFor("ep1").first() as DiscussUiState.Ready
            assertEquals(false, state.audioTurnWarningVisible, "Transcript sessions must NEVER trip the warning")
        }

    // -------------------------------------------------------------------------
    // Fixture
    // -------------------------------------------------------------------------

    private data class Fixture(
        val repo: DiscussRepository,
        val db: KofipodDatabase,
    )

    private fun TestScope.build(
        initialKey: String?,
        chat: ChatSummariser = StubChatSummariser.success("default"),
        cachedSummary: AiSummary? = null,
        source: DiscussSource = StubDiscussSource("transcript-body"),
        downloadSource: DownloadSource = NoDownloadSource,
        uploader: AudioUploader =
            AudioUploader { _, _, _, _, _ ->
                // Tests using the default source (transcript) never reach the
                // audio uploader; if a regression routes them through, this
                // surfaces the misroute as an explicit failure instead of
                // silently calling out to the network.
                Result.failure(AiErrorException(AiError.Network))
            },
    ): Fixture {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val db = inMemoryDatabase()
        val vault = DiscussFakeVault(initialKey)
        val settings = SettingsRepository(db, flowContext = testDispatcher)
        val coroutineScope = CoroutineScope(testDispatcher)
        val aiConfig = AiConfigRepository(keyVault = vault, settings = settings, appScope = coroutineScope)
        testScheduler.runCurrent()
        var counter = 0
        val coordinator =
            AudioUploadCoordinator(
                uploader = uploader,
                db = db,
                openFile = { io.ktor.utils.io.ByteReadChannel.Empty },
                ioContext = testDispatcher,
            )
        val repo =
            DiscussRepository(
                db = db,
                aiConfig = aiConfig,
                chat = chat,
                source = source,
                coordinator = coordinator,
                episodes = DiscussDbEpisodeSource(db),
                downloads = downloadSource,
                summaries = SummarySource { _ -> flowOf(cachedSummary) },
                appScope = coroutineScope,
                ioContext = testDispatcher,
                idGen = { "id-${++counter}" },
            )
        return Fixture(repo, db)
    }

    private fun insertEpisode(
        db: KofipodDatabase,
        episodeId: String,
        transcriptUrl: String,
        podcastId: String = "pod1",
    ) {
        // Idempotent so multi-episode tests can call this twice without
        // tripping the Podcast PK constraint on the second call.
        db.podcastQueries.insert(
            id = podcastId,
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
            podcastId = podcastId,
            guid = "g-$episodeId",
            title = "Episode $episodeId",
            description = "",
            publishedAt = 0L,
            durationSec = 600L,
            enclosureUrl = "https://example.com/$episodeId.mp3",
            enclosureMimeType = "audio/mpeg",
            fileSizeBytes = 0L,
            imageUrl = "",
            episodeNumber = null,
            seasonNumber = null,
            chaptersUrl = null,
            transcriptUrl = transcriptUrl,
        )
    }
}

private class StubDiscussSource(private val transcript: String) : DiscussSource {
    override suspend fun loadContext(
        episode: Episode,
        download: Download?,
    ): DiscussLoad =
        DiscussLoad.Success(
            DiscussContext.Available(transcript = transcript, fingerprint = episode.transcriptUrl.orEmpty()),
        )
}

/**
 * Always-AudioReady source for exercising the audio fallback. The localPath
 * etc. are surfaced verbatim to the coordinator under test, so use realistic
 * values when an assertion later reads them back.
 */
private class StubAudioDiscussSource(
    private val localPath: String = "/tmp/audio.mp3",
    private val mimeType: String = "audio/mpeg",
    private val sizeBytes: Long = 5_000_000L,
) : DiscussSource {
    override suspend fun loadContext(
        episode: Episode,
        download: Download?,
    ): DiscussLoad =
        DiscussLoad.Success(
            DiscussContext.AudioReady(
                localPath = localPath,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                fingerprint = sizeBytes.toString(),
            ),
        )
}

private object NoDownloadSource : DownloadSource {
    override fun forEpisodeFlow(episodeId: String): Flow<Download?> = flowOf(null)
}

/**
 * [DownloadSource] backed by a fixed map. Used by audio-fallback tests to
 * surface a "Completed" download for the episode under test without
 * standing up the real [com.kofikodr.kofipod.data.repo.DownloadRepository].
 */
private class StaticDownloadSource(private val byEpisode: Map<String, Download?>) : DownloadSource {
    override fun forEpisodeFlow(episodeId: String): Flow<Download?> = flowOf(byEpisode[episodeId])
}

private fun downloadOf(
    episodeId: String,
    state: String,
    localPath: String,
    bytes: Long,
): Download =
    Download(
        episodeId = episodeId,
        state = state,
        localPath = localPath,
        downloadedBytes = bytes,
        totalBytes = bytes,
        source = "manual",
        startedAt = 0L,
        completedAt = 0L.takeIf { state == "Completed" },
        errorMessage = null,
    )

/**
 * Recording [AudioUploader] for audio-path tests. Returns a synthetic
 * [UploadedFile] whose URI encodes the displayName so assertions can check
 * the URI travelled through to the chat call. Failure is also injectable
 * for error-path tests.
 */
private class RecordingTestUploader(
    private val handler: suspend (RecordingUpload) -> Result<UploadedFile> = { call ->
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
    val calls: MutableList<RecordingUpload> = mutableListOf()

    override suspend fun upload(
        apiKey: String,
        channel: io.ktor.utils.io.ByteReadChannel,
        mimeType: String,
        sizeBytes: Long,
        displayName: String,
    ): Result<UploadedFile> {
        val call = RecordingUpload(apiKey, mimeType, sizeBytes, displayName)
        calls += call
        return handler(call)
    }
}

private data class RecordingUpload(
    val apiKey: String,
    val mimeType: String,
    val sizeBytes: Long,
    val displayName: String,
)

private class DiscussDbEpisodeSource(private val db: KofipodDatabase) : EpisodeSource {
    override fun episodesFlow(podcastId: String): Flow<List<Episode>> = flowOf(emptyList())

    /**
     * One-shot snapshot rather than a real reactive flow, by design for these
     * tests. The reactive variant (`asFlow().mapToOneOrNull(Dispatchers.Default)`)
     * routes notifications through the Default dispatcher which the test
     * scheduler doesn't drive, racing with the in-pipeline writes the tests
     * are asserting against. Every test in this file inserts the episode
     * BEFORE building the repo, so a snapshot is sufficient. If a future
     * test needs to insert an episode mid-flight, swap to the reactive form
     * and inject the test dispatcher into mapToOneOrNull.
     */
    override fun episodeFlow(episodeId: String): Flow<Episode?> {
        val row = db.episodeQueries.selectById(episodeId).executeAsOneOrNull()
        return flowOf(row)
    }

    override fun newEpisodeCountsFlow(): Flow<Map<String, Int>> = flowOf(emptyMap())

    override suspend fun refresh(
        podcastId: String,
        feedId: Long,
        nowMillis: Long,
    ): RefreshResult = RefreshResult(emptyList(), 0)
}

private class DiscussFakeVault(initial: String?) : KeyVault {
    private var current: String? = initial

    override suspend fun get(): String? = current

    override suspend fun set(value: String) {
        current = value
    }

    override suspend fun clear() {
        current = null
    }
}

private class StubChatSummariser(
    private val handler: suspend (
        apiKey: String,
        model: GeminiModel,
        systemPrompt: String,
        context: ChatContext,
        history: List<DiscussTurn>,
        question: String,
    ) -> Result<DiscussAnswerJson>,
) : ChatSummariser {
    var callCount: Int = 0
        private set

    /** Number of historical turns received in the most recent call. -1 until the first call. */
    var lastHistorySize: Int = -1
        private set

    /** [ChatContext] passed in the most recent call — null until the first call. */
    var lastContext: ChatContext? = null
        private set

    override suspend fun chat(
        apiKey: String,
        model: GeminiModel,
        systemPrompt: String,
        context: ChatContext,
        history: List<DiscussTurn>,
        question: String,
    ): Result<DiscussAnswerJson> {
        callCount += 1
        lastHistorySize = history.size
        lastContext = context
        return handler(apiKey, model, systemPrompt, context, history, question)
    }

    companion object {
        fun success(
            answer: String,
            citations: List<CitationJson> = emptyList(),
        ): StubChatSummariser =
            StubChatSummariser { _, _, _, _, _, _ ->
                Result.success(DiscussAnswerJson(answer = answer, citations = citations))
            }
    }
}
