// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.snippets

/**
 * Test-friendly seam over the platform's file existence check. Production
 * uses the [FileChecker] expect/actual; tests use [FakeFileChecker]
 * implementing the same interface.
 */
interface FileCheckerApi {
    fun exists(path: String): Boolean
}

expect class FileChecker() : FileCheckerApi
