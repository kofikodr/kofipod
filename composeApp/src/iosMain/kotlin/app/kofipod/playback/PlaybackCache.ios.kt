// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playback

actual class PlaybackCache {
    // TODO iOS streaming cache — iOS playback itself is still a TODO stub.
    actual fun sizeBytes(): Long = 0L

    actual fun clear() {}
}
