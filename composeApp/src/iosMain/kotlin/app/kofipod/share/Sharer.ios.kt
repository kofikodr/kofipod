// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.share

actual class Sharer {
    actual fun shareText(
        title: String,
        text: String,
    ) {
        // TODO: present UIActivityViewController with [title, text]
    }

    actual fun shareFile(
        title: String,
        path: String,
        mimeType: String,
        captionText: String?,
    ) {
        // iOS: not implemented in this slice.
    }
}
