// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.share

/**
 * Share contract the UI/ViewModels depend on. Extracted as an interface so the platform
 * [PlatformSharer] — an `expect`/`actual` class whose Android actual needs a `Context` and
 * therefore can't be built in a (Robolectric-free) unit test — can be substituted by a fake.
 */
interface Sharer {
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

/**
 * Platform share implementation. Android fires `ACTION_SEND`; iOS is a stub. Common code
 * never constructs it — it is resolved from Koin as a [Sharer].
 */
expect class PlatformSharer : Sharer
