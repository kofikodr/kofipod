// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

actual class ClipboardPort(private val context: Context) {
    actual fun copyText(
        label: String,
        text: String,
    ) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
