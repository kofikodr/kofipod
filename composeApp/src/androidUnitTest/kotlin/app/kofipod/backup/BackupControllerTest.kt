// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.backup

import app.kofipod.ui.UiEventBus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Exercises the orchestration contract — single-flight, error mapping, restore confirm
 * flow, exit-process gating. The repo dependency is real (it's pure logic and exercising
 * it through the controller catches integration mistakes that mocking would miss); the
 * port + store are fakes that record calls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupControllerTest {
    /**
     * Use [UnconfinedTestDispatcher] so coroutines launched into [appScope] run eagerly
     * and we don't have to interleave [advanceUntilIdle] calls between every state
     * change. The `runTest` body still runs on a [TestScope] backed by the same
     * `testScheduler`, so `advanceUntilIdle()` calls remain valid for waiting on suspend
     * points (e.g. `delay(...)` in `confirmRestore`).
     */
    private fun runCt(block: suspend TestScope.() -> Unit) =
        runTest {
            block()
        }

    private val fixedClock =
        object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(1_750_000_000_000L)
        }

    private val sampleDb = "DB CONTENTS".encodeToByteArray()
    private var stagedPayload: ByteArray? = null

    private fun realRepo() =
        BackupRepository(
            dbFileBytes = { sampleDb },
            stageDb = { stagedPayload = it },
            appVersionCode = 7,
            appVersionName = "0.7.0",
            dbSchemaVersion = 15,
            clock = fixedClock,
        )

    @Test
    fun runBackup_writesPayloadAndUpdatesLastBackup() =
        runTest {
            val store = FakeStore(initialTreeUri = "tree://test")
            val port = FakePort()
            val controller = newController(port = port, store = store, scope = TestScope(UnconfinedTestDispatcher(testScheduler)))

            controller.runBackup()
            advanceUntilIdle()

            assertEquals(1, port.writes.size, "writeBackup called exactly once")
            assertEquals("tree://test", port.writes.single().treeUri)
            assertNotNull(store.recordedLastBackupAt, "lastBackupAt is set after success")
            assertEquals(BackupAction.Idle, controller.action.value)
        }

    @Test
    fun runBackup_isNoOp_whenNoFolderConfigured() =
        runTest {
            val port = FakePort()
            val store = FakeStore(initialTreeUri = null)
            val controller = newController(port = port, store = store, scope = TestScope(UnconfinedTestDispatcher(testScheduler)))

            controller.runBackup()
            advanceUntilIdle()

            assertTrue(port.writes.isEmpty(), "no write attempted without a folder")
            assertEquals(BackupAction.Idle, controller.action.value)
            assertNull(store.recordedLastBackupAt)
        }

    @Test
    fun runBackup_concurrentTaps_collapseToOneWrite() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val port =
                FakePort(writeBlock = { _, _ ->
                    // Block the first write so the second tap arrives while the first is in flight.
                    gate.await()
                })
            val store = FakeStore(initialTreeUri = "tree://test")
            val controller = newController(port = port, store = store, scope = TestScope(UnconfinedTestDispatcher(testScheduler)))

            controller.runBackup()
            controller.runBackup()
            controller.runBackup()
            advanceUntilIdle()

            // Only one write started; state stuck in BackingUp until we release the gate.
            assertEquals(BackupAction.BackingUp, controller.action.value)
            assertEquals(1, port.writes.size)

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(BackupAction.Idle, controller.action.value)
            assertEquals(1, port.writes.size, "still exactly one write after second + third tap")
        }

    @Test
    fun runBackup_securityException_surfacesFolderRevokedMessage() =
        runTest {
            val port =
                FakePort(writeBlock = { _, _ ->
                    throw SecurityException("simulated revoke")
                })
            val store = FakeStore(initialTreeUri = "tree://test")
            val controller = newController(port = port, store = store, scope = TestScope(UnconfinedTestDispatcher(testScheduler)))

            controller.runBackup()
            advanceUntilIdle()

            val state = controller.action.value
            val err = state as? BackupAction.Error ?: fail("expected Error, got $state")
            assertTrue(
                err.message.contains("no longer accessible", ignoreCase = true),
                "expected revoke-specific message, got '${err.message}'",
            )
            assertNull(store.recordedLastBackupAt, "no last-backup recorded on failure")
        }

    @Test
    fun dismissError_clearsErrorState() =
        runTest {
            // Drive into Error via a write that throws, then dismiss.
            val port =
                FakePort(writeBlock = { _, _ ->
                    error("simulated write failure")
                })
            val store = FakeStore(initialTreeUri = "tree://test")
            val controller = newController(port = port, store = store, scope = TestScope(UnconfinedTestDispatcher(testScheduler)))

            controller.runBackup()
            advanceUntilIdle()
            assertTrue(controller.action.value is BackupAction.Error, "drove into error")

            controller.dismissError()

            assertEquals(BackupAction.Idle, controller.action.value)
        }

    @Test
    fun dismissError_isNoOp_whenIdle() =
        runTest {
            val controller =
                newController(
                    port = FakePort(),
                    store = FakeStore(initialTreeUri = "tree://test"),
                    scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
                )

            controller.dismissError() // idle stays idle

            assertEquals(BackupAction.Idle, controller.action.value)
        }

    @Test
    fun runRestore_invalidBackup_setsErrorAndDoesNotRequestConfirmation() =
        runTest {
            val port = FakePort(restorePayload = "garbage".encodeToByteArray())
            val store = FakeStore(initialTreeUri = "tree://test")
            val controller = newController(port = port, store = store, scope = TestScope(UnconfinedTestDispatcher(testScheduler)))

            controller.runRestore()
            advanceUntilIdle()

            val state = controller.action.value
            val err = state as? BackupAction.Error ?: fail("expected Error, got $state")
            assertEquals(RestoreError.ZipUnreadable.toUserMessage(), err.message)
            assertNull(controller.pendingRestoreConfirm.value, "no confirm dialog for invalid backup")
        }

    @Test
    fun runRestore_validBackup_publishesPendingConfirm_doesNotExit() =
        runTest {
            val backup = realRepo().buildBackup()
            var exited = false
            val port = FakePort(restorePayload = backup)
            val store = FakeStore(initialTreeUri = "tree://test")
            val controller =
                newController(
                    port = port,
                    store = store,
                    scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
                    exitProcess = { exited = true },
                )

            controller.runRestore()
            advanceUntilIdle()

            assertNotNull(controller.pendingRestoreConfirm.value, "confirm dialog requested")
            assertEquals(BackupAction.Restoring, controller.action.value)
            assertFalse(exited, "process must NOT exit before user confirms")
            assertNull(store.pendingRestore, "pending-restore flag not yet set")
        }

    @Test
    fun confirmRestore_stagesAndExits() =
        runTest {
            val backup = realRepo().buildBackup()
            var exited = false
            val port = FakePort(restorePayload = backup)
            val store = FakeStore(initialTreeUri = "tree://test")
            val controller =
                newController(
                    port = port,
                    store = store,
                    scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
                    exitProcess = { exited = true },
                )

            controller.runRestore()
            advanceUntilIdle()
            controller.confirmRestore()
            advanceUntilIdle()

            assertNotNull(stagedPayload, "DB payload staged")
            assertEquals(BackupController.STAGED_FILENAME, store.pendingRestore)
            assertTrue(exited, "exitProcess called after confirm")
        }

    @Test
    fun cancelRestoreConfirm_clearsPending_andReturnsToIdle() =
        runTest {
            val backup = realRepo().buildBackup()
            val port = FakePort(restorePayload = backup)
            val controller =
                newController(
                    port = port,
                    store = FakeStore(initialTreeUri = "tree://test"),
                    scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
                )

            controller.runRestore()
            advanceUntilIdle()
            assertNotNull(controller.pendingRestoreConfirm.value)

            controller.cancelRestoreConfirm()

            assertNull(controller.pendingRestoreConfirm.value)
            assertEquals(BackupAction.Idle, controller.action.value)
        }

    @Test
    fun notifyRestoreCompletedIfPending_emitsSnackbar_whenFlagSet() =
        runTest {
            // Pin the post-restart notification: when PendingRestore.consumeIfPresent
            // sets the "restore_completed" flag, AppShell's first-composition pass
            // should surface a "Library restored" snackbar. Pattern lifted from
            // UiEventBusTest — async + yield to ensure the collector is subscribed
            // before we emit (UiEventBus has replay=0).
            val store = FakeStoreWithRestoreFlag(initialFlag = true)
            val bus = app.kofipod.ui.UiEventBus()
            val collected = async { bus.events.first() }
            yield()

            val controller =
                BackupController(
                    repo = realRepo(),
                    port = FakePort(),
                    store = store,
                    bus = bus,
                    appScope = TestScope(UnconfinedTestDispatcher(testScheduler)),
                    clock = fixedClock,
                    exitProcess = {},
                )

            controller.notifyRestoreCompletedIfPending()

            val event = collected.await() as? app.kofipod.ui.UiEvent.Snackbar
            assertEquals("Library restored", event?.message)
            assertFalse(store.flagAfterRead, "flag must clear after read")
        }

    @Test
    fun notifyRestoreCompletedIfPending_isNoOp_whenFlagUnset() =
        runTest {
            // The other half of the contract: idempotent in absence. AppShell calls
            // this on every cold start, so a stale call after the flag's been
            // consumed must not emit a snackbar.
            val store = FakeStoreWithRestoreFlag(initialFlag = false)
            val bus = app.kofipod.ui.UiEventBus()
            // We can't easily prove the negative ("nothing was ever emitted") with a
            // SharedFlow without timing out, so we instead emit a known sentinel
            // afterwards and assert that's the FIRST event the collector saw.
            val collected = async { bus.events.first() }
            yield()

            val controller =
                BackupController(
                    repo = realRepo(),
                    port = FakePort(),
                    store = store,
                    bus = bus,
                    appScope = TestScope(UnconfinedTestDispatcher(testScheduler)),
                    clock = fixedClock,
                    exitProcess = {},
                )

            controller.notifyRestoreCompletedIfPending()
            // Sentinel — this is the only thing the collector should see.
            bus.emit(app.kofipod.ui.UiEvent.Snackbar("sentinel"))

            val event = collected.await() as? app.kofipod.ui.UiEvent.Snackbar
            assertEquals(
                "sentinel",
                event?.message,
                "if a 'Library restored' snackbar fired despite flag=false, it would arrive first",
            )
        }

    @Test
    fun runBackupAwaiting_writesOnceFromTheWorkerEntryPoint() =
        runTest {
            // The Slice 3 worker calls runBackupAwaiting() (not runBackup()), so we pin
            // that path: it picks up the configured folder and produces exactly one
            // write through the port. The state-guard contract that prevents
            // worker+manual double-writes is already pinned by
            // runBackup_concurrentTaps_collapseToOneWrite.
            val port = FakePort()
            val store = FakeStore(initialTreeUri = "tree://test")
            val controller = newController(port = port, store = store, scope = TestScope(UnconfinedTestDispatcher(testScheduler)))

            controller.runBackupAwaiting()

            assertEquals(1, port.writes.size, "worker entry point produced one write")
            assertEquals(BackupAction.Idle, controller.action.value, "controller returns to idle on success")
            assertNotNull(store.recordedLastBackupAt, "lastBackupAt updated on worker success")
        }

    private fun newController(
        port: FakePort,
        store: FakeStore,
        scope: CoroutineScope,
        exitProcess: () -> Unit = {},
    ) = BackupController(
        repo = realRepo(),
        port = port,
        store = store,
        bus = UiEventBus(),
        appScope = scope,
        clock = fixedClock,
        exitProcess = exitProcess,
    )

    private class FakePort(
        private val restorePayload: ByteArray? = null,
        private val writeBlock: (suspend (String, ByteArray) -> Unit)? = null,
    ) : BackupFilePort {
        data class WriteCall(val treeUri: String, val content: ByteArray)

        val writes = mutableListOf<WriteCall>()

        override suspend fun pickFolder(): String? = null

        override suspend fun writeBackup(
            treeUri: String,
            content: ByteArray,
        ) {
            writes += WriteCall(treeUri, content)
            writeBlock?.invoke(treeUri, content)
        }

        override suspend fun pickAndReadBackup(): ByteArray? = restorePayload
    }

    private class FakeStore(
        initialTreeUri: String? = null,
    ) : BackupFolderStore {
        private var treeUri: String? = initialTreeUri
        var recordedLastBackupAt: Long? = null
        var pendingRestore: String? = null

        override fun treeUriNow(): String? = treeUri

        override fun setTreeUri(uri: String?) {
            treeUri = uri
        }

        override fun treeUriFlow(): Flow<String?> = flowOf(treeUri)

        override fun lastBackupAtNow(): Long? = recordedLastBackupAt

        override fun setLastBackupAt(ms: Long?) {
            recordedLastBackupAt = ms
        }

        override fun lastBackupAtFlow(): Flow<Long?> = flowOf(recordedLastBackupAt)

        override fun pendingRestoreFilenameNow(): String? = pendingRestore

        override fun setPendingRestoreFilename(name: String?) {
            pendingRestore = name
        }

        override fun displayNameForTreeUri(uri: String): String? = "Test Folder"

        override fun consumeRestoreCompletedFlag(): Boolean = false
    }

    private class FakeStoreWithRestoreFlag(
        initialFlag: Boolean,
    ) : BackupFolderStore {
        private var flag: Boolean = initialFlag

        /** Captures whether the flag was cleared by the read — exposed for assertions. */
        val flagAfterRead: Boolean get() = flag

        override fun treeUriNow(): String? = null

        override fun setTreeUri(uri: String?) {}

        override fun treeUriFlow(): Flow<String?> = flowOf(null)

        override fun lastBackupAtNow(): Long? = null

        override fun setLastBackupAt(ms: Long?) {}

        override fun lastBackupAtFlow(): Flow<Long?> = flowOf(null)

        override fun pendingRestoreFilenameNow(): String? = null

        override fun setPendingRestoreFilename(name: String?) {}

        override fun displayNameForTreeUri(uri: String): String? = null

        override fun consumeRestoreCompletedFlag(): Boolean {
            val read = flag
            flag = false
            return read
        }
    }
}
