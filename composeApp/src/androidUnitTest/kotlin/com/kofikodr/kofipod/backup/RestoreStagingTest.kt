// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.backup

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the issue #17 fix: staging a restore payload must be atomic (no partial
 * `restore.tmp` can survive a kill) and a structurally incomplete staged file must be
 * refused before it ever overwrites the live DB.
 */
class RestoreStagingTest {
    private val dir: File = Files.createTempDirectory("kofipod-stage").toFile()

    // ── stageRestoreAtomically ────────────────────────────────────────────────

    // Note: the atomicity guarantee itself (no partial restore.tmp survives a mid-write
    // kill) rests on the OS rename(2) contract and can't be observed at the JVM unit-test
    // layer — there's no post-rename intermediate state to assert. These tests verify the
    // observable outcomes (complete content, no leftover temp); the atomicity is by design.
    @Test
    fun stage_writesCompleteFile_andLeavesNoTempBehind() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        stageRestoreAtomically(dir, "restore.tmp", bytes)

        val target = File(dir, "restore.tmp")
        assertTrue(target.exists())
        assertContentEquals(bytes, target.readBytes())
        assertFalse(File(dir, "restore.tmp.new").exists(), "the .new temp must be renamed away, not left behind")
    }

    @Test
    fun stage_overwritesLeftoverTempFromAnInterruptedStage() {
        // Simulate a previous run that died after creating the temp but before rename.
        File(dir, "restore.tmp.new").writeBytes(byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9))
        val bytes = byteArrayOf(7, 7, 7)

        stageRestoreAtomically(dir, "restore.tmp", bytes)

        assertContentEquals(bytes, File(dir, "restore.tmp").readBytes())
        assertFalse(File(dir, "restore.tmp.new").exists())
    }

    @Test
    fun stage_replacesAnExistingTarget() {
        File(dir, "restore.tmp").writeBytes(byteArrayOf(0, 0, 0, 0))
        val bytes = byteArrayOf(42, 43)

        stageRestoreAtomically(dir, "restore.tmp", bytes)

        assertContentEquals(bytes, File(dir, "restore.tmp").readBytes())
    }

    // ── isCompleteSqliteFile ──────────────────────────────────────────────────

    @Test
    fun completeness_acceptsARealSqliteDatabase() {
        val db = File(dir, "real.db")
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)")
                st.execute("INSERT INTO t(v) VALUES('hello')")
            }
        }
        assertTrue(db.length() > 0)
        assertTrue(isCompleteSqliteFile(db), "a well-formed SQLite file must be accepted")
    }

    @Test
    fun completeness_rejectsATruncatedSqliteDatabase() {
        val db = File(dir, "real2.db")
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)")
                st.execute("INSERT INTO t(v) VALUES('hello')")
            }
        }
        // Truncate to the header only — a partial write that keeps the magic but drops
        // the declared pages. Must be refused.
        val truncated = File(dir, "truncated.db")
        truncated.writeBytes(db.readBytes().copyOf(100))
        assertFalse(isCompleteSqliteFile(truncated), "a truncated DB must be refused before overwriting the live DB")
    }

    @Test
    fun completeness_rejectsWhenHeaderClaimsMorePagesThanTheFileHolds() {
        // Magic + pageSize 4096 + changeCounter 1 + pageCount 4 + versionValidFor 1,
        // but only one page of bytes present → truncated.
        val header = validHeader(pageSize = 4096, pageCount = 4, changeCounter = 1, versionValidFor = 1)
        val file = File(dir, "claims4pages.db")
        file.writeBytes(header + ByteArray(4096 - header.size)) // total 4096 < 4096*4
        assertFalse(isCompleteSqliteFile(file))
    }

    @Test
    fun completeness_acceptsWhenFileMeetsTheDeclaredPageCount() {
        val header = validHeader(pageSize = 4096, pageCount = 2, changeCounter = 1, versionValidFor = 1)
        val file = File(dir, "twopages.db")
        file.writeBytes(header + ByteArray(4096 * 2 - header.size)) // total 8192 == 4096*2
        assertTrue(isCompleteSqliteFile(file))
    }

    @Test
    fun completeness_whenCountersDisagree_fallsBackToOneFullPage() {
        // changeCounter != versionValidFor → the in-header page count isn't trustworthy,
        // so we only require at least one full page rather than pageSize*pageCount.
        val header = validHeader(pageSize = 4096, pageCount = 4, changeCounter = 1, versionValidFor = 2)
        val file = File(dir, "counters-disagree.db")
        file.writeBytes(header + ByteArray(4096 - header.size)) // total 4096 == one page
        assertTrue(isCompleteSqliteFile(file), "with untrusted page count, one full page is accepted")
    }

    @Test
    fun completeness_rejectsAnInvalidPageSize() {
        // raw page-size 257 is not a power of two → not a valid SQLite header.
        val header = validHeader(pageSize = 257, pageCount = 1, changeCounter = 1, versionValidFor = 1)
        val file = File(dir, "badpagesize.db")
        file.writeBytes(header + ByteArray(4096))
        assertFalse(isCompleteSqliteFile(file))
    }

    @Test
    fun completeness_acceptsThe65536PageSizeEncoding() {
        // A stored page-size value of 1 means 65536 bytes per the SQLite spec.
        val header = validHeader(pageSize = 1, pageCount = 1, changeCounter = 1, versionValidFor = 1)
        val file = File(dir, "bigpage.db")
        file.writeBytes(header + ByteArray(65_536 - header.size)) // total 65536 == one 64KiB page
        assertTrue(isCompleteSqliteFile(file))
    }

    @Test
    fun completeness_rejectsNonSqliteBytes() {
        val file = File(dir, "garbage.bin")
        file.writeBytes(ByteArray(200) { (it and 0xFF).toByte() }) // no SQLite magic
        assertFalse(isCompleteSqliteFile(file))
    }

    @Test
    fun completeness_rejectsAFileShorterThanTheHeader() {
        val file = File(dir, "tiny.bin")
        file.writeBytes("SQLite format 3\u0000".encodeToByteArray()) // 16 bytes, < 100
        assertFalse(isCompleteSqliteFile(file))
    }

    @Test
    fun completeness_rejectsAMissingFile() {
        assertFalse(isCompleteSqliteFile(File(dir, "does-not-exist.db")))
    }

    /** Builds a 100-byte SQLite header with the fields our validator inspects. */
    private fun validHeader(
        pageSize: Int,
        pageCount: Long,
        changeCounter: Long,
        versionValidFor: Long,
    ): ByteArray {
        val h = ByteArray(100)
        val magic = "SQLite format 3\u0000".encodeToByteArray()
        magic.copyInto(h)
        // page size at offset 16 (big-endian u16)
        h[16] = ((pageSize ushr 8) and 0xFF).toByte()
        h[17] = (pageSize and 0xFF).toByte()
        putUInt32(h, 24, changeCounter)
        putUInt32(h, 28, pageCount)
        putUInt32(h, 92, versionValidFor)
        return h
    }

    private fun putUInt32(
        b: ByteArray,
        offset: Int,
        value: Long,
    ) {
        b[offset] = ((value ushr 24) and 0xFF).toByte()
        b[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        b[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        b[offset + 3] = (value and 0xFF).toByte()
    }

    @Test
    fun stage_thenValidate_endToEnd() {
        // A staged real DB must round-trip as complete; a staged truncated DB must not.
        val db = File(dir, "src.db")
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { conn ->
            conn.createStatement().use { it.execute("CREATE TABLE t(id INTEGER)") }
        }
        stageRestoreAtomically(dir, "staged.tmp", db.readBytes())
        assertTrue(isCompleteSqliteFile(File(dir, "staged.tmp")))

        stageRestoreAtomically(dir, "staged2.tmp", db.readBytes().copyOf(100))
        assertFalse(isCompleteSqliteFile(File(dir, "staged2.tmp")))
    }
}
