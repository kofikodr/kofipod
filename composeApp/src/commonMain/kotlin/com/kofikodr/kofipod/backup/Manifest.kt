// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Metadata bundled inside every SAF backup file. Lives next to the SQLDelight DB inside
 * the timestamped `.kpbak` zip (see [currentBackupFilename]). The schema-version field
 * is the load-bearing piece on
 * restore: a backup made by a newer build of the app may include tables/columns the
 * current build doesn't know how to read, so we refuse to restore in that direction.
 *
 * The other fields are forensic — useful for support ("when was this backup made? on what
 * version of the app?") but never used to make load-bearing decisions.
 *
 * Kept stable on the wire: every change to this shape bumps [MANIFEST_SCHEMA_VERSION] so
 * older builds that happen to inspect a newer manifest can fail loudly rather than silently.
 */
@Serializable
data class Manifest(
    @SerialName("schemaVersion") val schemaVersion: Int,
    @SerialName("appVersionCode") val appVersionCode: Int,
    @SerialName("appVersionName") val appVersionName: String,
    @SerialName("dbSchemaVersion") val dbSchemaVersion: Int,
    @SerialName("exportedAtMs") val exportedAtMs: Long,
    @SerialName("exportedAtIso") val exportedAtIso: String,
    @SerialName("dbSizeBytes") val dbSizeBytes: Long,
    @SerialName("dbSha256") val dbSha256: String,
) {
    fun toJsonString(): String = JSON.encodeToString(serializer(), this)

    companion object {
        // `prettyPrint` because the manifest is human-inspectable in the user's
        // chosen storage provider; `ignoreUnknownKeys` so a future build that adds
        // fields can still parse manifests written by older builds.
        val JSON: Json =
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        fun fromJsonStringOrNull(json: String): Manifest? = runCatching { JSON.decodeFromString(serializer(), json) }.getOrNull()
    }
}

/** Wire-format constants. Treat as load-bearing: changing any of them is a breaking change. */
const val MANIFEST_FILENAME = "manifest.json"
const val DB_FILENAME_IN_ZIP = "kofipod.db"

/**
 * Common prefix and suffix for every backup file written by this app. Active runs use
 * a timestamp between them (see [currentBackupFilename]); the legacy fixed-name file
 * [LEGACY_BACKUP_FILENAME] is still recognised by retention scans so a folder migrated
 * from an older build participates correctly.
 */
const val BACKUP_FILENAME_PREFIX = "kofipod-backup-"
const val BACKUP_FILENAME_SUFFIX = ".kpbak"

/** Filename written by builds before Slice 4 — kept only so retention recognises it. */
const val LEGACY_BACKUP_FILENAME = "kofipod-backup.kpbak"

/**
 * Number of `.kpbak` files retention keeps in the SAF folder after a successful write.
 * Older files (by modification time, falling back to filename timestamp parse) are
 * deleted. Manual and auto-backup writes both prune to this cap.
 */
const val BACKUP_RETENTION_KEEP = 5

/** Number of bytes in a YYYYMMDD-HHmmss stamp — `20260511-143012`. */
private const val BACKUP_FILENAME_STAMP_LENGTH = 15

/**
 * Build the filename for a new backup keyed to [exportedAtMs]. Format:
 * `kofipod-backup-YYYYMMDD-HHmmss.kpbak`, all in UTC so the lexicographic order of
 * filenames in the SAF folder matches chronological order regardless of where the
 * user reads the folder from. The exact millis are not encoded — at sub-second
 * resolution two runs would collide, but the controller's single-flight mutex makes
 * that path unreachable in practice.
 */
fun currentBackupFilename(exportedAtMs: Long): String {
    val seconds = exportedAtMs / 1_000L
    val daysSinceEpoch = seconds / 86_400L
    val secondsOfDay = ((seconds % 86_400L) + 86_400L) % 86_400L
    val (year, month, day) = civilFromDays(daysSinceEpoch)
    val hh = (secondsOfDay / 3600L).toInt()
    val mm = ((secondsOfDay / 60L) % 60L).toInt()
    val ss = (secondsOfDay % 60L).toInt()
    return BACKUP_FILENAME_PREFIX +
        year.toString().padStart(4, '0') +
        month.toString().padStart(2, '0') +
        day.toString().padStart(2, '0') +
        "-" +
        hh.toString().padStart(2, '0') +
        mm.toString().padStart(2, '0') +
        ss.toString().padStart(2, '0') +
        BACKUP_FILENAME_SUFFIX
}

/**
 * Parse the UTC timestamp out of a filename emitted by [currentBackupFilename]. Returns
 * null for filenames that don't match the shape (including [LEGACY_BACKUP_FILENAME]).
 * Used by retention sort as a fallback when the SAF provider doesn't expose a reliable
 * `lastModified` value.
 */
fun parseBackupFilenameTimestamp(name: String): Long? {
    if (!name.startsWith(BACKUP_FILENAME_PREFIX) || !name.endsWith(BACKUP_FILENAME_SUFFIX)) return null
    val stamp =
        name.substring(
            BACKUP_FILENAME_PREFIX.length,
            name.length - BACKUP_FILENAME_SUFFIX.length,
        )
    if (stamp.length != BACKUP_FILENAME_STAMP_LENGTH || stamp[8] != '-') return null
    val year = stamp.substring(0, 4).toIntOrNull() ?: return null
    val month = stamp.substring(4, 6).toIntOrNull() ?: return null
    val day = stamp.substring(6, 8).toIntOrNull() ?: return null
    val hh = stamp.substring(9, 11).toIntOrNull() ?: return null
    val mm = stamp.substring(11, 13).toIntOrNull() ?: return null
    val ss = stamp.substring(13, 15).toIntOrNull() ?: return null
    if (month !in 1..12 || hh !in 0..23 || mm !in 0..59 || ss !in 0..59) return null
    if (day !in 1..daysInMonth(year, month)) return null
    val days = daysFromCivil(year, month, day)
    return (days * 86_400L + hh * 3600L + mm * 60L + ss) * 1_000L
}

private fun daysInMonth(
    year: Int,
    month: Int,
): Int =
    when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeapYear(year)) 29 else 28
        else -> 0
    }

private fun isLeapYear(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

/**
 * Howard Hinnant's civil-from-days conversion. Public-domain algorithm; safer than
 * pulling kotlinx-datetime's full calendar surface into a hot path that runs on every
 * backup write. Returns (year, month, day) in the proleptic Gregorian calendar.
 */
private fun civilFromDays(daysSinceEpoch: Long): Triple<Int, Int, Int> {
    val z = daysSinceEpoch + 719_468L
    val era = if (z >= 0) z / 146_097L else (z - 146_096L) / 146_097L
    val doe = z - era * 146_097L
    val yoe = (doe - doe / 1460L + doe / 36_524L - doe / 146_096L) / 365L
    val y = yoe + era * 400L
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp = (5L * doy + 2L) / 153L
    val d = (doy - (153L * mp + 2L) / 5L + 1L).toInt()
    val m = (if (mp < 10L) mp + 3L else mp - 9L).toInt()
    val year = (if (m <= 2) y + 1L else y).toInt()
    return Triple(year, m, d)
}

private fun daysFromCivil(
    year: Int,
    month: Int,
    day: Int,
): Long {
    val y = if (month <= 2) year - 1 else year
    val era = if (y >= 0) y / 400 else (y - 399) / 400
    val yoe = (y - era * 400).toLong()
    val m = month.toLong()
    val doy = (153L * (if (m > 2) m - 3 else m + 9) + 2L) / 5L + day - 1L
    val doe = yoe * 365L + yoe / 4L - yoe / 100L + doy
    return era * 146_097L + doe - 719_468L
}

/**
 * Custom unregistered MIME. SAF's `MimeTypeMap` doesn't know it, so `CreateDocument`
 * will not append `.zip` (or anything else) to the suggested filename. Same trick the
 * OPML feature uses with `text/x-opml`. We can't use `application/zip` because then
 * SAF would suggest `kofipod-backup.kpbak.zip` on save.
 */
const val BACKUP_MIME = "application/x-kofipod-backup"

/** Manifest format version — bump on any breaking change to [Manifest]'s shape. */
const val MANIFEST_SCHEMA_VERSION = 1

/**
 * SQLDelight schema version, kept in sync with `KofipodDatabase.Schema.version` — that
 * is, `(highest numbered .sqm file) + 1`, because migration `N.sqm` migrates schema
 * version `N` → `N + 1`. Backups carry this number in the manifest so a restore can
 * refuse a schema newer than what the current build can open. Drift between this
 * constant and the generated schema version is pinned by
 * `ManifestTest.dbSchemaVersion_matchesGeneratedSchema` — if you add a new migration,
 * bump this constant and that test will pass again.
 */
const val DB_SCHEMA_VERSION = 22
