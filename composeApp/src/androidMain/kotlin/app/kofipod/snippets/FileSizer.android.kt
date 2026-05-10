// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import java.io.File

actual class FileSizer {
    actual fun sizeOf(path: String): Long = runCatching { File(path).length().coerceAtLeast(0L) }.getOrDefault(0L)
}
