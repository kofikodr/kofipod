// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm

actual class ClipboardPort {
    actual fun copyText(
        label: String,
        text: String,
    ) {
        // iOS: TODO — UIPasteboard.general.string = text once iOS becomes a focus.
    }
}
