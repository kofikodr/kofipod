// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ui.primitives.SectionLabel
import app.kofipod.ui.theme.LocalKofipodColors

@Composable
fun PrivacyDiagnosticsSection(
    crashesEnabled: Boolean,
    usageEnabled: Boolean,
    crashAvailable: Boolean,
    usageAvailable: Boolean,
    onCrashesEnabledChange: (Boolean) -> Unit,
    onUsageEnabledChange: (Boolean) -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!crashAvailable && !usageAvailable) return
    val c = LocalKofipodColors.current
    Column(modifier.fillMaxWidth()) {
        SectionLabel("Privacy & Diagnostics", topSpacing = 22.dp)
        Spacer(Modifier.height(8.dp))

        if (crashAvailable) {
            DiagnosticsToggleRow(
                tag = "diagnostics.crashes",
                title = "Send crash reports",
                subtitle = "Help fix bugs by sharing anonymous crash details when the app crashes. No personal information.",
                checked = crashesEnabled,
                onCheckedChange = onCrashesEnabledChange,
                disclosureLines =
                    listOf(
                        "Stack trace",
                        "Exception class and message (URLs scrubbed)",
                        "OS version, device model (e.g. \"Pixel 7\")",
                        "App version, locale",
                    ),
            )
        }

        if (crashAvailable && usageAvailable) {
            Spacer(Modifier.height(12.dp))
        }

        if (usageAvailable) {
            DiagnosticsToggleRow(
                tag = "diagnostics.usage",
                title = "Share anonymous usage data",
                subtitle =
                    "Help prioritize features by sharing counts of how often " +
                        "they're used. No identifiers, no IP address stored. " +
                        "Hosted in the EU.",
                checked = usageEnabled,
                onCheckedChange = onUsageEnabledChange,
                disclosureLines =
                    listOf(
                        "Event name (e.g. \"search_performed\")",
                        "Event properties (fixed enum values)",
                        "App version, OS version, locale",
                        "No client identifier ever sent",
                    ),
            )
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Read the privacy policy ›",
            color = c.purple,
            modifier =
                Modifier
                    .testTag("diagnostics.privacyPolicy")
                    .clickable { onOpenPrivacyPolicy() }
                    .padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun DiagnosticsToggleRow(
    tag: String,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    disclosureLines: List<String>,
) {
    val c = LocalKofipodColors.current
    var expanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = c.text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = c.textMute,
                    fontSize = 11.5.sp,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.testTag(tag),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "What's sent?",
            color = c.text,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier =
                Modifier
                    .testTag("$tag.disclosureToggle")
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
        )
        if (expanded) {
            Column(Modifier.padding(start = 8.dp)) {
                disclosureLines.forEach { line ->
                    Text(
                        "• $line",
                        color = c.textMute,
                        fontSize = 11.5.sp,
                    )
                }
            }
        }
    }
}
