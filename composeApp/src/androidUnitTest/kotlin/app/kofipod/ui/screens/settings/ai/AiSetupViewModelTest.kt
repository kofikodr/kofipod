// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.settings.ai

import app.kofipod.ai.AiConfigRepository
import app.kofipod.ai.AiError
import app.kofipod.ai.AiErrorException
import app.kofipod.ai.GeminiModel
import app.kofipod.ai.KeyValidator
import app.kofipod.ai.KeyVault
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.testing.inMemoryDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    fun cancelDisconnect_dismissesDialog_andLeavesVaultIntact() =
        runVmTest {
            // Tapping "Cancel" on the disconnect-confirm dialog must NOT touch
            // the vault — otherwise a misclick on the confirmation step would
            // silently revoke a working key. This test exists specifically to
            // catch a wiring regression where `cancelDisconnect` accidentally
            // calls `disconnect` (e.g. via copy-paste from `confirmDisconnect`).
            val vault = FakeKeyVault(initial = "still-good-key")
            val vm = newVm(vault = vault)
            vm.requestDisconnect()
            assertEquals(true, vm.state.value.showDisconnectConfirm, "fixture: dialog must be open before cancel")

            vm.cancelDisconnect()
            advanceUntilIdle()

            assertEquals(false, vm.state.value.showDisconnectConfirm, "Dialog must close on cancel")
            assertEquals("still-good-key", vault.stored, "Cancel must NEVER reach the vault — that would silently revoke the key")
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

    private fun TestScope.newVm(
        vault: FakeKeyVault = FakeKeyVault(),
        result: Result<Unit> = Result.success(Unit),
        validator: FakeKeyValidator = FakeKeyValidator(result),
    ): AiSetupViewModel {
        // Use the test scheduler for SQLDelight flow emissions — without this,
        // SettingsRepository defaults to Dispatchers.Default for its flowContext
        // and `aiModel()` never emits during the test, leaving the VM's combine
        // parked on its initial value. Symptom: flaky null vs. expected message
        // depending on JVM-thread scheduling.
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val appScope = CoroutineScope(testDispatcher)
        val config =
            AiConfigRepository(
                keyVault = vault,
                settings = SettingsRepository(inMemoryDatabase(), flowContext = testDispatcher),
                appScope = appScope,
            )
        advanceUntilIdle()
        val vm = AiSetupViewModel(config = config, client = validator)
        // The VM exposes `state` via stateIn(WhileSubscribed); without an active
        // collector its `.value` is frozen on the initial AiSetupUiState() and
        // assertions can't observe MutableStateFlow updates flowing through `combine`.
        backgroundScope.launch { vm.state.collect { /* keep subscription open */ } }
        advanceUntilIdle()
        return vm
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
}
