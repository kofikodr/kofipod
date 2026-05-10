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

    private fun readResource(path: String): String =
        ManifestTest::class.java.getResource(path)
            ?.readText()
            ?: error("test resource $path not found")
}
