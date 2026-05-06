// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

import kotlin.test.Test
import kotlin.test.assertEquals

class TimestampFormatterTest {
    @Test fun zeroIsDoubleZero() = assertEquals("00:00", formatHms(0))

    @Test fun underAMinute() = assertEquals("00:42", formatHms(42_000))

    @Test fun underAnHour() = assertEquals("12:34", formatHms((12 * 60 + 34) * 1_000L))

    @Test fun overAnHour() = assertEquals("1:02:03", formatHms(((1 * 3600) + (2 * 60) + 3) * 1_000L))

    @Test fun roundsDown() = assertEquals("00:01", formatHms(1_999))
}
