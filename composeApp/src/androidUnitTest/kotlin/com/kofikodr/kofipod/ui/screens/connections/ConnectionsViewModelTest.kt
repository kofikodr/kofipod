// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.connections

import com.kofikodr.kofipod.pkm.connections.ConnectionKind
import com.kofikodr.kofipod.pkm.connections.PkmConnection
import com.kofikodr.kofipod.pkm.sinks.ReadwiseClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsViewModelTest {
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun invalidReadwiseTokenSurfacesError() =
        runTest {
            val vm = buildVm(verifyResult = false)

            vm.openReadwiseDialog()
            vm.onReadwiseTokenChange("bad-token")
            vm.connectReadwise()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertNotNull(state.readwiseError, "readwiseError must be set when verify returns false")
            assertEquals(false, state.readwiseValidating, "readwiseValidating must clear after failure")
        }

    @Test
    fun validReadwiseTokenPersistsConnection() =
        runTest {
            val source = FakeConnectionsSource()
            val vm = buildVm(source = source, verifyResult = true)

            vm.openReadwiseDialog()
            vm.onReadwiseTokenChange("good")
            vm.connectReadwise()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(false, state.readwiseDialogOpen, "Dialog must close on successful connect")
            assertNull(state.readwiseError, "No error should remain after a successful connect")

            val connect = source.connects.firstOrNull { it.kind == ConnectionKind.Readwise }
            assertNotNull(connect, "A connect() call with kind=Readwise must have been recorded")
            assertEquals("readwise.token", connect.tokenRef)
            assertEquals("good", connect.tokenValue)
        }

    @Test
    fun closeReadwiseDialogCancelsInflightValidate() =
        runTest {
            val source = FakeConnectionsSource()
            val vm = buildVm(source = source, verifyResult = null)

            vm.openReadwiseDialog()
            vm.onReadwiseTokenChange("token")
            vm.connectReadwise()
            advanceUntilIdle()
            // mid-validate: validating == true, dialog open
            assertEquals(true, vm.uiState.value.readwiseValidating)

            vm.closeReadwiseDialog()
            advanceUntilIdle()
            // After close: dialog closed, validating cleared, no connect was recorded
            assertEquals(false, vm.uiState.value.readwiseDialogOpen)
            assertEquals(false, vm.uiState.value.readwiseValidating)
            assertNull(vm.uiState.value.readwiseError)
            assertEquals(0, source.connects.size)

            // Reopen — fresh state
            vm.openReadwiseDialog()
            assertEquals(true, vm.uiState.value.readwiseDialogOpen)
        }

    // ---- fixtures ----

    private fun TestScope.buildVm(
        source: FakeConnectionsSource = FakeConnectionsSource(),
        verifyResult: Boolean? = false,
    ): ConnectionsViewModel {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        // Route Dispatchers.Main to the test scheduler so viewModelScope.launch
        // (Main.immediate by default) is observable via advanceUntilIdle().
        // ConnectionsViewModel post-rescope uses viewModelScope for the
        // observeAll() collector and Readwise validation.
        Dispatchers.setMain(testDispatcher)
        val appScope = CoroutineScope(testDispatcher)
        return ConnectionsViewModel(
            connections = source,
            readwiseClient = FakeReadwiseClient(verifyResult),
            appScope = appScope,
            clock = FixedClock(0L),
        )
    }

    private class FakeReadwiseClient(private val verifyResult: Boolean?) :
        ReadwiseClient(
            HttpClient(MockEngine) {
                engine { addHandler { respond("", HttpStatusCode.OK) } }
            },
        ) {
        override suspend fun verify(token: String): Boolean = verifyResult ?: awaitCancellation()
    }

    data class ConnectCall(
        val kind: ConnectionKind,
        val tokenRef: String?,
        val tokenValue: String?,
    )

    private class FakeConnectionsSource : ConnectionsSource {
        val connects = mutableListOf<ConnectCall>()
        private val rows = MutableStateFlow<List<PkmConnection>>(emptyList())

        override fun observeAll(): Flow<List<PkmConnection>> = rows

        override suspend fun connect(
            kind: ConnectionKind,
            tokenRef: String?,
            tokenValue: String?,
            folderUri: String?,
            nowMs: Long,
        ) {
            connects += ConnectCall(kind, tokenRef, tokenValue)
            rows.value = rows.value +
                PkmConnection(
                    id = kind.wire,
                    kind = kind,
                    tokenRef = tokenRef,
                    folderUri = folderUri,
                    enabledAtMs = nowMs,
                    lastSyncAtMs = null,
                )
        }

        override suspend fun disconnect(kind: ConnectionKind) {
            rows.value = rows.value.filter { it.kind != kind }
        }
    }

    private class FixedClock(private val epochMs: Long) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(epochMs)
    }
}
