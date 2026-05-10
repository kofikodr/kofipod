// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.export

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.pkm.PkmDestination
import app.kofipod.pkm.PkmExportCoordinator
import app.kofipod.pkm.connections.ConnectionKind
import app.kofipod.pkm.connections.PkmConnectionRepository
import app.kofipod.ui.theme.LocalKofipodColors

/**
 * Pro "export to PKM" sheet with multi-destination dispatch. Hoisted at AppShell
 * so any entry point (snippet, bookmark, AI summary) can pop it without owning
 * the host. Self-gates on [PkmExportCoordinator.pendingRequest] — when null,
 * the function returns before composing the sheet.
 *
 * Users can select any combination of:
 * - Clipboard (always available)
 * - Share file (always available)
 * - Obsidian (only if a connection exists)
 * - Readwise (only if a connection exists)
 *
 * The "Export" button dispatches one [coordinator.execute] per selected destination.
 * After dispatch, the sheet dismisses and selection resets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportActionSheet(
    coordinator: PkmExportCoordinator,
    connections: PkmConnectionRepository,
    onNavigateToConnections: () -> Unit,
) {
    val pending by coordinator.pendingRequest.collectAsState()
    val rows by connections.observeAll().collectAsState(initial = emptyList())
    val request = pending ?: return

    var selected by remember(request) { mutableStateOf(setOf<PkmDestination>()) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val c = LocalKofipodColors.current

    ModalBottomSheet(
        onDismissRequest = { coordinator.dismiss() },
        sheetState = sheetState,
        containerColor = c.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                "Export to…",
                color = c.text,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(12.dp))

            DestinationToggleRow(
                destination = PkmDestination.Clipboard,
                title = "Copy as Markdown",
                enabled = true,
                selected = selected,
                onToggle = { selected = it },
            )
            Spacer(Modifier.height(4.dp))
            DestinationToggleRow(
                destination = PkmDestination.ShareFile,
                title = "Share as .md file",
                enabled = true,
                selected = selected,
                onToggle = { selected = it },
            )

            val obsConnected = rows.any { it.kind == ConnectionKind.Obsidian }
            DestinationToggleRow(
                destination = PkmDestination.Obsidian,
                title = "Save to Obsidian",
                enabled = obsConnected,
                selected = selected,
                onToggle = { selected = it },
            )

            val rwConnected = rows.any { it.kind == ConnectionKind.Readwise }
            DestinationToggleRow(
                destination = PkmDestination.Readwise,
                title = "Send to Readwise",
                enabled = rwConnected,
                selected = selected,
                onToggle = { selected = it },
            )

            Spacer(Modifier.height(12.dp))

            if (!obsConnected && !rwConnected) {
                TextButton(
                    onClick = {
                        coordinator.dismiss()
                        onNavigateToConnections()
                    },
                ) {
                    Text("Add destinations…")
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    selected.forEach { coordinator.execute(request, it) }
                    coordinator.dismiss()
                    selected = emptySet()
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val count = selected.size
                Text("Export $count destination${if (count == 1) "" else "s"}")
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DestinationToggleRow(
    destination: PkmDestination,
    title: String,
    enabled: Boolean,
    selected: Set<PkmDestination>,
    onToggle: (Set<PkmDestination>) -> Unit,
) {
    val c = LocalKofipodColors.current
    val isSelected = destination in selected

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.4f)
                .clickable(enabled = enabled) {
                    onToggle(
                        if (isSelected) {
                            selected - destination
                        } else {
                            selected + destination
                        },
                    )
                }
                .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = null,
            enabled = enabled,
        )
        Text(
            title,
            color = c.text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
