// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual class MarkdownTempFilePort(private val context: Context) {
    actual suspend fun writeTemp(
        filename: String,
        content: String,
    ): String =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "markdown")
            dir.mkdirs()
            val file = File(dir, filename)
            file.writeText(content)
            file.absolutePath
        }
}
