// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract for [downloadFileName]. The function's output is later joined to the
 * downloads directory as a path component, so any `/`, `\`, or `..` sequence in the
 * resulting string can let a hostile feed escape the app's private storage. We pin
 * both the happy path (real-world MIME types map to recognisable extensions) and the
 * sanitisation gates (every defensive branch must produce a specific safe output, not
 * just be free of one particular bad substring).
 */
class DownloadFileNameTest {
    @Test
    fun audioMpeg_yieldsMpegExtension() {
        // ExoPlayer recognises `.mpeg` by extension; pin so a future normalisation
        // pass doesn't silently rename existing downloaded files.
        assertEquals("ep1.mpeg", downloadFileName("ep1", "audio/mpeg"))
    }

    @Test
    fun audioMp4_yieldsMp4Extension() {
        // The function keeps the raw subtype rather than mapping mp4→m4a — that's
        // fine for ExoPlayer (recognised by extension). Pin so a future refactor
        // doesn't silently rename existing downloaded files.
        assertEquals("ep1.mp4", downloadFileName("ep1", "audio/mp4"))
    }

    @Test
    fun unknownAudioSubtype_yieldsRawIfAlphanumeric() {
        // Unknown but alphanumeric subtypes still pass — the function isn't a strict
        // allowlist, it's a sanitiser. The defence is structure (no separators), not
        // semantics (every known codec listed).
        assertEquals("ep1.opus", downloadFileName("ep1", "audio/opus"))
        assertEquals("ep1.flac", downloadFileName("ep1", "audio/flac"))
    }

    @Test
    fun mixedCaseExtension_isLowercased() {
        // `audio/MP3` and `audio/mp3` must yield the same filename so we don't end
        // up with two files on a case-sensitive filesystem for the same content.
        assertEquals("ep1.mp3", downloadFileName("ep1", "audio/MP3"))
        assertEquals("ep1.mpeg", downloadFileName("ep1", "audio/MPEG"))
    }

    @Test
    fun emptyMimeType_fallsBackToMp3() {
        assertEquals("ep1.mp3", downloadFileName("ep1", ""))
    }

    @Test
    fun mimeWithoutSlash_fallsBackToMp3() {
        // "mpeg" with no slash means we can't trust the producer to be following the
        // MIME shape at all — defensive fallback.
        assertEquals("ep1.mp3", downloadFileName("ep1", "mpeg"))
    }

    @Test
    fun mimeBlankSubtype_fallsBackToMp3() {
        assertEquals("ep1.mp3", downloadFileName("ep1", "audio/"))
    }

    @Test
    fun mimeSubtypeWithPathTraversal_fallsBackToMp3() {
        // The attack the finding flagged. Without sanitisation, the joined path
        // becomes `<dl>/ep1.foo/../bar` and resolves outside the downloads dir.
        assertEquals("ep1.mp3", downloadFileName("ep1", "audio/foo/../bar"))
    }

    @Test
    fun mimeSubtypeWithBackslash_fallsBackToMp3() {
        // Backslash is a path separator on Windows / can be normalised by the kernel
        // on Android in some contexts. Treat the same as forward slash.
        assertEquals("ep1.mp3", downloadFileName("ep1", "audio/foo\\bar"))
    }

    @Test
    fun mimeSubtypeWithCodecsParam_fallsBackToMp3() {
        // `audio/mp4; codecs=mp4a.40.2` — `substringAfter('/')` would otherwise keep
        // everything after the first slash, including the `;` and spaces, which is
        // not a safe filename extension even if it's not a traversal attempt.
        assertEquals("ep1.mp3", downloadFileName("ep1", "audio/mp4; codecs=mp4a.40.2"))
    }

    @Test
    fun mimeSubtypeTooLong_fallsBackToMp3() {
        // No real audio extension is longer than 5 chars (flac/opus tied at 4). A
        // longer alphanumeric run is more likely an attempt to confuse downstream
        // path-handling than a real codec name.
        assertEquals("ep1.mp3", downloadFileName("ep1", "audio/extensionlongerthanlimit"))
    }

    @Test
    fun mimeSubtypeWithUnicodeDigits_fallsBackToMp3() {
        // Arabic-Indic digits pass `Char.isLetterOrDigit()` but aren't an ASCII
        // extension — strict ASCII gate must reject so downstream code receives an
        // ASCII-only filename.
        assertEquals("ep1.mp3", downloadFileName("ep1", "audio/٠١"))
    }

    @Test
    fun episodeIdWithSlash_isReplacedWithUnderscore() {
        assertEquals("foo_bar.mpeg", downloadFileName("foo/bar", "audio/mpeg"))
    }

    @Test
    fun episodeIdWithDotDot_isFullySanitised() {
        // `..` and `/` are both unsafe and become `_`; the leading run is trimmed.
        // Pinning the exact output catches regressions where the sanitiser maps the
        // input to something else still unsafe (e.g. a single `.`).
        assertEquals("escape.mpeg", downloadFileName("../escape", "audio/mpeg"))
    }

    @Test
    fun episodeIdWithBackslash_isReplacedWithUnderscore() {
        assertEquals("foo_bar.mpeg", downloadFileName("foo\\bar", "audio/mpeg"))
    }

    @Test
    fun episodeIdWithNulByte_isReplacedWithUnderscore() {
        // A NUL byte in a filename either truncates at the kernel boundary on some
        // file systems or rejects the open. Sanitiser must strip it before we hit
        // either failure mode.
        val nul = '\u0000'
        val name = downloadFileName("real-id" + nul + "shadow", "audio/mpeg")
        assertEquals("real-id_shadow.mpeg", name)
        assertFalse(nul in name, "NUL must not survive sanitisation")
    }

    @Test
    fun episodeIdWithControlChars_isReplacedWithUnderscore() {
        // Tab, newline, and other low-ASCII control chars get the same treatment.
        assertEquals("a_b_c.mpeg", downloadFileName("a\tb\nc", "audio/mpeg"))
    }

    @Test
    fun episodeIdEmpty_fallsBackToEpisode() {
        // An empty id would produce `.mp3` which is a hidden file on Unix — and worse,
        // collides with every other id-less download.
        assertEquals("episode.mpeg", downloadFileName("", "audio/mpeg"))
    }

    @Test
    fun episodeIdAllUnsafe_fallsBackToEpisode() {
        // If every character is stripped by sanitisation, fall back rather than
        // leaving the file with no usable stem.
        assertEquals("episode.mpeg", downloadFileName("///", "audio/mpeg"))
    }

    @Test
    fun episodeIdTooLong_isTruncated() {
        // ext4 caps per-segment filename at 255 bytes. The id sanitiser caps at 100
        // ASCII chars so the joined `<id>.<ext>` always fits with margin.
        val id = "a".repeat(500)
        val name = downloadFileName(id, "audio/mpeg")
        assertTrue(name.length <= 110, "expected id capped near 100 chars; got len=${name.length}")
        assertEquals("a".repeat(100) + ".mpeg", name)
    }

    @Test
    fun downloadFileStem_matchesNameStem_forAnyId() {
        // The write path produces `<stem>.<ext>` via `downloadFileName`. The delete
        // path matches by `nameWithoutExtension == downloadFileStem(id)`. Both must
        // agree for every id the writer could persist — even ids whose raw form
        // diverges from the sanitised form. Pin a representative cross-section.
        val ids = listOf("123", "abc-def", "foo/bar", "../escape", "a\tb", "")
        for (id in ids) {
            val name = downloadFileName(id, "audio/mpeg")
            val stem = downloadFileStem(id)
            assertEquals(
                stem,
                name.substringBeforeLast('.'),
                "stem from downloadFileStem must equal the writer's name stem for id='$id'",
            )
        }
    }
}
