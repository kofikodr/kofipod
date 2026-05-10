// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm

actual class MarkdownTempFilePort {
    actual suspend fun writeTemp(
        filename: String,
        content: String,
    ): String {
        throw NotImplementedError("ios")
    }
}
