// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.permission

import androidx.compose.runtime.Composable

@Composable
actual fun rememberNotificationPermissionRequester(
    onResult: (granted: Boolean) -> Unit,
): () -> Unit = {
    // TODO: UNUserNotificationCenter.requestAuthorization
    onResult(true)
}
