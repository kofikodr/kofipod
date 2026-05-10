// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

actual class MarkdownTempFilePort {
    actual suspend fun writeTemp(
        filename: String,
        content: String,
    ): String {
        throw NotImplementedError("ios")
    }
}
