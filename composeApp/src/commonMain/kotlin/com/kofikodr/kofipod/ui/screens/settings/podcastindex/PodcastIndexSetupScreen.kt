// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.settings.podcastindex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofikodr.kofipod.ui.primitives.KPButton
import com.kofikodr.kofipod.ui.primitives.KPButtonStyle
import com.kofikodr.kofipod.ui.primitives.KPIcon
import com.kofikodr.kofipod.ui.primitives.KPIconName
import com.kofikodr.kofipod.ui.primitives.SectionLabel
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import com.kofikodr.kofipod.ui.theme.LocalKofipodRadii
import org.koin.compose.viewmodel.koinViewModel

private const val PODCAST_INDEX_API_URL = "https://podcastindex.org/api"

@Composable
fun PodcastIndexSetupScreen(
    onBack: () -> Unit,
    viewModel: PodcastIndexSetupViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    PodcastIndexSetupContent(
        state = state,
        onKeyChange = viewModel::onKeyChange,
        onSecretChange = viewModel::onSecretChange,
        onConnect = viewModel::connect,
        onRequestDisconnect = viewModel::requestDisconnect,
        onCancelDisconnect = viewModel::cancelDisconnect,
        onConfirmDisconnect = viewModel::confirmDisconnect,
        onBack = onBack,
    )
}

@Composable
internal fun PodcastIndexSetupContent(
    state: PodcastIndexSetupUiState,
    onKeyChange: (String) -> Unit,
    onSecretChange: (String) -> Unit,
    onConnect: () -> Unit,
    onRequestDisconnect: () -> Unit,
    onCancelDisconnect: () -> Unit,
    onConfirmDisconnect: () -> Unit,
    onBack: () -> Unit,
) {
    val c = LocalKofipodColors.current

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 20.dp, bottom = 40.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onBack() }
                    .padding(8.dp),
            ) {
                KPIcon(name = KPIconName.Back, color = c.text, size = 20.dp)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Podcast Index",
                color = c.text,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 26.sp,
            )
        }

        SectionLabel("How this works")
        PodcastIndexDisclosureCard()

        SectionLabel("Get a free key")
        PodcastIndexGetKeyRow()

        SectionLabel("Your API key")
        CredentialField(
            value = state.keyValue,
            onValueChange = onKeyChange,
            connected = state.connected,
            verifying = state.verifying,
            placeholder = if (state.connected) "Connected — paste a new key to replace it" else "Paste your key",
            masked = false,
            testTag = "piKeyField",
        )

        SectionLabel("Your API secret")
        CredentialField(
            value = state.secretValue,
            onValueChange = onSecretChange,
            connected = state.connected,
            verifying = state.verifying,
            placeholder = if (state.connected) "Connected — paste a new secret to replace it" else "Paste your secret",
            masked = true,
            testTag = "piSecretField",
            errorMessage = state.errorMessage,
        )

        Spacer(Modifier.height(24.dp))
        if (state.connected) {
            PodcastIndexConnectedFooter(onDisconnect = onRequestDisconnect)
        } else {
            KPButton(
                label = if (state.verifying) "Verifying…" else "Connect",
                onClick = { if (!state.verifying) onConnect() },
                style = KPButtonStyle.PrimaryPink,
                modifier = Modifier.fillMaxWidth().testTag("piConnectButton"),
            )
        }
    }

    if (state.showDisconnectConfirm) {
        PodcastIndexDisconnectConfirmDialog(
            onCancel = onCancelDisconnect,
            onConfirm = onConfirmDisconnect,
        )
    }
}

@Composable
private fun PodcastIndexDisclosureCard() {
    val c = LocalKofipodColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Text(
            "Kofipod uses the Podcast Index API to search and discover podcasts. " +
                "You bring your own free API credentials from podcastindex.org.",
            color = c.text,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Your key and secret are stored only on this device and are used to " +
                "authenticate API requests. They are never synced or sent anywhere else.",
            color = c.text,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun PodcastIndexGetKeyRow() {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    val uriHandler = LocalUriHandler.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(14.dp))
            .clickable { uriHandler.openUri(PODCAST_INDEX_API_URL) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Get a free Podcast Index key", color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                "Opens podcastindex.org in your browser",
                color = c.textMute,
                fontSize = 11.5.sp,
            )
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(r.pill))
                .background(c.purple)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text("Open", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun CredentialField(
    value: String,
    onValueChange: (String) -> Unit,
    connected: Boolean,
    verifying: Boolean,
    placeholder: String,
    masked: Boolean,
    testTag: String,
    errorMessage: String? = null,
) {
    val c = LocalKofipodColors.current
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            enabled = !verifying,
            visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
            placeholder = {
                Text(
                    placeholder,
                    color = c.textMute,
                    fontSize = 13.sp,
                )
            },
            keyboardOptions =
                KeyboardOptions(
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                ),
            modifier = Modifier.fillMaxWidth().testTag(testTag),
            colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = c.surface,
                    unfocusedContainerColor = c.surface,
                    disabledContainerColor = c.surfaceAlt,
                    focusedTextColor = c.text,
                    unfocusedTextColor = c.text,
                    cursorColor = c.pink,
                    focusedIndicatorColor = c.purple,
                    unfocusedIndicatorColor = c.border,
                ),
        )
        if (errorMessage != null) {
            Spacer(Modifier.height(6.dp))
            Text(errorMessage, color = c.pink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PodcastIndexConnectedFooter(onDisconnect: () -> Unit) {
    val c = LocalKofipodColors.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KPIcon(name = KPIconName.Check, color = c.purple, size = 20.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Connected", color = c.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "Podcast Index API credentials saved",
                    color = c.textMute,
                    fontSize = 11.5.sp,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        KPButton(
            label = "Disconnect",
            onClick = onDisconnect,
            style = KPButtonStyle.Outline,
            modifier = Modifier.fillMaxWidth().testTag("piDisconnectButton"),
        )
    }
}

@Composable
private fun PodcastIndexDisconnectConfirmDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val c = LocalKofipodColors.current
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = c.surface,
        title = { Text("Disconnect Podcast Index?", color = c.text, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "Your saved key and secret will be removed from this device. " +
                    "Podcast search will stop working until you add a key again.",
                color = c.textMute,
                fontSize = 13.sp,
            )
        },
        confirmButton = {
            Text(
                "Disconnect",
                color = c.pink,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onConfirm() }.padding(8.dp),
            )
        },
        dismissButton = {
            Text(
                "Cancel",
                color = c.textMute,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onCancel() }.padding(8.dp),
            )
        },
    )
}
