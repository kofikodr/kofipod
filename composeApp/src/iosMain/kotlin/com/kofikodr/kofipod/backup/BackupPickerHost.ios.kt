// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.backup

import androidx.compose.runtime.Composable

@Composable
actual fun BackupPickerHost() {
    // No-op: SAF backup picker isn't wired up on iOS. See BackupPickerHost.kt for rationale.
}
