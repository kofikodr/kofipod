// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm

import kotlin.test.Test
import kotlin.test.assertEquals

class TimestampFormatterTest {
    @Test fun zeroIsDoubleZero() = assertEquals("00:00", formatHms(0))

    @Test fun underAMinute() = assertEquals("00:42", formatHms(42_000))

    @Test fun underAnHour() = assertEquals("12:34", formatHms((12 * 60 + 34) * 1_000L))

    @Test fun overAnHour() = assertEquals("1:02:03", formatHms(((1 * 3600) + (2 * 60) + 3) * 1_000L))

    @Test fun roundsDown() = assertEquals("00:01", formatHms(1_999))

    @Test fun negativeIsClampedToZero() = assertEquals("00:00", formatHms(-1_000L))

    @Test
    fun tenHoursDoesNotPadHoursField() =
        // Exotic but plausible (audio books occasionally appear in podcast feeds).
        // Hours field is intentionally unpadded — `H:MM:SS`, not `HH:MM:SS`.
        assertEquals("10:00:00", formatHms(10L * 3600L * 1_000L))
}
