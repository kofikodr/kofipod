// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.downloads

/**
 * Build the on-disk filename for an episode download. Defaults to .mp3 when the
 * enclosure mime type is missing or unrecognized.
 */
fun downloadFileName(
    episodeId: String,
    mimeType: String,
): String {
    val ext = mimeType.substringAfter('/', "mp3").ifBlank { "mp3" }
    return "$episodeId.$ext"
}
