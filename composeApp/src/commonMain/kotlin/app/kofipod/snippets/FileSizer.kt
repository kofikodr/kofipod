// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

/** Returns 0 when the path is unreadable / missing / on a stub platform. */
expect class FileSizer() {
    fun sizeOf(path: String): Long
}
