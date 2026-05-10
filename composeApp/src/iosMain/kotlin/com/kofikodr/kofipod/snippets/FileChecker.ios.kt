// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.snippets

actual class FileChecker actual constructor() : FileCheckerApi {
    override fun exists(path: String): Boolean = false
}
