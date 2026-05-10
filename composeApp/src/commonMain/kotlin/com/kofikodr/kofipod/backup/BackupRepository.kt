// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.backup

import kotlinx.datetime.Clock

/**
 * Pure-logic core of the SAF backup feature: builds the backup payload and validates
 * incoming restore payloads. Does no SAF work itself — all IO with the user's chosen
 * folder lives in [BackupFilePort]; reading the live DB file lives behind the
 * [DbFileBytes] / [StageDbFile] seams.
 *
 * Kept side-effect-free except via injected lambdas so it's exhaustively unit-testable
 * on the JVM without standing up a real database driver or SAF launcher.
 */
class BackupRepository(
    private val dbFileBytes: DbFileBytes,
    private val stageDb: StageDbFile,
    private val appVersionCode: Int,
    private val appVersionName: String,
    private val dbSchemaVersion: Int = DB_SCHEMA_VERSION,
    private val clock: Clock = Clock.System,
) {
    /**
     * Snapshot the live DB, hash it, build a manifest, return a zip of both.
     * Called by [BackupController.runBackup] (manual + scheduled).
     */
    fun buildBackup(): ByteArray {
        val dbBytes = dbFileBytes.read()
        val sha = sha256(dbBytes)
        val now = clock.now()
        val manifest =
            Manifest(
                schemaVersion = MANIFEST_SCHEMA_VERSION,
                appVersionCode = appVersionCode,
                appVersionName = appVersionName,
                dbSchemaVersion = dbSchemaVersion,
                exportedAtMs = now.toEpochMilliseconds(),
                exportedAtIso = now.toString(),
                dbSizeBytes = dbBytes.size.toLong(),
                dbSha256 = sha,
            )
        val builder = ZipBuilder()
        builder.addEntry(MANIFEST_FILENAME, manifest.toJsonString().encodeToByteArray())
        builder.addEntry(DB_FILENAME_IN_ZIP, dbBytes)
        return builder.finish()
    }

    /**
     * Inspect [zipBytes] and return either [RestoreValidation.Valid] (with the DB bytes
     * to stage) or [RestoreValidation.Invalid] with a typed reason. The order matches
     * the spec — we surface the most specific reason first so the user gets actionable
     * feedback. Pure: no IO, no destructive action.
     */
    fun validateBackup(zipBytes: ByteArray): RestoreValidation {
        val entries = readZipEntries(zipBytes)
        if (entries.isEmpty()) return RestoreValidation.Invalid(RestoreError.ZipUnreadable)

        val manifestBytes =
            entries[MANIFEST_FILENAME]
                ?: return RestoreValidation.Invalid(RestoreError.MissingEntry)
        val dbBytes =
            entries[DB_FILENAME_IN_ZIP]
                ?: return RestoreValidation.Invalid(RestoreError.MissingEntry)

        val manifest =
            Manifest.fromJsonStringOrNull(manifestBytes.decodeToString())
                ?: return RestoreValidation.Invalid(RestoreError.BadManifest)

        if (manifest.dbSchemaVersion > dbSchemaVersion) {
            return RestoreValidation.Invalid(
                RestoreError.SchemaTooNew(found = manifest.dbSchemaVersion, current = dbSchemaVersion),
            )
        }

        val computedSha = sha256(dbBytes)
        if (computedSha != manifest.dbSha256) {
            return RestoreValidation.Invalid(RestoreError.Sha256Mismatch)
        }

        return RestoreValidation.Valid(dbBytes = dbBytes, manifest = manifest)
    }

    /** Hands [dbBytes] to the platform staging seam so [PendingRestore] can pick them up. */
    fun stageRestore(dbBytes: ByteArray) {
        stageDb.write(dbBytes)
    }
}

/**
 * Why a sealed result instead of throwing: the controller maps each branch to a specific
 * user-facing string. Throwing would either lose the discrimination (one catch, one
 * generic message) or require an exception per case (verbose for what's really a tag).
 */
sealed interface RestoreValidation {
    data class Valid(val dbBytes: ByteArray, val manifest: Manifest) : RestoreValidation {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Valid) return false
            return dbBytes.contentEquals(other.dbBytes) && manifest == other.manifest
        }

        override fun hashCode(): Int = 31 * dbBytes.contentHashCode() + manifest.hashCode()
    }

    data class Invalid(val error: RestoreError) : RestoreValidation
}

sealed interface RestoreError {
    fun toUserMessage(): String

    data object ZipUnreadable : RestoreError {
        override fun toUserMessage() = "Couldn't read backup file"
    }

    data object MissingEntry : RestoreError {
        override fun toUserMessage() = "This doesn't look like a Kofipod backup"
    }

    data object BadManifest : RestoreError {
        override fun toUserMessage() = "This doesn't look like a Kofipod backup"
    }

    data class SchemaTooNew(val found: Int, val current: Int) : RestoreError {
        override fun toUserMessage() = "This backup was made with a newer version of Kofipod. Update the app and try again."
    }

    data object Sha256Mismatch : RestoreError {
        override fun toUserMessage() = "Backup file appears corrupted"
    }
}
