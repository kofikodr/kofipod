// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.share

actual class PlatformSharer : Sharer {
    override fun shareText(
        title: String,
        text: String,
    ) {
        // TODO: present UIActivityViewController with [title, text]
    }

    override fun shareFile(
        title: String,
        path: String,
        mimeType: String,
        captionText: String?,
    ) {
        // iOS: not implemented in this slice.
    }
}
