// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.backup

/**
 * Builds an in-memory zip archive entry-by-entry and returns the raw bytes. The backup
 * payload is small (DB is well under a few MB even for heavy users; one manifest is ~250
 * bytes) so streaming is not load-bearing.
 *
 * `expect` because we use `java.util.zip` on Android — that's outside detekt's allowed
 * commonMain imports. iOS doesn't surface the backup feature in v1; the iOS actual
 * throws.
 */
expect class ZipBuilder() {
    fun addEntry(
        name: String,
        bytes: ByteArray,
    )

    fun finish(): ByteArray
}

/**
 * Read every entry in [bytes] into a name → bytes map. Returns an empty map if [bytes]
 * isn't a valid zip. The backup format only ever contains two entries
 * ([MANIFEST_FILENAME] + [DB_FILENAME_IN_ZIP]), so reading the whole archive into memory
 * is fine.
 */
expect fun readZipEntries(bytes: ByteArray): Map<String, ByteArray>
