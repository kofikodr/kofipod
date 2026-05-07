// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.export

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.pkm.PkmDestination
import app.kofipod.pkm.PkmExportCoordinator
import app.kofipod.ui.theme.LocalKofipodColors
import org.koin.compose.koinInject

/**
 * Pro "export to PKM" sheet. Hoisted at AppShell so any entry point (snippet,
 * bookmark, AI summary) can pop it without owning the host. Self-gates on
 * [PkmExportCoordinator.pendingRequest] — when null, the function returns
 * before composing the sheet.
 *
 * The coordinator clears `pendingRequest` after the export coroutine returns,
 * so the sheet hides itself once the user picks a destination (no manual
 * dismiss call needed on the success path).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownExportSheet() {
    val coordinator: PkmExportCoordinator = koinInject()
    val request by coordinator.pendingRequest.collectAsState()
    val current = request ?: return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val c = LocalKofipodColors.current

    ModalBottomSheet(
        onDismissRequest = { coordinator.dismiss() },
        sheetState = sheetState,
        containerColor = c.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                "Export as Markdown",
                color = c.text,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(12.dp))

            // TODO(slice6-task14): replaced by ExportActionSheet which adds Obsidian/Readwise rows.
            SinkRow(
                title = "Copy to clipboard",
                subtitle = "Plain Markdown text",
                onClick = { coordinator.execute(current, PkmDestination.Clipboard) },
            )
            Spacer(Modifier.height(4.dp))
            SinkRow(
                title = "Share as file…",
                subtitle = "Sends a .md file via the system share sheet",
                onClick = { coordinator.execute(current, PkmDestination.ShareFile) },
            )

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SinkRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(vertical = 12.dp),
    ) {
        Text(title, color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, color = c.textMute, fontSize = 13.sp)
    }
}
