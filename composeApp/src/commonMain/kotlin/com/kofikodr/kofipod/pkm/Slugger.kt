// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm

/**
 * ASCII-safe filename slug. Used for `.md` filenames only — body content is
 * never slugged.
 *
 * - Lowercases.
 * - Replaces every run of non-alphanumeric (ASCII a-z, 0-9) with a single `-`.
 * - Trims leading/trailing `-`.
 * - Truncates to [maxLen] characters.
 * - Returns `"untitled"` if the result is empty.
 *
 * Accent stripping uses a manual normalize-then-strip because
 * java.text.Normalizer is JVM-only. We strip a curated set of Latin-1 Supplement
 * + Latin Extended-A diacritics; everything outside the BMP letter range
 * (emoji, CJK, etc.) is dropped.
 */
fun slugify(
    text: String,
    maxLen: Int = 32,
): String {
    if (maxLen <= 0) return "untitled"
    val sb = StringBuilder(text.length)
    var prevHyphen = false
    for (ch in text) {
        val mapped = stripDiacritic(ch).lowercaseChar()
        if (mapped in 'a'..'z' || mapped in '0'..'9') {
            sb.append(mapped)
            prevHyphen = false
        } else if (!prevHyphen && sb.isNotEmpty()) {
            sb.append('-')
            prevHyphen = true
        }
    }
    while (sb.isNotEmpty() && sb.last() == '-') sb.deleteAt(sb.length - 1)
    if (sb.isEmpty()) return "untitled"
    if (sb.length > maxLen) sb.setLength(maxLen)
    while (sb.isNotEmpty() && sb.last() == '-') sb.deleteAt(sb.length - 1)
    return if (sb.isEmpty()) "untitled" else sb.toString()
}

private fun stripDiacritic(c: Char): Char =
    when (c) {
        'à', 'á', 'â', 'ã', 'ä', 'å' -> 'a'
        'è', 'é', 'ê', 'ë' -> 'e'
        'ì', 'í', 'î', 'ï' -> 'i'
        'ò', 'ó', 'ô', 'õ', 'ö' -> 'o'
        'ù', 'ú', 'û', 'ü' -> 'u'
        'ý', 'ÿ' -> 'y'
        'ñ' -> 'n'
        'ç' -> 'c'
        'À', 'Á', 'Â', 'Ã', 'Ä', 'Å' -> 'A'
        'È', 'É', 'Ê', 'Ë' -> 'E'
        'Ì', 'Í', 'Î', 'Ï' -> 'I'
        'Ò', 'Ó', 'Ô', 'Õ', 'Ö' -> 'O'
        'Ù', 'Ú', 'Û', 'Ü' -> 'U'
        'Ñ' -> 'N'
        'Ç' -> 'C'
        else -> c
    }
