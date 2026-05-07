// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.pkm.connections.ConnectionKind
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ConnectionsScreen(
    onBack: () -> Unit,
    viewModel: ConnectionsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val c = LocalKofipodColors.current

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        ConnectionsTopBar(onBack = onBack)

        Spacer(Modifier.height(8.dp))

        state.rows.forEach { row ->
            ConnectionRowCard(
                row = row,
                onConnect = {
                    when (row.kind) {
                        ConnectionKind.Obsidian -> { /* picker launched via rememberObsidianFolderPicker below */ }
                        ConnectionKind.Readwise -> viewModel.openReadwiseDialog()
                        else -> Unit
                    }
                },
                onObsidianConnect = viewModel::connectObsidian,
                onDisconnect = { viewModel.disconnect(row.kind) },
            )
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(16.dp))
    }

    if (state.readwiseDialogOpen) {
        ReadwiseDialog(
            state = state,
            onTokenChange = viewModel::onReadwiseTokenChange,
            onConnect = viewModel::connectReadwise,
            onDismiss = viewModel::closeReadwiseDialog,
        )
    }
}

@Composable
private fun ConnectionsTopBar(onBack: () -> Unit) {
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(c.surface)
                .border(1.dp, c.border, CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(name = KPIconName.Back, color = c.text, size = 16.dp, strokeWidth = 2f)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "Connections",
            color = c.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

@Composable
private fun ConnectionRowCard(
    row: ConnectionRow,
    onConnect: () -> Unit,
    onObsidianConnect: (treeUri: String) -> Unit,
    onDisconnect: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current

    val obsidianPicker =
        if (row.kind == ConnectionKind.Obsidian) {
            rememberObsidianFolderPicker(onPicked = onObsidianConnect)
        } else {
            null
        }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(r.md))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(r.md))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.displayName,
                color = c.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            val subtitle = rowSubtitle(row)
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = c.textMute,
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        when {
            row.kind == ConnectionKind.Markdown -> {
                // Markdown is always connected — no action button.
                Text(
                    "Built-in",
                    color = c.success,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            row.status is ConnectionStatus.Connected -> {
                ActionChip(
                    label = "Disconnect",
                    containerColor = c.bgSubtle,
                    textColor = c.danger,
                    onClick = onDisconnect,
                )
            }
            row.status is ConnectionStatus.Error -> {
                ActionChip(
                    label = "Reconnect",
                    containerColor = c.purpleTint,
                    textColor = c.purple,
                    onClick = {
                        if (obsidianPicker != null) obsidianPicker() else onConnect()
                    },
                )
            }
            else -> {
                ActionChip(
                    label = "Connect",
                    containerColor = c.purpleTint,
                    textColor = c.purple,
                    onClick = {
                        if (obsidianPicker != null) obsidianPicker() else onConnect()
                    },
                )
            }
        }
    }
}

private fun rowSubtitle(row: ConnectionRow): String? {
    val lastSync = row.lastSyncAtMs
    return when (val s = row.status) {
        is ConnectionStatus.Connected -> {
            val detail = s.detail
            if (detail != null) {
                "…/$detail"
            } else if (lastSync != null) {
                "Last synced"
            } else {
                null
            }
        }
        is ConnectionStatus.Error -> s.message
        ConnectionStatus.Disconnected -> null
    }
}

@Composable
private fun ActionChip(
    label: String,
    containerColor: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ReadwiseDialog(
    state: ConnectionsUiState,
    onTokenChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalKofipodColors.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect Readwise") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.readwiseTokenInput,
                    onValueChange = onTokenChange,
                    label = { Text("Readwise API token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Get your token at readwise.io/access_token",
                    color = c.textMute,
                    fontSize = 12.sp,
                )
                if (state.readwiseValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).align(Alignment.CenterHorizontally),
                        strokeWidth = 2.dp,
                        color = c.purple,
                    )
                }
                if (state.readwiseError != null) {
                    Text(
                        state.readwiseError,
                        color = c.danger,
                        fontSize = 13.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConnect,
                enabled = !state.readwiseValidating,
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
