// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.backup

import com.kofikodr.kofipod.ui.UiEventBus
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
            val bus = com.kofikodr.kofipod.ui.UiEventBus()
            val collected = async { bus.events.first() }
            yield()

            val controller =
                BackupController(
                    repo = realRepo(),
                    port = FakePort(),
                    store = store,
                    settings = newSettings(),
                    bus = bus,
                    appScope = TestScope(UnconfinedTestDispatcher(testScheduler)),
                    clock = fixedClock,
                    exitProcess = {},
                )

            controller.notifyRestoreCompletedIfPending()

            val event = collected.await() as? com.kofikodr.kofipod.ui.UiEvent.Snackbar
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
            val bus = com.kofikodr.kofipod.ui.UiEventBus()
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
                    settings = newSettings(),
                    bus = bus,
                    appScope = TestScope(UnconfinedTestDispatcher(testScheduler)),
                    clock = fixedClock,
                    exitProcess = {},
                )

            controller.notifyRestoreCompletedIfPending()
            // Sentinel — this is the only thing the collector should see.
            bus.emit(com.kofikodr.kofipod.ui.UiEvent.Snackbar("sentinel"))

            val event = collected.await() as? com.kofikodr.kofipod.ui.UiEvent.Snackbar
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

    @Test
    fun runBackup_logsBackupRun_inSchedulerRunLog() =
        runTest {
            // Auto-backup must be distinguishable from podcast-download runs in the
            // Last-7-runs chart — pin the SchedulerRunLog `kind` field landing.
            val settings = newSettings()
            val store = FakeStore(initialTreeUri = "tree://test")
            val port = FakePort()
            val controller =
                newController(
                    port = port,
                    store = store,
                    scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
                    settings = settings,
                )

            controller.runBackup()
            advanceUntilIdle()

            val runs = com.kofikodr.kofipod.background.SchedulerRunLog.read(settings)
            assertEquals(1, runs.size, "exactly one run appended")
            assertEquals(
                com.kofikodr.kofipod.background.SchedulerRunKind.Backup,
                runs.single().runKind,
                "appended run is tagged as a backup",
            )
            assertEquals(0, runs.single().inserted, "backup runs do not carry insertion counts")
            // Pin that the injected clock — not wall time — reaches `appendBackup`.
            // A regression to `System.currentTimeMillis()` would silently land here.
            assertEquals(1_750_000_000_000L, runs.single().at, "run.at uses injected clock")
        }

    @Test
    fun runBackup_pruneRetainsLastFive_andDeletesOlder() =
        runTest {
            // Eight pre-existing files in the folder — none of them today's run. After the
            // controller writes the 9th, retention keeps the most recent five (by
            // lastModified) and deletes the rest. We use filename-timestamp fallback by
            // setting lastModifiedMs = 0 so the controller's sortKey() exercises the
            // parseBackupFilenameTimestamp path.
            val existing =
                listOf(
                    BackupFileInfo("kofipod-backup-20260101-100000.kpbak", 0L),
                    BackupFileInfo("kofipod-backup-20260102-100000.kpbak", 0L),
                    BackupFileInfo("kofipod-backup-20260103-100000.kpbak", 0L),
                    BackupFileInfo("kofipod-backup-20260104-100000.kpbak", 0L),
                    BackupFileInfo("kofipod-backup-20260105-100000.kpbak", 0L),
                    BackupFileInfo("kofipod-backup-20260106-100000.kpbak", 0L),
                    BackupFileInfo("kofipod-backup-20260107-100000.kpbak", 0L),
                    BackupFileInfo(LEGACY_BACKUP_FILENAME, 0L),
                )
            val port = FakePort(initialBackups = existing)
            val store = FakeStore(initialTreeUri = "tree://test")
            val controller =
                newController(
                    port = port,
                    store = store,
                    scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
                )

            controller.runBackup()
            advanceUntilIdle()

            // 8 existing + 1 new write = 9 total; retention deletes 4 oldest.
            // Pin BOTH partitions — deleted set AND retained set — so a sort-direction
            // bug that flips which end is "newest" would surface here.
            assertEquals(4, port.deletes.size, "four oldest entries pruned")
            assertTrue(LEGACY_BACKUP_FILENAME in port.deletes, "legacy filename treated as oldest backup")
            assertTrue("kofipod-backup-20260101-100000.kpbak" in port.deletes)
            assertTrue("kofipod-backup-20260102-100000.kpbak" in port.deletes)
            assertTrue("kofipod-backup-20260103-100000.kpbak" in port.deletes)

            // Survivor assertions: the five most recent files (by parsed filename
            // timestamp, since lastModifiedMs is 0 in this fixture) must NOT be in
            // the delete list. This catches a reversed-sort regression that would
            // otherwise silently delete the wrong half.
            val newWriteFilename = port.writes.single().filename
            listOf(
                "kofipod-backup-20260104-100000.kpbak",
                "kofipod-backup-20260105-100000.kpbak",
                "kofipod-backup-20260106-100000.kpbak",
                "kofipod-backup-20260107-100000.kpbak",
                newWriteFilename,
            ).forEach { survivor ->
                assertFalse(survivor in port.deletes, "expected $survivor to be retained")
            }
        }

    @Test
    fun runBackup_pruneFailure_doesNotFailTheBackup() =
        runTest {
            // Pruning is best-effort. If the provider refuses a delete, the backup itself
            // still succeeded — we must surface success, not turn this into a user error.
            // Six pre-existing files plus the new write = 7 total, which crosses the
            // retention cap so the prune actually fires and reaches the throwing delete.
            val files =
                List(6) { i ->
                    BackupFileInfo("kofipod-backup-2025010${i + 1}-100000.kpbak", 0L)
                }.toMutableList()
            val refusingPort =
                object : BackupFilePort {
                    val writes = mutableListOf<String>()

                    override suspend fun pickFolder(): String? = null

                    override suspend fun writeBackup(
                        treeUri: String,
                        filename: String,
                        content: ByteArray,
                    ) {
                        writes += filename
                        // Reflect the write so the subsequent prune sees 7 entries (real
                        // providers expose the new file on the next list call).
                        files += BackupFileInfo(filename, lastModifiedMs = 9_999_999_999_999L)
                    }

                    override suspend fun pickAndReadBackup(): ByteArray? = null

                    override suspend fun listBackups(treeUri: String): List<BackupFileInfo> = files.toList()

                    override suspend fun deleteBackup(
                        treeUri: String,
                        filename: String,
                    ): Boolean = throw RuntimeException("provider refused deletion")
                }
            val store = FakeStore(initialTreeUri = "tree://test")
            val controller =
                BackupController(
                    repo = realRepo(),
                    port = refusingPort,
                    store = store,
                    settings = newSettings(),
                    bus = UiEventBus(),
                    appScope = TestScope(UnconfinedTestDispatcher(testScheduler)),
                    clock = fixedClock,
                    exitProcess = {},
                )

            controller.runBackup()
            advanceUntilIdle()

            assertEquals(1, refusingPort.writes.size, "the backup write itself completed")
            assertEquals(BackupAction.Idle, controller.action.value, "controller is idle, not in Error")
            assertNotNull(store.recordedLastBackupAt, "lastBackupAt is still recorded")
        }

    private fun newController(
        port: FakePort,
        store: FakeStore,
        scope: CoroutineScope,
        exitProcess: () -> Unit = {},
        settings: com.kofikodr.kofipod.data.repo.SettingsRepository = newSettings(),
    ) = BackupController(
        repo = realRepo(),
        port = port,
        store = store,
        settings = settings,
        bus = UiEventBus(),
        appScope = scope,
        clock = fixedClock,
        exitProcess = exitProcess,
    )

    private class FakePort(
        private val restorePayload: ByteArray? = null,
        private val writeBlock: (suspend (String, ByteArray) -> Unit)? = null,
        private val initialBackups: List<BackupFileInfo> = emptyList(),
    ) : BackupFilePort {
        data class WriteCall(val treeUri: String, val filename: String, val content: ByteArray)

        val writes = mutableListOf<WriteCall>()
        val deletes = mutableListOf<String>()
        private val files = initialBackups.toMutableList()

        // Far-future marker that always wins over realistic 2025/2026 timestamps used
        // in fixture filenames. Sentinel rather than `Long.MAX_VALUE` so a debug print
        // stays readable.
        private val writeMarkerMs = 9_999_999_999_999L

        override suspend fun pickFolder(): String? = null

        override suspend fun writeBackup(
            treeUri: String,
            filename: String,
            content: ByteArray,
        ) {
            writes += WriteCall(treeUri, filename, content)
            writeBlock?.invoke(treeUri, content)
            // Reflect the write in the listing so subsequent prune sees the new file.
            // Real providers stamp newly written documents with the current wall clock;
            // we mirror that with a marker high enough to beat the test fixtures' parsed
            // filename timestamps. Tests that need a specific value can replace the file
            // entry afterwards.
            files.removeAll { it.filename == filename }
            files += BackupFileInfo(filename = filename, lastModifiedMs = writeMarkerMs)
        }

        override suspend fun pickAndReadBackup(): ByteArray? = restorePayload

        override suspend fun listBackups(treeUri: String): List<BackupFileInfo> = files.toList()

        override suspend fun deleteBackup(
            treeUri: String,
            filename: String,
        ): Boolean {
            deletes += filename
            return files.removeAll { it.filename == filename }
        }
    }

    /**
     * Real SettingsRepository backed by an in-memory SQLDelight DB. SchedulerRunLog
     * reads/writes through `getMetaNow` + `put`; the in-memory driver makes those
     * trivially observable without faking the repository surface.
     */
    private fun newSettings() =
        com.kofikodr.kofipod.data.repo.SettingsRepository(
            db = com.kofikodr.kofipod.testing.inMemoryDatabase(),
        )

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
