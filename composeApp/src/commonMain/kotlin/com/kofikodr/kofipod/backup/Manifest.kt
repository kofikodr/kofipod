// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Metadata bundled inside every SAF backup file. Lives next to the SQLDelight DB inside
 * the [BACKUP_FILENAME] zip. The schema-version field is the load-bearing piece on
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
const val BACKUP_FILENAME = "kofipod-backup.kpbak"

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
 * SQLDelight schema version, mirrored from the highest numbered file under
 * `composeApp/src/commonMain/sqldelight/com/kofikodr/kofipod/db/migrations/`. Bump in lockstep
 * with every new `.sqm` file. Backups carry this number in the manifest so a restore
 * can refuse a schema newer than what the current build can open.
 */
const val DB_SCHEMA_VERSION = 18
