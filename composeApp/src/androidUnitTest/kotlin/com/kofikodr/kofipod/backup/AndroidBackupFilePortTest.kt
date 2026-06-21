// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.backup

import android.net.Uri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidBackupFilePortTest {
    @Test
    fun writeBackupToTree_deletesExistingTargetBeforeRename() {
        val filename = "kofipod-backup-20260616-010203.kpbak"
        val tree = FakeBackupDocumentTree()
        tree.seed(filename)

        writeBackupToTree(
            tree = tree,
            filename = filename,
            content = "new backup".encodeToByteArray(),
            writeBytes = { file, bytes ->
                (file as FakeBackupDocumentFile).content = bytes.decodeToString()
            },
        )

        assertEquals(
            listOf(
                "create:$filename.tmp",
                "write:$filename.tmp:new backup",
                "delete:$filename",
                "rename:$filename.tmp:$filename",
            ),
            tree.events,
        )
        assertTrue(tree.hasLiveFile(filename), "exact target should exist after rename")
        assertFalse(
            tree.liveNames().any { it.contains("(1)") },
            "silent SAF disambiguation must not leave an orphaned backup",
        )
    }

    @Test
    fun writeBackupToTree_cleansTempBeforeCopyFallback() {
        val filename = "kofipod-backup-20260616-010203.kpbak"
        val tree = FakeBackupDocumentTree(renameResult = false)

        writeBackupToTree(
            tree = tree,
            filename = filename,
            content = "new backup".encodeToByteArray(),
            writeBytes = { file, bytes ->
                (file as FakeBackupDocumentFile).content = bytes.decodeToString()
            },
        )

        assertEquals(
            listOf(
                "create:$filename.tmp",
                "write:$filename.tmp:new backup",
                "rename:$filename.tmp:$filename",
                "delete:$filename.tmp",
                "create:$filename",
                "write:$filename:new backup",
            ),
            tree.events,
        )
        assertEquals(listOf(filename), tree.liveNames())
    }

    @Test
    fun writeBackupToTree_deletesDisambiguatedRenameBeforeCopyFallback() {
        val filename = "kofipod-backup-20260616-010203.kpbak"
        val disambiguated = "kofipod-backup-20260616-010203 (1).kpbak"
        val tree = FakeBackupDocumentTree(forcedRenameName = disambiguated)

        writeBackupToTree(
            tree = tree,
            filename = filename,
            content = "new backup".encodeToByteArray(),
            writeBytes = { file, bytes ->
                (file as FakeBackupDocumentFile).content = bytes.decodeToString()
            },
        )

        assertEquals(
            listOf(
                "create:$filename.tmp",
                "write:$filename.tmp:new backup",
                "rename:$filename.tmp:$filename",
                "delete:$disambiguated",
                "create:$filename",
                "write:$filename:new backup",
            ),
            tree.events,
        )
        assertEquals(listOf(filename), tree.liveNames())
    }

    @Test
    fun writeBackupToTree_failsWhenExistingTargetCannotBeDeleted() {
        val filename = "kofipod-backup-20260616-010203.kpbak"
        val tree = FakeBackupDocumentTree(deleteFailures = setOf(filename))
        tree.seed(filename)

        assertFailsWith<IllegalStateException> {
            writeBackupToTree(
                tree = tree,
                filename = filename,
                content = "new backup".encodeToByteArray(),
                writeBytes = { file, bytes ->
                    (file as FakeBackupDocumentFile).content = bytes.decodeToString()
                },
            )
        }

        assertEquals(
            listOf(
                "create:$filename.tmp",
                "write:$filename.tmp:new backup",
                "delete-failed:$filename",
                "delete:$filename.tmp",
            ),
            tree.events,
        )
        assertEquals(listOf(filename), tree.liveNames())
    }

    @Test
    fun writeBackupToTree_failsWhenStaleTempCannotBeDeleted() {
        val filename = "kofipod-backup-20260616-010203.kpbak"
        val tempName = "$filename.tmp"
        val tree = FakeBackupDocumentTree(deleteFailures = setOf(tempName))
        tree.seed(tempName)

        assertFailsWith<IllegalStateException> {
            writeBackupToTree(
                tree = tree,
                filename = filename,
                content = "new backup".encodeToByteArray(),
                writeBytes = { file, bytes ->
                    (file as FakeBackupDocumentFile).content = bytes.decodeToString()
                },
            )
        }

        assertEquals(listOf("delete-failed:$tempName"), tree.events)
        assertEquals(listOf(tempName), tree.liveNames())
    }

    @Test
    fun writeBackupToTree_deletesFallbackTargetWhenSecondWriteFails() {
        val filename = "kofipod-backup-20260616-010203.kpbak"
        val tree = FakeBackupDocumentTree(renameResult = false)
        var writes = 0

        assertFailsWith<IllegalStateException> {
            writeBackupToTree(
                tree = tree,
                filename = filename,
                content = "new backup".encodeToByteArray(),
                writeBytes = { file, bytes ->
                    writes += 1
                    if (writes == 2) error("simulated second write failure")
                    (file as FakeBackupDocumentFile).content = bytes.decodeToString()
                },
            )
        }

        assertEquals(
            listOf(
                "create:$filename.tmp",
                "write:$filename.tmp:new backup",
                "rename:$filename.tmp:$filename",
                "delete:$filename.tmp",
                "create:$filename",
                "delete:$filename",
            ),
            tree.events,
        )
        assertEquals(emptyList(), tree.liveNames())
    }

    @Test
    fun writeBackupToTree_failsWhenFallbackTempCannotBeDeleted() {
        val filename = "kofipod-backup-20260616-010203.kpbak"
        val tree = FakeBackupDocumentTree(renameResult = false, deleteFailures = setOf("$filename.tmp"))

        assertFailsWith<IllegalStateException> {
            writeBackupToTree(
                tree = tree,
                filename = filename,
                content = "new backup".encodeToByteArray(),
                writeBytes = { file, bytes ->
                    (file as FakeBackupDocumentFile).content = bytes.decodeToString()
                },
            )
        }

        assertEquals(
            listOf(
                "create:$filename.tmp",
                "write:$filename.tmp:new backup",
                "rename:$filename.tmp:$filename",
                "delete-failed:$filename.tmp",
            ),
            tree.events,
        )
        assertEquals(listOf("$filename.tmp"), tree.liveNames())
    }

    @Test
    fun writeBackupToTree_failsWhenDisambiguatedRenameCannotBeDeleted() {
        val filename = "kofipod-backup-20260616-010203.kpbak"
        val disambiguated = "kofipod-backup-20260616-010203 (1).kpbak"
        val tree =
            FakeBackupDocumentTree(
                forcedRenameName = disambiguated,
                deleteFailures = setOf(disambiguated),
            )

        assertFailsWith<IllegalStateException> {
            writeBackupToTree(
                tree = tree,
                filename = filename,
                content = "new backup".encodeToByteArray(),
                writeBytes = { file, bytes ->
                    (file as FakeBackupDocumentFile).content = bytes.decodeToString()
                },
            )
        }

        assertEquals(
            listOf(
                "create:$filename.tmp",
                "write:$filename.tmp:new backup",
                "rename:$filename.tmp:$filename",
                "delete-failed:$disambiguated",
            ),
            tree.events,
        )
        assertEquals(listOf(disambiguated), tree.liveNames())
    }

    private class FakeBackupDocumentTree(
        private val renameResult: Boolean = true,
        private val forcedRenameName: String? = null,
        private val deleteFailures: Set<String> = emptySet(),
    ) : BackupDocumentTree {
        val events = mutableListOf<String>()
        private val files = linkedMapOf<String, FakeBackupDocumentFile>()

        fun seed(name: String) {
            files[name] = FakeBackupDocumentFile(tree = this, name = name, renameResult = renameResult)
        }

        fun hasLiveFile(name: String): Boolean = files[name]?.deleted == false

        fun liveNames(): List<String> =
            files.values
                .filterNot { it.deleted }
                .map { it.name.orEmpty() }

        override fun findFile(name: String): BackupDocumentFile? = files[name]?.takeUnless { it.deleted }

        override fun createFile(
            mimeType: String,
            name: String,
        ): BackupDocumentFile {
            events += "create:$name"
            return FakeBackupDocumentFile(tree = this, name = name, renameResult = renameResult)
                .also { files[name] = it }
        }

        fun recordWrite(
            name: String,
            content: String,
        ) {
            events += "write:$name:$content"
        }

        fun delete(file: FakeBackupDocumentFile): Boolean {
            if (file.name in deleteFailures) {
                events += "delete-failed:${file.name}"
                return false
            }
            events += "delete:${file.name}"
            file.deleted = true
            files.remove(file.name)
            return true
        }

        fun rename(
            file: FakeBackupDocumentFile,
            target: String,
        ): Boolean {
            events += "rename:${file.name}:$target"
            if (!renameResult) return false

            files.remove(file.name)
            val occupied = files[target]?.deleted == false
            file.name = forcedRenameName ?: if (occupied) target.replace(".kpbak", " (1).kpbak") else target
            files[file.name.orEmpty()] = file
            return true
        }
    }

    private class FakeBackupDocumentFile(
        private val tree: FakeBackupDocumentTree,
        override var name: String?,
        private val renameResult: Boolean,
    ) : BackupDocumentFile {
        var deleted = false
        var content: String = ""
            set(value) {
                field = value
                tree.recordWrite(name.orEmpty(), value)
            }

        override val uri: Uri
            get() = error("fake writer does not use Android Uri")

        override fun renameTo(displayName: String): Boolean = tree.rename(this, displayName)

        override fun delete(): Boolean = tree.delete(this)
    }
}
