// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.opml

import androidx.compose.runtime.Composable

/**
 * Hosts the platform file-picker launchers that back [OpmlFilePort]. The Android actual
 * wires `rememberLauncherForActivityResult` for `ACTION_OPEN_DOCUMENT` and
 * `ACTION_CREATE_DOCUMENT` and bridges results back into the singleton port. The iOS
 * actual is a no-op (file picker is out of scope for the secondary target).
 *
 * Hoisted in [com.kofikodr.kofipod.ui.shell.AppShell] so the launchers stay rooted at the app
 * level — a picker opened from Settings still resolves if the user backgrounds the app
 * mid-flow.
 */
@Composable
expect fun OpmlPickerHost()
