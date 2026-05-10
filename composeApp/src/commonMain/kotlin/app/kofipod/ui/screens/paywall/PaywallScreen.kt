// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.paywall

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.theme.LocalKofipodColors
import org.koin.compose.viewmodel.koinViewModel

private data class PaywallFeature(
    val icon: KPIconName,
    val title: String,
    val sub: String,
    val version: String,
)

private val PAYWALL_FEATURES =
    listOf(
        PaywallFeature(KPIconName.Share, "Snip & share clips", "Trim any moment to MP4 or MP3.", "1.0"),
        PaywallFeature(KPIconName.Bookmark, "Bookmark with notes", "Mark moments, jot a line of context.", "1.0"),
        PaywallFeature(KPIconName.Folder, "PKM Connections", "Push to Obsidian, Readwise, Notion, Markdown.", "1.0"),
        PaywallFeature(KPIconName.Search, "Library-wide search", "Find anything across episodes, summaries, snips.", "1.0"),
        PaywallFeature(KPIconName.SkipForward, "Silence skip", "Auto-trim awkward gaps.", "1.1"),
        PaywallFeature(KPIconName.Sparkle, "Smart Playlists", "Build queues from rules.", "1.1"),
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
    val c = LocalKofipodColors.current

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.dismiss()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = c.surface,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
        ) {
            PaywallHero()
            Spacer(Modifier.height(14.dp))
            FeatureCard()
            Spacer(Modifier.height(16.dp))
            PaywallErrorBanner(state.errorMessage)
            PaywallCta(
                title = "Kofipod Pro",
                price = "\$12.99",
                sub = "One-time · for you",
                launching = state.mode == PaywallMode.Launching,
                enabled = state.mode == PaywallMode.Idle,
                onClick = viewModel::purchaseIndividual,
            )
            Spacer(Modifier.height(10.dp))
            PaywallLinkRow(
                restoring = state.mode == PaywallMode.Restoring,
                onRestore = viewModel::restore,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Maybe later",
                color = c.textMute,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PaywallHero() {
    val c = LocalKofipodColors.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.horizontalGradient(listOf(c.purple, c.pink)),
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    "PRO",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp,
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(
                "Unlock when you tap a Pro feature",
                color = c.textMute,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Take notes that actually go somewhere.",
            color = c.text,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 28.sp,
        )
    }
}

@Composable
private fun FeatureCard() {
    val c = LocalKofipodColors.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(c.surfaceAlt)
                .border(1.dp, c.border, RoundedCornerShape(18.dp))
                .padding(8.dp),
    ) {
        PAYWALL_FEATURES.forEachIndexed { idx, feature ->
            ProFeatureRow(feature = feature, divider = idx > 0)
        }
    }
}

@Composable
private fun ProFeatureRow(
    feature: PaywallFeature,
    divider: Boolean,
) {
    val c = LocalKofipodColors.current
    val isFuture = feature.version == "1.1"
    if (divider) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(c.border),
        )
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.purpleTint),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(name = feature.icon, color = c.purple, size = 16.dp)
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                feature.title,
                color = c.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                feature.sub,
                color = c.textMute,
                fontSize = 11.5.sp,
                lineHeight = 14.sp,
            )
        }
        Box(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(
                        1.dp,
                        if (isFuture) c.border else c.purple.copy(alpha = 0.5f),
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                "v${feature.version}",
                color = if (isFuture) c.textMute else c.purple,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp,
            )
        }
    }
}

@Composable
private fun PaywallErrorBanner(message: String?) {
    if (message == null) return
    val c = LocalKofipodColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(c.danger.copy(alpha = 0.10f))
                .border(1.dp, c.danger.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(c.danger),
            contentAlignment = Alignment.Center,
        ) {
            Text("!", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
        }
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Billing unavailable", color = c.text, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
            Spacer(Modifier.height(1.dp))
            Text(message, color = c.textMute, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PaywallCta(
    title: String,
    price: String,
    sub: String,
    launching: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (enabled || launching) c.pink else c.pink.copy(alpha = 0.5f))
                // clickable BEFORE inner padding so the entire pink button is the touch target,
                // not just the area inside the 18dp text padding.
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                sub,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.5.sp,
            )
        }
        if (launching) {
            Text(
                "OPENING…",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.6.sp,
            )
        } else {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    price,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    "once",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun PaywallLinkRow(
    restoring: Boolean,
    onRestore: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (restoring) "Restoring…" else "Restore purchase",
            color = c.purple,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier =
                Modifier
                    .clickable(enabled = !restoring, role = Role.Button, onClick = onRestore)
                    .padding(vertical = 4.dp),
        )
    }
}
