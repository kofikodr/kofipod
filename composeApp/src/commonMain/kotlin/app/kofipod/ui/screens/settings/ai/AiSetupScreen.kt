// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.settings.ai

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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ai.GeminiModel
import app.kofipod.ui.primitives.KPButton
import app.kofipod.ui.primitives.KPButtonStyle
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.primitives.SectionLabel
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii
import org.koin.compose.viewmodel.koinViewModel

private const val GEMINI_KEY_URL = "https://aistudio.google.com/app/apikey"

@Composable
fun AiSetupScreen(
    onBack: () -> Unit,
    viewModel: AiSetupViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
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
                "AI features",
                color = c.text,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 26.sp,
            )
        }

        SectionLabel("How this works")
        DisclosureCard()

        SectionLabel("Get a key")
        GetKeyRow()

        SectionLabel("Your Gemini API key")
        ApiKeyField(
            value = state.pasteValue,
            onValueChange = viewModel::onPasteChange,
            connected = state.connected,
            verifying = state.verifying,
            errorMessage = state.errorMessage,
        )

        SectionLabel("Model")
        ModelPicker(selected = state.model, onSelect = viewModel::setModel)

        Spacer(Modifier.height(24.dp))
        if (state.connected) {
            ConnectedFooter(
                model = state.model,
                onDisconnect = viewModel::requestDisconnect,
            )
        } else {
            KPButton(
                label = if (state.verifying) "Verifying…" else "Connect",
                onClick = { if (!state.verifying) viewModel.connect() },
                style = KPButtonStyle.PrimaryPink,
                modifier = Modifier.fillMaxWidth().testTag("aiConnectButton"),
            )
        }
    }

    if (state.showDisconnectConfirm) {
        DisconnectConfirmDialog(
            onCancel = viewModel::cancelDisconnect,
            onConfirm = viewModel::confirmDisconnect,
        )
    }
}

@Composable
private fun DisclosureCard() {
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
            "Kofipod doesn't run AI itself. You bring your own free Gemini API key from " +
                "Google AI Studio, and Kofipod uses it on your device to summarise episodes.",
            color = c.text,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "When you tap Generate on an episode, the audio is uploaded to Google and the " +
                "summary comes back to your device. Your key stays only on this phone — it is " +
                "never synced or sent anywhere else.",
            color = c.text,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(8.dp))
        val warning =
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(
                        "Don't paste sensitive content into AI features. On Google's free tier " +
                            "your prompts may be used to improve Google's models.",
                    )
                }
            }
        Text(warning, color = c.text, fontSize = 12.5.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun GetKeyRow() {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    val uriHandler = LocalUriHandler.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(14.dp))
            .clickable { uriHandler.openUri(GEMINI_KEY_URL) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Get a free Gemini API key", color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                "Opens Google AI Studio in your browser",
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
private fun ApiKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    connected: Boolean,
    verifying: Boolean,
    errorMessage: String?,
) {
    val c = LocalKofipodColors.current
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            enabled = !verifying,
            placeholder = {
                Text(
                    if (connected) "Connected — paste a new key to replace it" else "Paste your key",
                    color = c.textMute,
                    fontSize = 13.sp,
                )
            },
            keyboardOptions =
                KeyboardOptions(
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                ),
            modifier = Modifier.fillMaxWidth().testTag("aiKeyField"),
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
private fun ModelPicker(
    selected: GeminiModel,
    onSelect: (GeminiModel) -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.pill))
            .background(c.surfaceAlt)
            .border(1.dp, c.border, RoundedCornerShape(r.pill))
            .padding(4.dp),
    ) {
        GeminiModel.entries.forEach { model ->
            val active = model == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(r.pill))
                    .background(if (active) c.purple else Color.Transparent)
                    .clickable { onSelect(model) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    model.displayName,
                    color = if (active) Color.White else c.text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun ConnectedFooter(
    model: GeminiModel,
    onDisconnect: () -> Unit,
) {
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
                    "Model: ${model.displayName}",
                    color = c.textMute,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        KPButton(
            label = "Disconnect",
            onClick = onDisconnect,
            style = KPButtonStyle.Outline,
            modifier = Modifier.fillMaxWidth().testTag("aiDisconnectButton"),
        )
    }
}

@Composable
private fun DisconnectConfirmDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val c = LocalKofipodColors.current
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = c.surface,
        title = { Text("Disconnect Gemini?", color = c.text, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "Your saved key will be removed from this device. AI features will be hidden " +
                    "until you connect again.",
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
