// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.backup

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

// The first 16 bytes of every SQLite database file: "SQLite format 3" followed by a
// NUL terminator (hex 53 51 4c 69 74 65 20 66 6f 72 6d 61 74 20 33 00).
private val SQLITE_MAGIC = "SQLite format 3\u0000".encodeToByteArray()

// The fixed-size SQLite database header. We read all of it to reach the page-size
// (offset 16), file-change-counter (24), in-header page-count (28), and
// version-valid-for (92) fields used to detect a truncated file.
private const val SQLITE_HEADER_SIZE = 100

/**
 * Stage [bytes] at `[dir]/[filename]` crash-safely: write to a sibling `.new` file,
 * flush it to disk, then atomically rename it over the target. `rename(2)` within a
 * single filesystem (here, `filesDir`) is atomic, so the staged file is always either
 * absent or fully written — a process kill mid-write can never leave a partial
 * `restore.tmp` that a later cold start would copy over the live DB (issue #17).
 */
internal fun stageRestoreAtomically(
    dir: File,
    filename: String,
    bytes: ByteArray,
) {
    val target = File(dir, filename)
    val tmp = File(dir, "$filename.new")
    tmp.delete() // drop any leftover from a previously interrupted stage
    FileOutputStream(tmp).use { out ->
        out.write(bytes)
        // Flush to disk before the rename — otherwise a crash after the rename but
        // before the page cache is flushed could expose a file with unwritten bytes.
        out.fd.sync()
    }
    if (!tmp.renameTo(target)) {
        tmp.delete()
        error("Couldn't stage restore file")
    }
}

/**
 * True iff [file] is a structurally complete SQLite database. Used to refuse a
 * truncated/partial staged payload before it overwrites the live DB. Validates the
 * magic header and, when the in-header page count is trustworthy, that the file holds
 * at least `pageSize * pageCount` bytes — a file truncated by an interrupted write
 * falls short. Pure byte inspection so it stays unit-testable without an Android
 * SQLite runtime, and faster than opening the database.
 */
internal fun isCompleteSqliteFile(file: File): Boolean {
    if (!file.exists() || file.length() < SQLITE_HEADER_SIZE) return false
    val header = ByteArray(SQLITE_HEADER_SIZE)
    FileInputStream(file).use { input ->
        var off = 0
        while (off < SQLITE_HEADER_SIZE) {
            val read = input.read(header, off, SQLITE_HEADER_SIZE - off)
            if (read < 0) return false
            off += read
        }
    }
    for (i in SQLITE_MAGIC.indices) if (header[i] != SQLITE_MAGIC[i]) return false

    val pageSize = readPageSize(header) ?: return false
    val pageCount = readUInt32(header, 28)
    val changeCounter = readUInt32(header, 24)
    val versionValidFor = readUInt32(header, 92)
    // The in-header page count (offset 28) is authoritative only when the file change
    // counter (offset 24) equals the version-valid-for number (offset 92). Our backups
    // are read from a checkpointed DB, so this holds and lets us catch truncation
    // exactly. If it doesn't, fall back to requiring at least one full page.
    return if (pageCount > 0L && changeCounter == versionValidFor) {
        file.length() >= pageSize * pageCount
    } else {
        file.length() >= pageSize
    }
}

/** Reads the page size at header offset 16; a stored value of 1 means 65536 bytes. */
private fun readPageSize(header: ByteArray): Long? {
    val raw = ((header[16].toInt() and 0xFF) shl 8) or (header[17].toInt() and 0xFF)
    val size = if (raw == 1) 65_536 else raw
    val isPowerOfTwo = size != 0 && (size and (size - 1)) == 0
    return if (size in 512..65_536 && isPowerOfTwo) size.toLong() else null
}

/** Reads a big-endian unsigned 32-bit integer at [offset]. */
private fun readUInt32(
    b: ByteArray,
    offset: Int,
): Long =
    ((b[offset].toLong() and 0xFF) shl 24) or
        ((b[offset + 1].toLong() and 0xFF) shl 16) or
        ((b[offset + 2].toLong() and 0xFF) shl 8) or
        (b[offset + 3].toLong() and 0xFF)
