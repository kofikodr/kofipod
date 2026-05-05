// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ui.theme.LocalKofipodColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsDisclosureSheet(
    visible: Boolean,
    onAcknowledge: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (!visible) return
    val c = LocalKofipodColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onAcknowledge,
        sheetState = sheetState,
        containerColor = c.surface,
    ) {
        Column(Modifier.padding(24.dp).fillMaxWidth()) {
            Text(
                "Help improve Kofipod",
                color = c.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Kofipod sends anonymous crash reports and usage counts so the developer " +
                    "can fix bugs and prioritize features. No personal information, no " +
                    "tracking across apps. You can turn either off in Settings → Privacy " +
                    "& Diagnostics at any time.",
                color = c.textSoft,
            )
            Spacer(Modifier.height(24.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(onClick = {
                    onAcknowledge()
                    onOpenSettings()
                }) { Text("Open Settings") }
                Button(onClick = onAcknowledge) { Text("Got it") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
