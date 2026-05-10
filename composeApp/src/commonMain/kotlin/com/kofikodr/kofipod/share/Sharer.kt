// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.share

expect class Sharer {
    fun shareText(
        title: String,
        text: String,
    )

    fun shareFile(
        title: String,
        path: String,
        mimeType: String,
        captionText: String? = null,
    )
}
