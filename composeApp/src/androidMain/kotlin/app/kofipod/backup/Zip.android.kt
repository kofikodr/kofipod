// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

actual class ZipBuilder {
    private val buf = ByteArrayOutputStream()
    private val zip = ZipOutputStream(buf)
    private var finished = false

    actual fun addEntry(
        name: String,
        bytes: ByteArray,
    ) {
        check(!finished) { "ZipBuilder already finished" }
        val entry = ZipEntry(name)
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    actual fun finish(): ByteArray {
        if (!finished) {
            zip.finish()
            zip.close()
            finished = true
        }
        return buf.toByteArray()
    }
}

actual fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
    val out = mutableMapOf<String, ByteArray>()
    runCatching {
        ZipInputStream(ByteArrayInputStream(bytes)).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                if (!entry.isDirectory) {
                    out[entry.name] = stream.readBytes()
                }
                stream.closeEntry()
            }
        }
    }.onFailure {
        // Treat any zip-parse error as "no entries" — the repo's validation layer
        // surfaces this as RestoreError.ZipUnreadable, which is the right user message.
        out.clear()
    }
    return out
}
