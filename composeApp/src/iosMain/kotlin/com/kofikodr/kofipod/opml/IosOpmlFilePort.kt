// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.opml

/**
 * No-op iOS binding so Koin's graph stays consistent with Android. The OPML feature
 * isn't wired into iOS UI; calling these would surface immediately as a "cancelled"
 * picker, which is the closest harmless fallback if the surface ever does get exposed.
 */
class IosOpmlFilePort : OpmlFilePort {
    override suspend fun pickImport(): ByteArray? = null

    override suspend fun saveExport(
        suggestedFilename: String,
        content: String,
    ): Boolean = false
}
