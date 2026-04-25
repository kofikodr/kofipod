// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.repo

import app.kofipod.data.repo.SettingsRepository
import app.kofipod.data.repo.UpdateRepository
import app.kofipod.data.repo.UpdateUiState
import app.kofipod.testing.inMemoryDatabase
import app.kofipod.update.LocalApkPathStore
import app.kofipod.update.UpdateInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateRepositoryTest {
    @Test
    fun emptyState_emitsUpToDate() =
        runHarnessTest {
            assertNull(repo.readUpdateInfoSnapshot(), "fresh DB should have no update info row")
            val state = repo.state().first()
            assertTrue(state is UpdateUiState.UpToDate, "fresh DB with no metadata should be UpToDate, got $state")
            assertNull(state.lastCheckedAtMs)
        }

    @Test
    fun storeAvailable_thenState_emitsAvailable() =
        runHarnessTest {
            repo.storeAvailable(SAMPLE_INFO)
            repo.markChecked(timestampMs = 1_700_000_000_000L)

            val state = repo.state().first()
            assertTrue(state is UpdateUiState.Available, "expected Available, got $state")
            assertEquals(SAMPLE_INFO.version, state.info.version)
            assertEquals(1_700_000_000_000L, state.lastCheckedAtMs)
        }

    @Test
    fun markApkDownloaded_movesAvailableToReadyToInstall() =
        runHarnessTest {
            repo.storeAvailable(SAMPLE_INFO)

            repo.markApkDownloaded("/data/data/app.kofipod/files/updates/kofipod-1.2.0.apk")

            val state = repo.state().first()
            assertTrue(state is UpdateUiState.ReadyToInstall, "expected ReadyToInstall, got $state")
            assertEquals(SAMPLE_INFO.version, state.info.version)
            assertEquals("/data/data/app.kofipod/files/updates/kofipod-1.2.0.apk", state.apkPath)
        }

    @Test
    fun dismissCurrentVersion_returnsStateToUpToDate_evenWhenApkIsDownloaded() =
        runHarnessTest {
            repo.storeAvailable(SAMPLE_INFO)
            repo.markApkDownloaded("/path/to/kofipod-1.2.0.apk")

            repo.dismissCurrentVersion()

            val state = repo.state().first()
            assertTrue(
                state is UpdateUiState.UpToDate,
                "dismissing the current version should suppress the banner, got $state",
            )
        }

    @Test
    fun storeAvailable_sameVersionAgain_doesNotWipeDownloadedApkPath() =
        runHarnessTest {
            // Regression test for the daily-worker bug: re-confirming a known pending release
            // would clear KEY_UPDATE_DOWNLOADED_PATH unconditionally, dropping the UI from
            // ReadyToInstall back to Available every 24h cycle until the user reinstalled.
            repo.storeAvailable(SAMPLE_INFO)
            repo.markApkDownloaded("/path/to/kofipod-1.2.0.apk")

            // Worker runs again, finds the same release, calls storeAvailable with identical info.
            repo.storeAvailable(SAMPLE_INFO)

            assertEquals(
                "/path/to/kofipod-1.2.0.apk",
                repo.downloadedApkPathNow(),
                "re-confirming the same version must not invalidate the cached APK path",
            )
            val state = repo.state().first()
            assertTrue(state is UpdateUiState.ReadyToInstall, "state should remain ReadyToInstall, got $state")
        }

    @Test
    fun storeAvailable_newerVersion_invalidatesCachedApkPath() =
        runHarnessTest {
            // Inverse invariant: a version bump must drop the stale APK pointer so users
            // can't install yesterday's APK against today's release row.
            repo.storeAvailable(SAMPLE_INFO)
            repo.markApkDownloaded("/path/to/kofipod-1.2.0.apk")

            repo.storeAvailable(SAMPLE_INFO.copy(version = "1.3.0"))

            assertNull(
                repo.downloadedApkPathNow(),
                "a new version must invalidate the previous version's downloaded APK pointer",
            )
            val state = repo.state().first()
            assertTrue(state is UpdateUiState.Available, "state should fall back to Available, got $state")
            assertEquals("1.3.0", state.info.version)
        }

    @Test
    fun newerVersion_afterDismiss_resurrectsTheBanner() =
        runHarnessTest {
            // The dismiss is per-version, so a strictly-newer release should show up again
            // even if the user had skipped the previous one.
            repo.storeAvailable(SAMPLE_INFO)
            repo.dismissCurrentVersion()
            assertTrue(repo.state().first() is UpdateUiState.UpToDate)

            repo.storeAvailable(SAMPLE_INFO.copy(version = "1.3.0"))

            val state = repo.state().first()
            assertTrue(
                state is UpdateUiState.Available,
                "a new release after a dismiss must surface again, got $state",
            )
            assertEquals("1.3.0", state.info.version)
        }

    @Test
    fun clearAvailable_wipesAllUpdateMetadata() =
        runHarnessTest {
            repo.storeAvailable(SAMPLE_INFO)
            repo.markApkDownloaded("/path/to/kofipod-1.2.0.apk")

            repo.clearAvailable()

            assertNull(repo.readUpdateInfoSnapshot())
            assertNull(repo.downloadedApkPathNow())
            assertTrue(repo.state().first() is UpdateUiState.UpToDate)
        }

    /** In-memory [LocalApkPathStore] backed by a [MutableStateFlow] for deterministic flow emissions. */
    private class FakeLocalApkPathStore : LocalApkPathStore {
        private val flow = MutableStateFlow<String?>(null)

        override fun pathNow(): String? = flow.value

        override fun setPath(path: String?) {
            flow.value = path?.takeIf { it.isNotEmpty() }
        }

        override fun pathFlow() = flow
    }

    private class Harness(
        val repo: UpdateRepository,
        val settings: SettingsRepository,
        val apkStore: FakeLocalApkPathStore,
    )

    /**
     * Runs both the [SettingsRepository] flow context and the test scope on the same
     * [UnconfinedTestDispatcher]. With both sides on the same dispatcher, writes propagate
     * through `combine()` synchronously — `repo.state().first()` after a write returns the
     * new state without wall-clock waits.
     */
    private fun runHarnessTest(block: suspend Harness.() -> Unit) =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val db = inMemoryDatabase()
            val settings = SettingsRepository(db, flowContext = dispatcher)
            val apkStore = FakeLocalApkPathStore()
            val repo = UpdateRepository(settings = settings, localApk = apkStore)
            Harness(repo, settings, apkStore).block()
        }

    private companion object {
        val SAMPLE_INFO =
            UpdateInfo(
                version = "1.2.0",
                releaseUrl = "https://github.com/kofikodr/kofipod/releases/tag/v1.2.0",
                apkUrl = "https://github.com/kofikodr/kofipod/releases/download/v1.2.0/kofipod-1.2.0.apk",
                apkSizeBytes = 7_500_000L,
                releaseNotes = "Fix typo, ship it",
            )
    }
}
