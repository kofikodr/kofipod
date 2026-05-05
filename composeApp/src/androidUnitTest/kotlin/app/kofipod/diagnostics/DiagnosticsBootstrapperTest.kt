// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsBootstrapperTest {
    @Test
    fun `crashes effective is false when toggle on but disclosure not acknowledged`() =
        runTest {
            val toggle = MutableStateFlow(true)
            val ack = MutableStateFlow(false)
            val effective = DiagnosticsBootstrapper.effective(toggle, ack)
            assertEquals(false, effective.first())
        }

    @Test
    fun `effective is false when disclosure acknowledged but toggle off`() =
        runTest {
            val toggle = MutableStateFlow(false)
            val ack = MutableStateFlow(true)
            val effective = DiagnosticsBootstrapper.effective(toggle, ack)
            assertEquals(false, effective.first())
        }

    @Test
    fun `effective is true only when both toggle and acknowledgement are true`() =
        runTest {
            val toggle = MutableStateFlow(true)
            val ack = MutableStateFlow(true)
            val effective = DiagnosticsBootstrapper.effective(toggle, ack)
            assertEquals(true, effective.first())
        }

    @Test
    fun `effective re-emits when acknowledgement flips`() =
        runTest {
            val toggle = MutableStateFlow(true)
            val ack = MutableStateFlow(false)
            val effective = DiagnosticsBootstrapper.effective(toggle, ack)
            val emissions = mutableListOf<Boolean>()
            emissions.add(effective.first())
            ack.value = true
            emissions.add(effective.first())
            assertEquals(listOf(false, true), emissions)
        }

    @Test
    fun `telemetryReady flips true only after Telemetry enable returns`() =
        runTest(UnconfinedTestDispatcher()) {
            val config = FakeConfig(usage = true, ack = true, crashes = true)
            val telemetry = RecordingTelemetry()
            val bootstrapper =
                DiagnosticsBootstrapper(
                    config = config,
                    crashes = NoOpCrashReporter,
                    telemetry = telemetry,
                    appScope = backgroundScope,
                )
            bootstrapper.start()
            assertTrue("telemetry.enable() must be called before telemetryReady flips", telemetry.enabled)
            assertEquals(true, bootstrapper.telemetryReady.first())
        }

    @Test
    fun `telemetryReady stays false until disclosure is acknowledged`() =
        runTest(UnconfinedTestDispatcher()) {
            val config = FakeConfig(usage = true, ack = false, crashes = true)
            val telemetry = RecordingTelemetry()
            val bootstrapper =
                DiagnosticsBootstrapper(
                    config = config,
                    crashes = NoOpCrashReporter,
                    telemetry = telemetry,
                    appScope = backgroundScope,
                )
            bootstrapper.start()
            assertEquals(false, bootstrapper.telemetryReady.first())
            assertEquals(false, telemetry.enabled)
        }
}

private class FakeConfig(
    usage: Boolean,
    ack: Boolean,
    crashes: Boolean,
) : DiagnosticsConfigRepository {
    override val crashesEnabled: Flow<Boolean> = MutableStateFlow(crashes)
    override val usageEnabled: Flow<Boolean> = MutableStateFlow(usage)
    override val disclosureAcknowledged: Flow<Boolean> = MutableStateFlow(ack)

    override suspend fun setCrashesEnabled(enabled: Boolean) = Unit

    override suspend fun setUsageEnabled(enabled: Boolean) = Unit

    override suspend fun acknowledgeDisclosure() = Unit
}

private class RecordingTelemetry : Telemetry {
    var enabled: Boolean = false
        private set

    override fun enable() {
        enabled = true
    }

    override fun disable() {
        enabled = false
    }

    override fun track(event: TelemetryEvent) = Unit

    override fun debugSmokeTest(eventName: String): String = "recording"
}
