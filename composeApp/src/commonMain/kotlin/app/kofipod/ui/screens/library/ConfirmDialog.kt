// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii

@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    cancelLabel: String = "Cancel",
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(r.lg))
                .background(c.surface)
                .padding(20.dp),
        ) {
            Text(title, color = c.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Text(message, color = c.textMute, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            Row {
                Text(
                    cancelLabel,
                    color = c.textSoft,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onDismiss() }.padding(12.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    confirmLabel,
                    color = c.danger,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onConfirm() }.padding(12.dp),
                )
            }
        }
    }
}
