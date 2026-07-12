// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.background

import com.kofikodr.kofipod.data.repo.SettingsRepository
import com.kofikodr.kofipod.testing.inMemoryDatabase
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DailyCheckCoordinatorTest {
    private class Harness {
        val settings = SettingsRepository(inMemoryDatabase())
        var enableCalls = 0
        var disableCalls = 0
        val coordinator =
            DailyCheckCoordinator(
                settings = settings,
                enableScheduler = { enableCalls++ },
                disableScheduler = { disableCalls++ },
            )
    }

    @Test
    fun reassertEnabled_restoresSettingAndReschedules_whenDailyCheckWasTurnedOff() {
        // On-device repro (2026-07-05): daily check OFF cancels the periodic work and
        // nothing ever re-schedules it, so a later per-podcast auto-download/notify
        // enable silently never fires. Enabling a dependent feature must re-arm the
        // prerequisite — and report the flip so the UI can tell the user.
        val h = Harness()
        h.settings.setDailyCheckEnabled(false)

        val flipped = h.coordinator.reassertEnabled()

        assertTrue(flipped, "caller must learn the global setting was flipped so it can surface it")
        assertTrue(h.settings.dailyCheckEnabledNow(), "the daily-check setting must be switched back on")
        assertEquals(1, h.enableCalls, "the periodic work must be (re-)scheduled")
    }

    @Test
    fun reassertEnabled_reschedulesIdempotently_whenDailyCheckAlreadyOn() {
        val h = Harness()

        assertFalse(h.coordinator.reassertEnabled(), "no flip to report when the setting was already on")
        assertFalse(h.coordinator.reassertEnabled())

        assertTrue(h.settings.dailyCheckEnabledNow(), "an already-on setting must stay on")
        assertEquals(2, h.enableCalls, "re-asserting is safe to repeat — scheduling is idempotent (UPDATE policy)")
        assertEquals(0, h.disableCalls)
    }

    @Test
    fun setEnabled_togglesSettingAndScheduling_asTheSingleCouplingHome() {
        // Settings + Scheduler-Details toggles both route through here so the
        // "setting + periodic work" coupling has exactly one implementation.
        val h = Harness()

        h.coordinator.setEnabled(false)
        assertFalse(h.settings.dailyCheckEnabledNow())
        assertEquals(1, h.disableCalls, "turning off must cancel the periodic work")

        h.coordinator.setEnabled(true)
        assertTrue(h.settings.dailyCheckEnabledNow())
        assertEquals(1, h.enableCalls, "turning on must schedule the periodic work")
    }
}
