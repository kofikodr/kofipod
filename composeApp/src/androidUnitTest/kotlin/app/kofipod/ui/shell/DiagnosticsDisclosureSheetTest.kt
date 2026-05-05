// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsDisclosureSheetTest {
    @Test
    fun `body mentions both channels and EU hosting when both are available`() {
        val body = disclosureBody(crashAvailable = true, usageAvailable = true)
        assertTrue("expected crash mention", body.contains("crash reports"))
        assertTrue("expected usage mention", body.contains("usage counts"))
        assertTrue("expected EU hosting note", body.contains("hosted in the EU", ignoreCase = true))
        assertTrue("expected 'either' phrasing", body.contains("either"))
    }

    @Test
    fun `body mentions only crashes when usage is not available`() {
        val body = disclosureBody(crashAvailable = true, usageAvailable = false)
        assertTrue("expected crash mention", body.contains("crash reports"))
        assertFalse("did not expect usage mention", body.contains("usage counts"))
        assertFalse("did not expect EU note when usage off", body.contains("EU"))
    }

    @Test
    fun `body mentions only usage and EU hosting when crashes are not available`() {
        val body = disclosureBody(crashAvailable = false, usageAvailable = true)
        assertFalse("did not expect crash mention", body.contains("crash reports"))
        assertTrue("expected usage mention", body.contains("usage counts"))
        assertTrue("expected EU hosting note", body.contains("hosted in the EU", ignoreCase = true))
    }

    @Test
    fun `body throws when neither channel is available`() {
        assertThrows(IllegalArgumentException::class.java) {
            disclosureBody(crashAvailable = false, usageAvailable = false)
        }
    }
}
