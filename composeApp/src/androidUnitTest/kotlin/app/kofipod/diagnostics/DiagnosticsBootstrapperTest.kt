// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `effective flips back to false when acknowledgement is revoked`() =
        runTest {
            val toggle = MutableStateFlow(true)
            val ack = MutableStateFlow(true)
            val effective = DiagnosticsBootstrapper.effective(toggle, ack)
            assertEquals(true, effective.first())
            ack.value = false
            assertEquals(false, effective.first())
        }

    /**
     * Enforces the actual ordering contract: at the moment Telemetry.enable()
     * is invoked, telemetryReady MUST still be false. The naive post-hoc
     * assertion (both true after start()) would pass even if the production
     * code set _telemetryReady = true BEFORE calling enable() — which is
     * exactly the cold-start race the readiness flow exists to prevent.
     */
    @Test
    fun `telemetryReady is still false at the moment Telemetry enable is invoked`() =
        runTest(UnconfinedTestDispatcher()) {
            val config = FakeConfig.of(usage = true, ack = true, crashes = true)
            val readyAtCallCapture = mutableListOf<Boolean>()
            lateinit var bootstrapper: DiagnosticsBootstrapper
            val telemetry =
                RecordingTelemetry(onEnable = { readyAtCallCapture += bootstrapper.telemetryReady.value })
            bootstrapper =
                DiagnosticsBootstrapper(
                    config = config,
                    crashes = NoOpCrashReporter,
                    telemetry = telemetry,
                    appScope = backgroundScope,
                )
            bootstrapper.start()
            assertEquals(listOf(false), readyAtCallCapture)
            assertTrue(telemetry.enabled)
            assertEquals(true, bootstrapper.telemetryReady.first())
        }

    @Test
    fun `telemetryReady stays false until disclosure is acknowledged`() =
        runTest(UnconfinedTestDispatcher()) {
            val config = FakeConfig.of(usage = true, ack = false, crashes = true)
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

    @Test
    fun `telemetryReady flips back to false and Telemetry disable is called when toggle goes off`() =
        runTest(UnconfinedTestDispatcher()) {
            val usage = MutableStateFlow(true)
            val config = FakeConfig(crashesFlow = MutableStateFlow(true), usageFlow = usage, ackFlow = MutableStateFlow(true))
            val telemetry = RecordingTelemetry()
            val bootstrapper =
                DiagnosticsBootstrapper(
                    config = config,
                    crashes = NoOpCrashReporter,
                    telemetry = telemetry,
                    appScope = backgroundScope,
                )
            bootstrapper.start()
            assertEquals(true, bootstrapper.telemetryReady.first())
            assertTrue(telemetry.enabled)
            usage.value = false
            assertEquals(false, bootstrapper.telemetryReady.first())
            assertFalse(telemetry.enabled)
        }

    @Test
    fun `telemetryReady still flips even if Telemetry enable throws`() =
        runTest(UnconfinedTestDispatcher()) {
            val config = FakeConfig.of(usage = true, ack = true, crashes = true)
            val telemetry = RecordingTelemetry(throwOnEnable = true)
            val bootstrapper =
                DiagnosticsBootstrapper(
                    config = config,
                    crashes = NoOpCrashReporter,
                    telemetry = telemetry,
                    appScope = backgroundScope,
                )
            bootstrapper.start()
            // Critical invariant: any awaiter (e.g. AppOpened in
            // KofipodApplication) must NOT suspend forever just because
            // the SDK init crashed. telemetryReady flips so awaiters
            // unblock; the broken SDK silently no-ops via track()'s gate.
            assertEquals(true, bootstrapper.telemetryReady.first())
        }
}

private class FakeConfig(
    val crashesFlow: MutableStateFlow<Boolean>,
    val usageFlow: MutableStateFlow<Boolean>,
    val ackFlow: MutableStateFlow<Boolean>,
) : DiagnosticsConfigRepository {
    override val crashesEnabled: Flow<Boolean> = crashesFlow
    override val usageEnabled: Flow<Boolean> = usageFlow
    override val disclosureAcknowledged: Flow<Boolean> = ackFlow

    override suspend fun setCrashesEnabled(enabled: Boolean) = Unit

    override suspend fun setUsageEnabled(enabled: Boolean) = Unit

    override suspend fun acknowledgeDisclosure() = Unit

    companion object {
        fun of(
            usage: Boolean,
            ack: Boolean,
            crashes: Boolean,
        ): FakeConfig = FakeConfig(MutableStateFlow(crashes), MutableStateFlow(usage), MutableStateFlow(ack))
    }
}

private class RecordingTelemetry(
    private val onEnable: () -> Unit = {},
    private val throwOnEnable: Boolean = false,
) : Telemetry {
    var enabled: Boolean = false
        private set

    override fun enable() {
        onEnable()
        if (throwOnEnable) throw IllegalStateException("simulated SDK init failure")
        enabled = true
    }

    override fun disable() {
        enabled = false
    }

    override fun track(event: TelemetryEvent) = Unit

    override fun debugSmokeTest(eventName: String): String = "recording"
}
