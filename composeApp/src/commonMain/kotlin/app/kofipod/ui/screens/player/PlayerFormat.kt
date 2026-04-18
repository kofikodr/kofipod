// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.player

internal fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val sPad = s.toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sPad" else "$m:$sPad"
}

internal fun formatSpeed(speed: Float): String {
    val tenths = ((speed * 10f) + 0.5f).toInt().coerceAtLeast(0)
    return "${tenths / 10}.${tenths % 10}"
}
