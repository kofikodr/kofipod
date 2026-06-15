// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.settings.podcastindex

import com.kofikodr.kofipod.data.api.PodcastIndexConfigRepository
import com.kofikodr.kofipod.data.api.PodcastIndexCredentialStore
import com.kofikodr.kofipod.data.api.PodcastIndexCreds
import com.kofikodr.kofipod.data.api.PodcastIndexValidation
import com.kofikodr.kofipod.data.api.PodcastIndexValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastIndexSetupViewModelTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeStore(var stored: PodcastIndexCreds? = null) : PodcastIndexCredentialStore {
        override suspend fun get() = stored

        override suspend fun set(creds: PodcastIndexCreds) {
            stored = creds
        }

        override suspend fun clear() {
            stored = null
        }
    }

    private class FakeValidator(val result: PodcastIndexValidation) : PodcastIndexValidator {
        var calls = 0

        override suspend fun validate(creds: PodcastIndexCreds): PodcastIndexValidation {
            calls++
            return result
        }
    }

    // The VM exposes `state` via stateIn(WhileSubscribed); without an active
    // collector its `.value` is frozen on the initial PodcastIndexSetupUiState()
    // and assertions can't observe MutableStateFlow updates flowing through `combine`.
    // backgroundScope keeps the subscription alive for the lifetime of the test.
    private fun kotlinx.coroutines.test.TestScope.vm(
        validator: PodcastIndexValidator,
        store: FakeStore,
        scope: CoroutineScope,
    ): PodcastIndexSetupViewModel {
        val vm = PodcastIndexSetupViewModel(PodcastIndexConfigRepository(store, scope), validator)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        return vm
    }

    @Test
    fun connect_validCreds_persistsAndClearsFields() =
        runTest {
            val store = FakeStore()
            val validator = FakeValidator(PodcastIndexValidation.Valid)
            val sut = vm(validator, store, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            sut.onKeyChange("k")
            sut.onSecretChange("s")
            sut.connect()
            advanceUntilIdle()
            assertEquals(PodcastIndexCreds("k", "s"), store.stored)
            assertTrue(sut.state.value.connected)
            assertEquals("", sut.state.value.keyValue)
            assertEquals("", sut.state.value.secretValue)
            assertNull(sut.state.value.errorMessage)
        }

    @Test
    fun connect_invalidCreds_doesNotPersist_showsError() =
        runTest {
            val store = FakeStore()
            val validator = FakeValidator(PodcastIndexValidation.Invalid)
            val sut = vm(validator, store, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            sut.onKeyChange("k")
            sut.onSecretChange("bad")
            sut.connect()
            advanceUntilIdle()
            assertNull(store.stored)
            assertFalse(sut.state.value.connected)
            assertEquals(invalidCredsCopy(), sut.state.value.errorMessage)
        }

    @Test
    fun connect_networkError_showsNetworkCopy() =
        runTest {
            val sut =
                vm(FakeValidator(PodcastIndexValidation.NetworkError), FakeStore(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            sut.onKeyChange("k")
            sut.onSecretChange("s")
            sut.connect()
            advanceUntilIdle()
            assertEquals(networkErrorCopy(), sut.state.value.errorMessage)
        }

    @Test
    fun connect_blankFields_showsPromptWithoutValidating() =
        runTest {
            val validator = FakeValidator(PodcastIndexValidation.Valid)
            val sut = vm(validator, FakeStore(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            sut.onKeyChange("k") // secret left blank
            sut.connect()
            advanceUntilIdle()
            assertEquals(0, validator.calls)
            assertEquals(missingFieldsCopy(), sut.state.value.errorMessage)
        }

    @Test
    fun disconnect_clearsStoredCreds() =
        runTest {
            val store = FakeStore(PodcastIndexCreds("k", "s"))
            val sut = vm(FakeValidator(PodcastIndexValidation.Valid), store, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            advanceUntilIdle()
            sut.requestDisconnect()
            sut.confirmDisconnect()
            advanceUntilIdle()
            assertNull(store.stored)
            assertFalse(sut.state.value.connected)
        }
}
