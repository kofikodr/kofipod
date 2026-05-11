// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the on-wire shape of the backup manifest. The fixture
 * (`androidUnitTest/resources/backup/sample_manifest.json`) is the canary — if anyone
 * changes the JSON field names or types in [Manifest], this test fails until the
 * fixture is updated in lockstep, which forces a conscious decision about backwards
 * compatibility (does this change the wire format? do older builds need to handle it?).
 */
class ManifestTest {
    @Test
    fun fixtureParses_intoExpectedFields() {
        val json = readResource("/backup/sample_manifest.json")
        val manifest = assertNotNull(Manifest.fromJsonStringOrNull(json))

        assertEquals(1, manifest.schemaVersion)
        assertEquals(7, manifest.appVersionCode)
        assertEquals("0.7.0", manifest.appVersionName)
        assertEquals(15, manifest.dbSchemaVersion)
        assertEquals(1746400000000L, manifest.exportedAtMs)
        assertEquals("2026-05-05T12:00:00Z", manifest.exportedAtIso)
        assertEquals(524288L, manifest.dbSizeBytes)
        assertEquals(
            "abc123def456abc123def456abc123def456abc123def456abc123def456abcd",
            manifest.dbSha256,
        )
    }

    @Test
    fun roundTripsThroughEncodeDecode() {
        val original =
            Manifest(
                schemaVersion = 1,
                appVersionCode = 7,
                appVersionName = "0.7.0",
                dbSchemaVersion = 15,
                exportedAtMs = 1_746_400_000_000L,
                exportedAtIso = "2026-05-05T12:00:00Z",
                dbSizeBytes = 524_288L,
                dbSha256 = "deadbeef",
            )

        val rehydrated = Manifest.fromJsonStringOrNull(original.toJsonString())

        assertEquals(original, rehydrated)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val withExtra =
            """
            {
              "schemaVersion": 1,
              "appVersionCode": 7,
              "appVersionName": "0.7.0",
              "dbSchemaVersion": 15,
              "exportedAtMs": 1,
              "exportedAtIso": "2026-05-05T12:00:00Z",
              "dbSizeBytes": 1,
              "dbSha256": "x",
              "futureField": "should not break old builds"
            }
            """.trimIndent()

        assertNotNull(Manifest.fromJsonStringOrNull(withExtra))
    }

    @Test
    fun returnsNullOnMalformedJson() {
        assertNull(Manifest.fromJsonStringOrNull("not json"))
        assertNull(Manifest.fromJsonStringOrNull("{}")) // missing required fields
    }

    @Test
    fun currentBackupFilename_isReversibleByParser() {
        // 2026-05-11T14:30:12Z — picked because it crosses both a 30 (May) and 31
        // (April) month, plus a non-leap-year boundary, so the civil-from-days math
        // gets a meaningful workout.
        val epochMs = 1_778_509_812_000L
        val name = currentBackupFilename(epochMs)
        assertEquals("kofipod-backup-20260511-143012.kpbak", name)
        assertEquals(epochMs, parseBackupFilenameTimestamp(name))
    }

    @Test
    fun currentBackupFilename_handlesLeapDay() {
        // 2024-02-29T00:00:00Z — picked specifically because the civil-from-days
        // round-trip is the easiest place to break for a leap day.
        val epochMs = 1_709_164_800_000L
        val name = currentBackupFilename(epochMs)
        assertEquals("kofipod-backup-20240229-000000.kpbak", name)
        assertEquals(epochMs, parseBackupFilenameTimestamp(name))
    }

    @Test
    fun parseBackupFilenameTimestamp_rejectsLegacyAndUnknown() {
        // Legacy file is recognised as a backup by the list filter but does NOT yield a
        // parseable timestamp — sort fallback must surface it as the oldest entry.
        assertNull(parseBackupFilenameTimestamp(LEGACY_BACKUP_FILENAME))
        assertNull(parseBackupFilenameTimestamp("kofipod-backup-not-a-date.kpbak"))
        assertNull(parseBackupFilenameTimestamp("some-other-file.kpbak"))
        assertNull(parseBackupFilenameTimestamp("kofipod-backup-20261301-000000.kpbak")) // month 13
        // Calendar-accurate day range: April has 30 days, so "April 31" must be rejected,
        // not silently rolled into May 1 (which would otherwise come out of
        // daysFromCivil's arithmetic and produce a wrong timestamp).
        assertNull(parseBackupFilenameTimestamp("kofipod-backup-20260431-000000.kpbak"))
        // Leap-day correctness: 2026 is not a leap year, so Feb 29 2026 must be rejected.
        assertNull(parseBackupFilenameTimestamp("kofipod-backup-20260229-000000.kpbak"))
        // Sanity: hour/min/sec out of range still rejected.
        assertNull(parseBackupFilenameTimestamp("kofipod-backup-20260511-250000.kpbak"))
    }

    private fun readResource(path: String): String =
        ManifestTest::class.java.getResource(path)
            ?.readText()
            ?: error("test resource $path not found")
}
