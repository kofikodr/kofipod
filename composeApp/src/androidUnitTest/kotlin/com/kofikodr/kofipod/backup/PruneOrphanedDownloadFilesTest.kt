// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.backup

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behaviour of [pruneOrphanedDownloadFiles] — the post-restore cleanup that reclaims
 * audio files left orphaned when a restore wipes every `Download` row (issue #18).
 */
class PruneOrphanedDownloadFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun deletesUnreferencedFilesAndKeepsReferencedOnes() {
        val dir = tmp.newFolder("downloads")
        val keep = File(dir, "keep.mp3").apply { writeText("keep") }
        val orphan = File(dir, "orphan.mp3").apply { writeText("orphan") }

        val deleted = pruneOrphanedDownloadFiles(dir, setOf(keep.absolutePath))

        assertEquals(1, deleted)
        assertTrue(keep.exists(), "a file referenced by a completed Download row must survive")
        assertFalse(orphan.exists(), "an unreferenced file must be pruned")
    }

    @Test
    fun deletesEveryFileWhenNothingIsReferenced() {
        // The restore case: scrubTransientState wiped all Download rows, so the
        // referenced set is empty and every download file is orphaned.
        val dir = tmp.newFolder("downloads")
        val a = File(dir, "a.mp3").apply { writeText("a") }
        val b = File(dir, "b.mp3").apply { writeText("b") }

        val deleted = pruneOrphanedDownloadFiles(dir, emptySet())

        assertEquals(2, deleted)
        assertFalse(a.exists())
        assertFalse(b.exists())
    }

    @Test
    fun returnsZeroForAMissingDirectory() {
        // New-device restore: the downloads dir doesn't exist yet — no-op, no crash.
        val missing = File(tmp.root, "no-downloads-here")

        assertEquals(0, pruneOrphanedDownloadFiles(missing, emptySet()))
    }

    @Test
    fun leavesSubdirectoriesAndTheirContentsUntouched() {
        // Only top-level regular files are download artifacts; never recurse into
        // (or delete) subdirectories.
        val dir = tmp.newFolder("downloads")
        val sub = File(dir, "sub").apply { mkdirs() }
        val nested = File(sub, "nested.mp3").apply { writeText("nested") }
        val orphan = File(dir, "orphan.mp3").apply { writeText("orphan") }

        val deleted = pruneOrphanedDownloadFiles(dir, emptySet())

        assertEquals(1, deleted, "only the top-level orphan is deleted")
        assertTrue(sub.exists())
        assertTrue(nested.exists())
        assertFalse(orphan.exists())
    }
}
