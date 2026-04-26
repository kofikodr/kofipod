// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.util

import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Local-day epoch counter. `kotlinx.datetime.LocalDate.toEpochDays()` returns days
 * since 1970-01-01 UTC for a `LocalDate` interpreted in the caller's chosen zone.
 * Stats bucket by local day so a session at 23:59 doesn't bleed into "tomorrow."
 */
fun todayEpochDay(tz: TimeZone = TimeZone.currentSystemDefault()): Int = Clock.System.now().toLocalDateTime(tz).date.toEpochDays()

fun epochDayToLocalDate(epochDay: Int): LocalDate = LocalDate.fromEpochDays(epochDay)
