// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import java.io.File

actual class FileChecker actual constructor() : FileCheckerApi {
    override fun exists(path: String): Boolean =
        path.isNotBlank() && File(path).exists()
}
