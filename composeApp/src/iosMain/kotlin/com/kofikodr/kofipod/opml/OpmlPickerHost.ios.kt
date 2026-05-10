// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.opml

import androidx.compose.runtime.Composable

@Composable
actual fun OpmlPickerHost() {
    // No-op: file picker isn't wired up on iOS. See OpmlPickerHost.kt for the rationale.
}
