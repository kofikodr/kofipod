// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [FakeDiagnosticsConfigRepository], the in-memory test
 * double that other tests in this module substitute for the real
 * [AndroidDiagnosticsConfigRepository]. The Android impl's persistence
 * (EncryptedSharedPreferences round-trip, file-system durability, MasterKey
 * setup) is intentionally NOT covered here — the project's testing scope
 * is JVM-only (Compose UI tests + Paparazzi snapshots), and adding
 * Robolectric for one repository's prefs path is out of scope. This suite
 * just guarantees the fake behaves like the contract requires, so any
 * test using it as a substitute can rely on the documented semantics.
 */
class FakeDiagnosticsConfigRepositoryContractTest {
    @Test
    fun `defaults — crashes on, usage on, disclosure not acknowledged`() =
        runTest {
            val repo = FakeDiagnosticsConfigRepository()
            assertTrue(repo.crashesEnabled.first())
            assertTrue(repo.usageEnabled.first())
            assertFalse(repo.disclosureAcknowledged.first())
        }

    @Test
    fun `setCrashesEnabled false flips the crashes flow`() =
        runTest {
            val repo = FakeDiagnosticsConfigRepository()
            repo.setCrashesEnabled(false)
            assertFalse(repo.crashesEnabled.first())
            assertTrue(repo.usageEnabled.first())
        }

    @Test
    fun `setUsageEnabled false flips the usage flow`() =
        runTest {
            val repo = FakeDiagnosticsConfigRepository()
            repo.setUsageEnabled(false)
            assertFalse(repo.usageEnabled.first())
            assertTrue(repo.crashesEnabled.first())
        }

    @Test
    fun `acknowledgeDisclosure flips the acknowledgement flow`() =
        runTest {
            val repo = FakeDiagnosticsConfigRepository()
            repo.acknowledgeDisclosure()
            assertTrue(repo.disclosureAcknowledged.first())
        }

    @Test
    fun `flags are independent`() =
        runTest {
            val repo = FakeDiagnosticsConfigRepository()
            repo.setCrashesEnabled(false)
            repo.setUsageEnabled(false)
            repo.acknowledgeDisclosure()
            assertFalse(repo.crashesEnabled.first())
            assertFalse(repo.usageEnabled.first())
            assertTrue(repo.disclosureAcknowledged.first())
        }

    @Test
    fun `setting flags emits new values to existing collectors`() =
        runTest {
            val repo = FakeDiagnosticsConfigRepository()
            val emissions = mutableListOf<Boolean>()
            emissions.add(repo.crashesEnabled.first())
            repo.setCrashesEnabled(false)
            emissions.add(repo.crashesEnabled.first())
            assertEquals(listOf(true, false), emissions)
        }
}

class FakeDiagnosticsConfigRepository : DiagnosticsConfigRepository {
    private val crashes = MutableStateFlow(true)
    private val usage = MutableStateFlow(true)
    private val ack = MutableStateFlow(false)

    override val crashesEnabled: Flow<Boolean> = crashes
    override val usageEnabled: Flow<Boolean> = usage
    override val disclosureAcknowledged: Flow<Boolean> = ack

    override suspend fun setCrashesEnabled(enabled: Boolean) {
        crashes.value = enabled
    }

    override suspend fun setUsageEnabled(enabled: Boolean) {
        usage.value = enabled
    }

    override suspend fun acknowledgeDisclosure() {
        ack.value = true
    }
}
