// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.permission

import androidx.compose.runtime.Composable

/**
 * Returns a launcher that, when invoked, ensures notifications are permitted.
 * [onResult] is called with the resulting grant state (true if no runtime prompt is needed).
 */
@Composable
expect fun rememberNotificationPermissionRequester(
    onResult: (granted: Boolean) -> Unit,
): () -> Unit
