// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.kofikodr.kofipod.ui.screens.settings.podcastindex.PodcastIndexSetupContent
import com.kofikodr.kofipod.ui.screens.settings.podcastindex.PodcastIndexSetupUiState
import com.kofikodr.kofipod.ui.theme.KofipodTheme
import com.kofikodr.kofipod.ui.theme.KofipodThemeMode
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi baselines for [PodcastIndexSetupContent].
 *
 * Four visually-distinct states are snapshotted:
 *  - empty: the default idle state — both fields blank, no error, not connected.
 *  - verifying: both fields disabled and the button reads "Verifying…".
 *  - error: inline error message below the secret field after a rejected credential.
 *  - connected: the "Connected" footer with a Disconnect button, fields hidden.
 *
 * The Koin-wired [PodcastIndexSetupScreen] entry point is intentionally NOT
 * snapshotted here — snapshotting the stateless content composable avoids
 * the Koin / KofipodPlayer initialisation that crashes Paparazzi.
 */
class PodcastIndexSetupSnapshots {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = DeviceConfig.PIXEL_5,
            theme = "android:Theme.Material.Light.NoActionBar",
        )

    @Test
    fun podcastIndexSetup_empty_light() =
        paparazzi.snapshot {
            SetupHarness(state = emptyState())
        }

    @Test
    fun podcastIndexSetup_verifying_light() =
        paparazzi.snapshot {
            SetupHarness(state = verifyingState())
        }

    @Test
    fun podcastIndexSetup_error_light() =
        paparazzi.snapshot {
            SetupHarness(state = errorState())
        }

    @Test
    fun podcastIndexSetup_connected_light() =
        paparazzi.snapshot {
            SetupHarness(state = connectedState())
        }
}

@Composable
private fun SetupHarness(state: PodcastIndexSetupUiState) {
    KofipodTheme(KofipodThemeMode.Light) {
        val c = LocalKofipodColors.current
        Box(modifier = Modifier.fillMaxSize().background(c.bg)) {
            PodcastIndexSetupContent(
                state = state,
                onKeyChange = {},
                onSecretChange = {},
                onConnect = {},
                onRequestDisconnect = {},
                onCancelDisconnect = {},
                onConfirmDisconnect = {},
                onBack = {},
            )
        }
    }
}

private fun emptyState(): PodcastIndexSetupUiState = PodcastIndexSetupUiState()

private fun verifyingState(): PodcastIndexSetupUiState =
    PodcastIndexSetupUiState(
        keyValue = "my-api-key",
        secretValue = "my-api-secret",
        verifying = true,
    )

private fun errorState(): PodcastIndexSetupUiState =
    PodcastIndexSetupUiState(
        keyValue = "bad-key",
        secretValue = "bad-secret",
        errorMessage = "That key or secret was rejected. Double-check both values.",
    )

private fun connectedState(): PodcastIndexSetupUiState =
    PodcastIndexSetupUiState(
        connected = true,
    )
