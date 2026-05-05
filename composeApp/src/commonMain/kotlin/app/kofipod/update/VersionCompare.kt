// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.update

/**
 * Compare two semver-ish strings (e.g. `1.1.2`, `v1.1.2`, `1.1.2-debug`).
 *
 * Returns negative if [a] < [b], zero if equal, positive if [a] > [b].
 *
 * Rules:
 * - Leading `v` / `V` is stripped.
 * - Anything from the first `-` onward (pre-release / build suffix) is dropped before
 *   comparison so `1.1.2-debug` and `1.1.2` compare equal. This matches our needs:
 *   debug builds must not perpetually flag themselves as out-of-date once a stable
 *   release with the same numeric version has shipped.
 * - Missing components default to 0; extra components are compared too. So `1.1` < `1.1.1`.
 * - Non-numeric components fall back to lexicographic comparison; we never expect to
 *   see them given our release tagging discipline, but we don't want to crash if we do.
 */
internal fun compareSemver(
    a: String,
    b: String,
): Int {
    val pa = parts(a)
    val pb = parts(b)
    val n = maxOf(pa.size, pb.size)
    for (i in 0 until n) {
        val ai = pa.getOrNull(i) ?: "0"
        val bi = pb.getOrNull(i) ?: "0"
        val cmp =
            when {
                ai.all { it.isDigit() } && bi.all { it.isDigit() } ->
                    (ai.toLongOrNull() ?: 0L).compareTo(bi.toLongOrNull() ?: 0L)
                else -> ai.compareTo(bi)
            }
        if (cmp != 0) return cmp
    }
    return 0
}

private fun parts(raw: String): List<String> {
    val trimmed = raw.trim().removePrefix("v").removePrefix("V")
    val core = trimmed.substringBefore('-')
    if (core.isEmpty()) return listOf("0")
    return core.split('.')
}
