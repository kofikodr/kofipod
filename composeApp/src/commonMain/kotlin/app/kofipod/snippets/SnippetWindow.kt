// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

object SnippetWindow {
    private const val LAST_WINDOW_MS = 60_000L
    private const val MIN_SPAN_MS = 1_000L

    data class Window(val startMs: Long, val endMs: Long)

    /**
     * Per spec § F1: Snip-last-60s opens an editor with a draft anchored at
     * `[currentPosition − 60_000ms, currentPosition]`. Clamps start to zero
     * for early-position episodes; never overruns duration.
     */
    fun computeLast60sWindow(
        positionMs: Long,
        durationMs: Long,
    ): Window {
        val end = positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
        val start = (end - LAST_WINDOW_MS).coerceAtLeast(0L)
        return Window(start, end)
    }

    /**
     * Bring an arbitrary user-edited window back into [0, duration] with a
     * minimum 1s span. Swaps reversed start/end. Prefers extending end to
     * satisfy the min-span; falls back to pulling start back if end is
     * already at duration.
     */
    fun clampWindow(
        startMs: Long,
        endMs: Long,
        durationMs: Long,
    ): Window {
        val cap = durationMs.coerceAtLeast(0L)
        var s = startMs.coerceIn(0L, cap)
        var e = endMs.coerceIn(0L, cap)
        if (e < s) {
            val t = s
            s = e
            e = t
        }
        if (e - s < MIN_SPAN_MS) {
            val needed = MIN_SPAN_MS - (e - s)
            val canExtendEnd = (cap - e).coerceAtLeast(0L)
            if (canExtendEnd >= needed) {
                e += needed
            } else {
                e = cap
                s = (e - MIN_SPAN_MS).coerceAtLeast(0L)
            }
        }
        return Window(s, e)
    }

    /** mm:ss.s formatting (one decimal). For UI display only — not for storage. */
    fun formatTimestampDeci(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val totalDeci = (safe + 50L) / 100L // round to nearest 0.1s
        val deci = (totalDeci % 10L).toInt()
        val totalSec = totalDeci / 10L
        val mm = (totalSec / 60L).toInt()
        val ss = (totalSec % 60L).toInt()
        val mmStr = if (mm < 10) "0$mm" else mm.toString()
        val ssStr = if (ss < 10) "0$ss" else ss.toString()
        return "$mmStr:$ssStr.$deci"
    }

    /** mm:ss formatting (no decimal) — matches the Slice 4 design's chip + axis labels. */
    fun formatTimestamp(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L) + 500L) / 1_000L
        val mm = (totalSec / 60L).toInt()
        val ss = (totalSec % 60L).toInt()
        val mmStr = if (mm < 10) "0$mm" else mm.toString()
        val ssStr = if (ss < 10) "0$ss" else ss.toString()
        return "$mmStr:$ssStr"
    }

    /** Compact selection-duration formatting: drops the leading zero (`0:42`, `12:05`). */
    fun formatDuration(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L) + 500L) / 1_000L
        val mm = (totalSec / 60L).toInt()
        val ss = (totalSec % 60L).toInt()
        val ssStr = if (ss < 10) "0$ss" else ss.toString()
        return "$mm:$ssStr"
    }
}
