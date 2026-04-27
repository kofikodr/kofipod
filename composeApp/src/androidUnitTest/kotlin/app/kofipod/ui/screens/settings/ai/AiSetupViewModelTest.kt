// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.settings.ai

import app.kofipod.ai.AiConfigRepository
import app.kofipod.ai.AiError
import app.kofipod.ai.AiErrorException
import app.kofipod.ai.AiSourceKind
import app.kofipod.ai.AiSummaryRepository
import app.kofipod.ai.GeminiModel
import app.kofipod.ai.KeyValidator
import app.kofipod.ai.KeyVault
import app.kofipod.ai.TextSummariser
import app.kofipod.ai.TranscriptFetcher
import app.kofipod.data.repo.EpisodeSource
import app.kofipod.data.repo.RefreshResult
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.db.Episode
import app.kofipod.db.KofipodDatabase
import app.kofipod.testing.inMemoryDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural tests for [AiSetupViewModel] and [errorCopy]. The VM is the seam
 * where validation results turn into user-visible messages — every branch in
 * [errorCopy] must map to deliberate copy, and the connect/disconnect flow must
 * leave the underlying [AiConfigRepository] in a coherent state.
 *
 * Validation is driven by [FakeKeyValidator] (a `KeyValidator` test double)
 * rather than a real `GeminiClient` + Ktor MockEngine — Ktor's response loop
 * uses internal dispatchers that don't compose with `runTest`'s virtual scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiSetupViewModelTest {
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Sets `Dispatchers.Main` to the test's scheduler so `viewModelScope.launch { ... }`
     * is drained by `advanceUntilIdle()`. Using a class-level dispatcher would
     * yield a different scheduler than `runTest`'s and the launched work would
     * never run during the test body.
     */
    private fun runVmTest(block: suspend TestScope.() -> Unit) =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            block()
        }

    // ---- errorCopy: pure mapping ---------------------------------------------------

    @Test
    fun errorCopy_keyInvalid_pointsUserAtAiStudio() {
        val msg = errorCopy(AiError.KeyInvalid)
        assertTrue("rejected" in msg, "Copy must signal rejection, not a transient error: $msg")
        assertTrue("AI Studio" in msg, "Copy should point users to where they can fix the key: $msg")
    }

    @Test
    fun errorCopy_rateLimited_suggestsRetryLater() {
        val msg = errorCopy(AiError.RateLimited)
        assertTrue("rate-limited" in msg, "Copy must explain why the request was rejected: $msg")
        assertTrue("Try again" in msg, "Copy must invite a retry: $msg")
    }

    @Test
    fun errorCopy_network_blamesConnection_notTheKey() {
        val msg = errorCopy(AiError.Network)
        assertTrue(
            "connection" in msg,
            "Copy must blame the connection so the user doesn't re-paste a working key: $msg",
        )
    }

    @Test
    fun errorCopy_unknown_includesStatusCode_whenAvailable() {
        assertEquals("Validation failed (status 503).", errorCopy(AiError.Unknown(503)))
    }

    @Test
    fun errorCopy_unknown_handlesMissingStatusCode() {
        assertEquals("Validation failed (status unknown).", errorCopy(AiError.Unknown(null)))
    }

    // ---- connect(): empty paste short-circuits without a network call -------------

    @Test
    fun connect_withEmptyPasteValue_setsErrorMessage_withoutHittingTheNetwork() =
        runVmTest {
            val validator = FakeKeyValidator(Result.success(Unit))
            val vm = newVm(validator = validator)

            vm.connect() // pasteValue is "" by default

            advanceUntilIdle()
            assertEquals("Paste your Gemini API key first.", vm.state.value.errorMessage)
            assertEquals(0, validator.callCount, "Empty paste must NEVER reach the validator — wasted request budget")
            assertEquals(false, vm.state.value.verifying, "verifying must not be set when we short-circuit")
        }

    @Test
    fun connect_withWhitespaceOnlyPasteValue_isTreatedAsEmpty() =
        runVmTest {
            val validator = FakeKeyValidator(Result.success(Unit))
            val vm = newVm(validator = validator)

            vm.onPasteChange("   \t\n  ")
            vm.connect()

            advanceUntilIdle()
            assertEquals("Paste your Gemini API key first.", vm.state.value.errorMessage)
            assertEquals(0, validator.callCount, "Whitespace-only must short-circuit just like an empty string")
        }

    // ---- connect(): success persists the key + clears the paste field --------------

    @Test
    fun connect_onSuccess_persistsKey_clearsPaste_andLeavesNoError() =
        runVmTest {
            val vault = FakeKeyVault()
            val vm = newVm(vault = vault, result = Result.success(Unit))

            vm.onPasteChange("good-key")
            vm.connect()
            advanceUntilIdle()

            val state = vm.state.value
            assertEquals("", state.pasteValue, "Paste field must clear so the secret isn't sitting in UI state")
            assertNull(state.errorMessage, "No error should remain after a successful validation")
            assertEquals("good-key", vault.stored, "Key must be persisted via the repository on success")
        }

    // ---- connect(): each failure branch routes through errorCopy ------------------

    @Test
    fun connect_onKeyInvalid_showsRejectedMessage_andDoesNotPersistKey() =
        runVmTest {
            val vault = FakeKeyVault()
            val vm = newVm(vault = vault, result = aiFailure(AiError.KeyInvalid))

            vm.onPasteChange("bad-key")
            vm.connect()
            advanceUntilIdle()

            assertEquals(errorCopy(AiError.KeyInvalid), vm.state.value.errorMessage)
            assertNull(vault.stored, "A rejected key must NOT be saved — that's the whole point of pre-validation")
            assertEquals("bad-key", vm.state.value.pasteValue, "Paste must remain so the user can edit and retry")
        }

    @Test
    fun connect_onRateLimited_showsRateLimitedMessage() =
        runVmTest {
            val vm = newVm(result = aiFailure(AiError.RateLimited))

            vm.onPasteChange("k")
            vm.connect()
            advanceUntilIdle()

            assertEquals(errorCopy(AiError.RateLimited), vm.state.value.errorMessage)
        }

    @Test
    fun connect_onNetworkError_showsConnectionMessage() =
        runVmTest {
            val vm = newVm(result = aiFailure(AiError.Network))

            vm.onPasteChange("k")
            vm.connect()
            advanceUntilIdle()

            assertEquals(errorCopy(AiError.Network), vm.state.value.errorMessage)
        }

    // ---- connect(): idempotency under double-tap -----------------------------------

    @Test
    fun connect_whileAlreadyVerifying_isANoOp_andDoesNotIssueASecondRequest() =
        runVmTest {
            // Synchronous double-tap: both calls read `verifying = false`, second
            // call must short-circuit on the VM-level guard rather than racing on
            // setKey() and burning two validation requests.
            val gate = CompletableDeferred<Result<Unit>>()
            val validator = FakeKeyValidator { gate.await() }
            val vm = newVm(validator = validator)

            vm.onPasteChange("k")
            vm.connect()
            vm.connect() // should short-circuit

            // Allow the (single) in-flight call to complete.
            gate.complete(Result.success(Unit))
            advanceUntilIdle()

            assertEquals(
                1,
                validator.callCount,
                "Two synchronous taps must collapse to a single validation call — otherwise " +
                    "we'd race on setKey() and waste rate-limit budget",
            )
        }

    // ---- onPasteChange: clears stale error so the user can retry ------------------

    @Test
    fun onPasteChange_clearsExistingErrorMessage() =
        runVmTest {
            val vm = newVm(result = aiFailure(AiError.KeyInvalid))

            vm.onPasteChange("first-bad")
            vm.connect()
            advanceUntilIdle()
            assertTrue(vm.state.value.errorMessage != null, "fixture: error must be set before the assertion")

            vm.onPasteChange("second-")

            assertNull(vm.state.value.errorMessage, "Editing the paste field must clear stale error copy")
        }

    // ---- confirmDisconnect: clears repo + paste field -----------------------------

    @Test
    fun cancelDisconnect_dismissesDialog_andLeavesVaultAndSummariesIntact() =
        runVmTest {
            // Tapping "Cancel" on the disconnect-confirm dialog must NOT touch
            // the vault OR the cached-summaries table — otherwise a misclick on
            // the confirmation step would silently revoke a working key AND
            // wipe every summary the user has paid Gemini to generate. This
            // test exists specifically to catch a wiring regression where
            // `cancelDisconnect` accidentally calls `disconnect`.
            val vault = FakeKeyVault(initial = "still-good-key")
            val (vm, db) = newVmWithDb(vault = vault)
            seedSummaryRow(db, episodeId = "ep-keep")
            vm.requestDisconnect()
            assertEquals(true, vm.state.value.showDisconnectConfirm, "fixture: dialog must be open before cancel")

            vm.cancelDisconnect()
            advanceUntilIdle()

            assertEquals(false, vm.state.value.showDisconnectConfirm, "Dialog must close on cancel")
            assertEquals("still-good-key", vault.stored, "Cancel must NEVER reach the vault — that would silently revoke the key")
            assertEquals(
                1,
                db.episodeAiSummaryQueries.selectByEpisode("ep-keep").executeAsList().size,
                "Cancel must NEVER wipe cached summaries — that's confirmDisconnect's job",
            )
        }

    @Test
    fun confirmDisconnect_alsoWipesCachedSummaries() =
        runVmTest {
            // Slice 4 contract: Disconnect removes both halves of the user's AI
            // footprint. The dialog copy promises this; the call site is the
            // only place that wires it up. A regression here would leave
            // summaries on disk after the key is gone — surprising and a quiet
            // privacy footgun.
            val vault = FakeKeyVault(initial = "to-be-revoked")
            val (vm, db) = newVmWithDb(vault = vault)
            seedSummaryRow(db, episodeId = "ep-1")
            seedSummaryRow(db, episodeId = "ep-2")
            vm.requestDisconnect()

            vm.confirmDisconnect()
            advanceUntilIdle()

            assertEquals(null, vault.stored, "Confirm must clear the key")
            assertTrue(
                db.episodeAiSummaryQueries.selectByEpisode("ep-1").executeAsList().isEmpty() &&
                    db.episodeAiSummaryQueries.selectByEpisode("ep-2").executeAsList().isEmpty(),
                "Confirm must wipe ALL cached summaries — leaving any behind violates the dialog's promise",
            )
        }

    @Test
    fun confirmDisconnect_callsRepositoryDisconnect_andClearsTransientState() =
        runVmTest {
            val vault = FakeKeyVault(initial = "existing-key")
            val vm = newVm(vault = vault)
            vm.requestDisconnect()
            vm.onPasteChange("about-to-be-cleared")

            vm.confirmDisconnect()
            advanceUntilIdle()

            val state = vm.state.value
            assertNull(vault.stored, "disconnect must reach the vault, not just the in-memory flag")
            assertEquals(false, state.connected)
            assertEquals(false, state.showDisconnectConfirm, "Dialog must close after confirm")
            assertEquals("", state.pasteValue, "Paste field must be cleared on disconnect")
        }

    // -------------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------------

    private data class VmFixture(val vm: AiSetupViewModel, val db: KofipodDatabase)

    private fun TestScope.newVm(
        vault: FakeKeyVault = FakeKeyVault(),
        result: Result<Unit> = Result.success(Unit),
        validator: FakeKeyValidator = FakeKeyValidator(result),
    ): AiSetupViewModel = newVmWithDb(vault, result, validator).vm

    /**
     * Variant that exposes the underlying database so disconnect-cleanup tests
     * can seed an `EpisodeAiSummary` row and assert it gets wiped. Most tests
     * don't need the DB and use [newVm] for symmetry with the existing fixture.
     */
    private fun TestScope.newVmWithDb(
        vault: FakeKeyVault = FakeKeyVault(),
        result: Result<Unit> = Result.success(Unit),
        validator: FakeKeyValidator = FakeKeyValidator(result),
    ): VmFixture {
        // Use the test scheduler for SQLDelight flow emissions — without this,
        // SettingsRepository defaults to Dispatchers.Default for its flowContext
        // and `aiModel()` never emits during the test, leaving the VM's combine
        // parked on its initial value. Symptom: flaky null vs. expected message
        // depending on JVM-thread scheduling.
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val appScope = CoroutineScope(testDispatcher)
        val db = inMemoryDatabase()
        val config =
            AiConfigRepository(
                keyVault = vault,
                settings = SettingsRepository(db, flowContext = testDispatcher),
                appScope = appScope,
            )
        // Real AiSummaryRepository against the in-memory DB so confirmDisconnect's
        // clearAll() exercises the actual SQL, not a stub. The summariser /
        // transcripts / episodes seams are stubs because nothing in this test
        // file calls generate() — only clearAll(), which only touches the DB.
        val summaries =
            AiSummaryRepository(
                db = db,
                aiConfig = config,
                summariser = NoopTextSummariser,
                transcripts = NoopTranscriptFetcher,
                episodes = EmptyEpisodeSource,
                appScope = appScope,
                ioContext = testDispatcher,
            )
        advanceUntilIdle()
        val vm = AiSetupViewModel(config = config, client = validator, summaries = summaries)
        // The VM exposes `state` via stateIn(WhileSubscribed); without an active
        // collector its `.value` is frozen on the initial AiSetupUiState() and
        // assertions can't observe MutableStateFlow updates flowing through `combine`.
        backgroundScope.launch { vm.state.collect { /* keep subscription open */ } }
        advanceUntilIdle()
        return VmFixture(vm, db)
    }

    private fun seedSummaryRow(
        db: KofipodDatabase,
        episodeId: String,
    ) {
        db.episodeAiSummaryQueries.upsert(
            episodeId = episodeId,
            generatedAtMs = 1L,
            modelId = GeminiModel.Flash.apiId,
            sourceKind = AiSourceKind.Transcript.wire,
            sourceFingerprint = "https://example.com/$episodeId.vtt",
            summary = "summary body for $episodeId",
            peopleJson = "[]",
            thingsJson = "[]",
            linksJson = "[]",
        )
    }

    private fun aiFailure(error: AiError): Result<Unit> = Result.failure(AiErrorException(error))

    private class FakeKeyValidator(
        private val handler: suspend () -> Result<Unit>,
    ) : KeyValidator {
        constructor(result: Result<Unit>) : this({ result })

        var callCount: Int = 0
            private set

        override suspend fun validate(
            apiKey: String,
            model: GeminiModel,
        ): Result<Unit> {
            callCount += 1
            return handler()
        }
    }

    private class FakeKeyVault(initial: String? = null) : KeyVault {
        var stored: String? = initial
            private set

        override suspend fun get(): String? = stored?.takeIf { it.isNotBlank() }

        override suspend fun set(value: String) {
            stored = value
        }

        override suspend fun clear() {
            stored = null
        }
    }

    /**
     * Stubs for the [AiSummaryRepository] dependencies that this test file does
     * not exercise. Only `clearAll()` is invoked from `confirmDisconnect`, and
     * that goes straight to SQLDelight — never through these seams.
     */
    private object NoopTextSummariser : TextSummariser {
        override suspend fun generateFromText(
            apiKey: String,
            model: GeminiModel,
            prompt: String,
            content: String,
        ): Result<String> = error("AiSetupViewModelTest must not exercise the summariser")
    }

    private object NoopTranscriptFetcher : TranscriptFetcher {
        override suspend fun fetch(url: String): Result<String> = error("AiSetupViewModelTest must not exercise the transcript fetcher")
    }

    private object EmptyEpisodeSource : EpisodeSource {
        override fun episodesFlow(podcastId: String): Flow<List<Episode>> = flowOf(emptyList())

        override fun episodeFlow(episodeId: String): Flow<Episode?> = flowOf(null)

        override fun newEpisodeCountsFlow(): Flow<Map<String, Int>> = flowOf(emptyMap())

        override suspend fun refresh(
            podcastId: String,
            feedId: Long,
            nowMillis: Long,
        ): RefreshResult = RefreshResult(emptyList(), 0)
    }
}
