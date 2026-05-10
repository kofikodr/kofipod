// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm

/**
 * Platform clipboard port. Android wraps `ClipboardManager`; iOS is a no-op
 * stub for now (iOS is secondary).
 */
expect class ClipboardPort {
    /** Place [text] on the system clipboard with a human-readable [label]. */
    fun copyText(
        label: String,
        text: String,
    )
}
