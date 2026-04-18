// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.share

import android.content.Context
import android.content.Intent

actual class Sharer(private val context: Context) {
    actual fun shareText(title: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
