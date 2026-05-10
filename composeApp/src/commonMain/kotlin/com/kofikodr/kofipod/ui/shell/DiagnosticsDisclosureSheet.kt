// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.shell

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
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsDisclosureSheet(
    visible: Boolean,
    crashAvailable: Boolean,
    usageAvailable: Boolean,
    onAcknowledge: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (!visible) return
    if (!crashAvailable && !usageAvailable) return
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
                disclosureBody(crashAvailable, usageAvailable),
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

internal fun disclosureBody(
    crashAvailable: Boolean,
    usageAvailable: Boolean,
): String {
    require(crashAvailable || usageAvailable) {
        "disclosureBody is only meaningful when at least one channel is available; " +
            "callers must gate on DiagnosticsCapabilities first."
    }
    val what =
        when {
            crashAvailable && usageAvailable ->
                "anonymous crash reports and usage counts"
            crashAvailable -> "anonymous crash reports"
            else -> "anonymous usage counts"
        }
    val euLine = if (usageAvailable) " Usage data is hosted in the EU." else ""
    return "Kofipod sends $what so the developer can fix bugs and prioritize " +
        "features. No personal information, no tracking across apps.$euLine " +
        "You can turn ${if (crashAvailable && usageAvailable) "either" else "it"} off " +
        "in Settings → Privacy & Diagnostics at any time."
}
