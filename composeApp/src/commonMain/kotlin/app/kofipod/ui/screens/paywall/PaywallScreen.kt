// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.paywall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel

private val PAYWALL_FEATURES =
    listOf(
        "Snippets — share clips as MP4 or MP3",
        "Bookmarks with notes",
        "Transcript & summary search",
        "Markdown / Obsidian / Readwise export",
        "Coming free for Pro buyers in v1.1: Silence Skip, Smart Playlists, Notion export",
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallSheet(
    triggerKey: String,
    viewModel: PaywallViewModel = koinViewModel(),
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.dismiss()
            onDismiss()
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
        ) {
            Text("Kofipod Pro", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "One-time purchase. No subscription. No ads.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(20.dp))
            PAYWALL_FEATURES.forEach { feature ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("• ", fontWeight = FontWeight.SemiBold)
                    Text(feature, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = viewModel::purchaseIndividual,
                enabled = state.mode == PaywallMode.Idle,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Kofipod Pro — \$12.99")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = viewModel::purchaseFamily,
                enabled = state.mode == PaywallMode.Idle,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Family (up to 5) — \$19.99")
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = viewModel::restore,
                    enabled = state.mode == PaywallMode.Idle,
                ) {
                    Text(if (state.mode == PaywallMode.Restoring) "Restoring…" else "Restore Purchase")
                }
                TextButton(onClick = {
                    viewModel.dismiss()
                    onDismiss()
                }) {
                    Text("Maybe later")
                }
            }

            val error = state.errorMessage
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
