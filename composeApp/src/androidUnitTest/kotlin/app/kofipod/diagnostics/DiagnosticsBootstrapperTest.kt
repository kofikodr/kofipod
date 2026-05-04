// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsBootstrapperTest {

    @Test
    fun `crashes effective is false when toggle on but disclosure not acknowledged`() = runTest {
        val toggle = MutableStateFlow(true)
        val ack = MutableStateFlow(false)
        val effective = DiagnosticsBootstrapper.effective(toggle, ack)
        assertEquals(false, effective.first())
    }

    @Test
    fun `effective is false when disclosure acknowledged but toggle off`() = runTest {
        val toggle = MutableStateFlow(false)
        val ack = MutableStateFlow(true)
        val effective = DiagnosticsBootstrapper.effective(toggle, ack)
        assertEquals(false, effective.first())
    }

    @Test
    fun `effective is true only when both toggle and acknowledgement are true`() = runTest {
        val toggle = MutableStateFlow(true)
        val ack = MutableStateFlow(true)
        val effective = DiagnosticsBootstrapper.effective(toggle, ack)
        assertEquals(true, effective.first())
    }

    @Test
    fun `effective re-emits when acknowledgement flips`() = runTest {
        val toggle = MutableStateFlow(true)
        val ack = MutableStateFlow(false)
        val effective = DiagnosticsBootstrapper.effective(toggle, ack)
        val emissions = mutableListOf<Boolean>()
        emissions.add(effective.first())
        ack.value = true
        emissions.add(effective.first())
        assertEquals(listOf(false, true), emissions)
    }
}
