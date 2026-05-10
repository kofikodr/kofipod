// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.backup

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Exercises [BackupRepository]'s validation gates against zip payloads. We're verifying
 * the contract — what counts as a valid backup vs. each named failure mode — not the
 * underlying zip / sha256 plumbing (which is target-platform stdlib and tested by JDK
 * itself).
 *
 * No mocks of the system under test's own methods. The repo's only collaborators are
 * the [DbFileBytes] / [StageDbFile] lambdas and the JVM stdlib zip / digest, which we
 * exercise via real [ZipBuilder] / [readZipEntries] / [sha256] calls (the JVM actuals).
 */
class BackupRepositoryTest {
    private val fixedClock =
        object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        }

    private val sampleDb = "PRETEND I AM A SQLITE DB FILE".encodeToByteArray()
    private var staged: ByteArray? = null

    private fun repo(
        dbBytes: ByteArray = sampleDb,
        currentSchema: Int = 15,
    ) = BackupRepository(
        dbFileBytes = { dbBytes },
        stageDb = { staged = it },
        appVersionCode = 7,
        appVersionName = "0.7.0",
        dbSchemaVersion = currentSchema,
        clock = fixedClock,
    )

    @Test
    fun buildBackup_producesZipWithManifestAndDbEntries() {
        val zip = repo().buildBackup()

        val entries = readZipEntries(zip)
        assertEquals(setOf(MANIFEST_FILENAME, DB_FILENAME_IN_ZIP), entries.keys)
        assertTrue(entries[DB_FILENAME_IN_ZIP].contentEquals(sampleDb))
    }

    @Test
    fun buildBackup_manifestCarriesCurrentSchemaAndExpectedFields() {
        val zip = repo().buildBackup()

        val manifest =
            Manifest.fromJsonStringOrNull(
                readZipEntries(zip).getValue(MANIFEST_FILENAME).decodeToString(),
            )
        assertNotNull(manifest)
        assertEquals(MANIFEST_SCHEMA_VERSION, manifest.schemaVersion)
        assertEquals(7, manifest.appVersionCode)
        assertEquals("0.7.0", manifest.appVersionName)
        assertEquals(15, manifest.dbSchemaVersion)
        assertEquals(sampleDb.size.toLong(), manifest.dbSizeBytes)
        assertEquals(sha256(sampleDb), manifest.dbSha256)
        assertEquals(1_700_000_000_000L, manifest.exportedAtMs)
    }

    @Test
    fun validateBackup_acceptsRoundTrip() {
        val r = repo()
        val zip = r.buildBackup()

        val result = r.validateBackup(zip)

        val valid = result as? RestoreValidation.Valid ?: fail("expected Valid, got $result")
        assertTrue(valid.dbBytes.contentEquals(sampleDb))
    }

    @Test
    fun validateBackup_rejectsZipWithoutManifest() {
        val builder = ZipBuilder()
        builder.addEntry(DB_FILENAME_IN_ZIP, sampleDb)
        val zip = builder.finish()

        val result = repo().validateBackup(zip)

        assertEquals(RestoreValidation.Invalid(RestoreError.MissingEntry), result)
    }

    @Test
    fun validateBackup_rejectsZipWithoutDb() {
        val r = repo()
        val builder = ZipBuilder()
        // Manifest must be syntactically valid for the missing-db path to be the reason
        // surfaced — otherwise we'd hit BadManifest first.
        val manifest =
            Manifest(
                schemaVersion = 1,
                appVersionCode = 1,
                appVersionName = "x",
                dbSchemaVersion = 15,
                exportedAtMs = 1,
                exportedAtIso = "x",
                dbSizeBytes = 0,
                dbSha256 = sha256(ByteArray(0)),
            )
        builder.addEntry(MANIFEST_FILENAME, manifest.toJsonString().encodeToByteArray())
        val zip = builder.finish()

        val result = r.validateBackup(zip)

        assertEquals(RestoreValidation.Invalid(RestoreError.MissingEntry), result)
    }

    @Test
    fun validateBackup_rejectsBadJsonManifest() {
        val builder = ZipBuilder()
        builder.addEntry(MANIFEST_FILENAME, "not json".encodeToByteArray())
        builder.addEntry(DB_FILENAME_IN_ZIP, sampleDb)
        val zip = builder.finish()

        val result = repo().validateBackup(zip)

        assertEquals(RestoreValidation.Invalid(RestoreError.BadManifest), result)
    }

    @Test
    fun validateBackup_rejectsSchemaTooNew() {
        // Build with a fictional newer schema version, then validate against current 15.
        val futureRepo = repo(currentSchema = 99)
        val zip = futureRepo.buildBackup()

        val result = repo(currentSchema = 15).validateBackup(zip)

        val invalid = result as? RestoreValidation.Invalid ?: fail("expected Invalid, got $result")
        val err = invalid.error as? RestoreError.SchemaTooNew ?: fail("expected SchemaTooNew, got ${invalid.error}")
        assertEquals(99, err.found)
        assertEquals(15, err.current)
    }

    @Test
    fun validateBackup_acceptsOlderSchemaVersion() {
        // Pins the `<=` boundary in `validateBackup`. A backup made by an older build
        // (schema 14) must still be restorable by a newer build (schema 15) — SQLDelight
        // applies migrations to bring the restored DB forward. Without this test, a
        // regression to `>=` (rejecting equal versions) or `<` (rejecting all older
        // versions) would slip past validateBackup_acceptsRoundTrip, since that test
        // uses the same schema on both sides.
        val olderRepo = repo(currentSchema = 14)
        val zip = olderRepo.buildBackup()

        val result = repo(currentSchema = 15).validateBackup(zip)

        val valid = result as? RestoreValidation.Valid ?: fail("expected Valid, got $result")
        assertEquals(14, valid.manifest.dbSchemaVersion)
    }

    @Test
    fun validateBackup_rejectsSha256Mismatch() {
        val r = repo()
        val zip = r.buildBackup()

        // Surgically rewrite the DB entry so its bytes no longer hash to what the
        // manifest claims. We rebuild the zip with the original manifest + a corrupted
        // db payload of the same length so we can be sure we're hitting the sha branch
        // and not the size/structure branch.
        val entries = readZipEntries(zip).toMutableMap()
        val corrupted = entries.getValue(DB_FILENAME_IN_ZIP).copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNotEquals(sha256(corrupted), sha256(entries.getValue(DB_FILENAME_IN_ZIP)))
        val rebuilt = ZipBuilder()
        rebuilt.addEntry(MANIFEST_FILENAME, entries.getValue(MANIFEST_FILENAME))
        rebuilt.addEntry(DB_FILENAME_IN_ZIP, corrupted)
        val tampered = rebuilt.finish()

        val result = r.validateBackup(tampered)

        assertEquals(RestoreValidation.Invalid(RestoreError.Sha256Mismatch), result)
    }

    @Test
    fun validateBackup_rejectsNonZipBytes() {
        val result = repo().validateBackup("PDF or image or random bytes".encodeToByteArray())

        assertEquals(RestoreValidation.Invalid(RestoreError.ZipUnreadable), result)
    }

    @Test
    fun stageRestore_handsBytesToInjectedLambda() {
        assertNull(staged)
        repo().stageRestore("staged-payload".encodeToByteArray())
        assertTrue("staged-payload".encodeToByteArray().contentEquals(staged))
    }

    @Test
    fun restoreError_messages_areUserFacing_andSpecCompliant() {
        // Pin the spec-mandated wording for each error branch. Not the full sentence —
        // that's brittle — but the load-bearing keywords. Catches accidental regressions
        // to generic copy ("Error" / "Failed" / "Invalid backup") and confirms the
        // SchemaTooNew path tells the user what to do (update the app).
        assertTrue(
            RestoreError.ZipUnreadable.toUserMessage().isNotBlank(),
            "ZipUnreadable message is empty",
        )
        assertTrue(
            RestoreError.MissingEntry.toUserMessage().contains("Kofipod backup", ignoreCase = true),
            "MissingEntry message must identify it as not a Kofipod backup",
        )
        assertTrue(
            RestoreError.BadManifest.toUserMessage().contains("Kofipod backup", ignoreCase = true),
            "BadManifest message must identify it as not a Kofipod backup",
        )
        val schemaMsg = RestoreError.SchemaTooNew(found = 99, current = 15).toUserMessage()
        assertTrue(
            schemaMsg.contains("newer", ignoreCase = true),
            "SchemaTooNew message must explain the version is newer: '$schemaMsg'",
        )
        assertTrue(
            RestoreError.Sha256Mismatch.toUserMessage().contains("corrupted", ignoreCase = true),
            "Sha256Mismatch message must tell the user the file is corrupted",
        )
    }
}
