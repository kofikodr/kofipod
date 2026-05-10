// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.backup

import androidx.compose.runtime.Composable

/**
 * Hosts the platform file-picker launchers that back [BackupFilePort]. The Android
 * actual wires `rememberLauncherForActivityResult` for `OpenDocumentTree` (folder pick)
 * and `OpenDocument` (restore pick), bridges results back into the singleton port, and
 * calls `takePersistableUriPermission` on a fresh tree URI so the grant survives reboot.
 *
 * The iOS actual is a no-op — the SAF backup surface isn't wired into iOS UI in v1.
 *
 * Hoisted in [com.kofikodr.kofipod.ui.shell.AppShell] so the launchers stay rooted at the app
 * level. A picker opened from Settings still resolves if the user backgrounds the app
 * mid-flow.
 */
@Composable
expect fun BackupPickerHost()
