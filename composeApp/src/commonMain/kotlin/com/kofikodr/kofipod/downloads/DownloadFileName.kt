// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.downloads

/**
 * Build the on-disk filename for an episode download. Both inputs come from RSS feed
 * metadata that we don't control — `episodeId` is a string we treat as opaque but the
 * feed publisher chose its shape, and `mimeType` is whatever the enclosure declared.
 * The resulting string is later joined as a path component under the downloads
 * directory, so anything containing `/`, `\`, or a `..` segment could let a hostile
 * feed escape the app's private storage. Both inputs are sanitised here; downstream
 * code may rely on the output being a single safe filename component.
 *
 * Defaults to `.mp3` when the enclosure mime type is missing, unrecognised, or fails
 * sanitisation. The id falls back to `episode` if sanitisation strips every character.
 *
 * Contract pinned by `DownloadFileNameTest`.
 */
fun downloadFileName(
    episodeId: String,
    mimeType: String,
): String {
    val safeExt = sanitiseExtension(mimeType.substringAfter('/', missingDelimiterValue = ""))
    return "${downloadFileStem(episodeId)}.$safeExt"
}

/**
 * The id portion of the on-disk filename, without the dot or extension. Exposed so
 * the delete path can match the same sanitised stem the write path produced — the
 * raw `episodeId` no longer guarantees prefix-matches the on-disk name once special
 * characters are remapped to `_`.
 */
fun downloadFileStem(episodeId: String): String = sanitiseIdComponent(episodeId)

private const val DEFAULT_EXT = "mp3"
private const val DEFAULT_ID = "episode"

/** Max length for a recognised audio extension. flac/opus = 4, mpeg = 4. Buffer of 1. */
private const val MAX_EXT_LENGTH = 5

/**
 * Cap on the id component. ext4's per-segment limit is 255 bytes; we leave room for
 * `.<ext>` plus any prefix the caller adds when joining into a path. 100 ASCII chars
 * comfortably covers every real-world episode id (PodcastIndex feed IDs are 8–10
 * digits; UUIDs are 36; slugs are usually < 80).
 */
private const val MAX_ID_LENGTH = 100

private fun sanitiseExtension(rawExt: String): String {
    if (rawExt.isEmpty() || rawExt.length > MAX_EXT_LENGTH) return DEFAULT_EXT
    // Strict ASCII alphanumeric. `Char.isLetterOrDigit()` accepts Unicode letters
    // (e.g. Arabic-Indic digits, Cyrillic letters) which look like a valid extension
    // but can confuse downstream path / MIME-sniffing code. A real audio extension
    // is ASCII; anything else means the feed's MIME type isn't usable as one — fall
    // back rather than guess. Also lower-case so `audio/MP3` and `audio/mp3` produce
    // the same on-disk filename on case-sensitive filesystems.
    if (rawExt.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' }) return DEFAULT_EXT
    return rawExt.lowercase()
}

private fun sanitiseIdComponent(raw: String): String {
    // Keep ASCII alphanumerics and the two punctuation chars used in real episode ids
    // (PodcastIndex feed IDs are numeric; UUIDs and slugs use `-` and `_`). Everything
    // else — including `/`, `\`, the `.` that would otherwise form `..`, NUL, control
    // chars, and Unicode codepoints — becomes `_`. Then collapse runs and trim so a
    // string of separators doesn't leave a leading/trailing underscore in the filename.
    val mapped =
        buildString(raw.length) {
            for (c in raw) {
                if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_') {
                    append(c)
                } else {
                    append('_')
                }
            }
        }
    val collapsed = mapped.trim('_').replace(UNDERSCORE_RUN, "_")
    if (collapsed.isEmpty()) return DEFAULT_ID
    return if (collapsed.length > MAX_ID_LENGTH) collapsed.take(MAX_ID_LENGTH) else collapsed
}

private val UNDERSCORE_RUN = Regex("_+")
