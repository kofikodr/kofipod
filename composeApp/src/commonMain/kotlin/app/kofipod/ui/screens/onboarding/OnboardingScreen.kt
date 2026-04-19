// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.primitives.KofipodMark
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(56.dp))
        // Brand lockup: mark + wordmark on one line.
        Row(verticalAlignment = Alignment.CenterVertically) {
            KofipodMark(modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                "Kofipod",
                color = c.text,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 26.sp,
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(
            "Your podcasts,",
            color = c.text,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 36.sp,
            lineHeight = 40.sp,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text("your way", color = c.purple, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp, lineHeight = 40.sp)
            Text(".", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp)
            Text(".", color = c.pink, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Search the open podcast index. Build your own lists. " +
                "Backs up to your Google account automatically.",
            color = c.textSoft,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(28.dp))
        FeatureRow(icon = KPIconName.Search, text = "Search by title or person")
        Spacer(Modifier.height(10.dp))
        FeatureRow(icon = KPIconName.Folder, text = "Library + playback positions backed up automatically")
        Spacer(Modifier.height(10.dp))
        FeatureRow(icon = KPIconName.Download, text = "On-demand & daily auto-downloads")
        Spacer(Modifier.height(36.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(r.pill))
                .background(c.pink)
                .clickable { viewModel.onContinue(onFinished) }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Get started",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun FeatureRow(
    icon: KPIconName,
    text: String,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.md))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(r.md))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(c.purpleTint),
            contentAlignment = Alignment.Center,
        ) {
            KPIcon(name = icon, color = c.purple, size = 16.dp)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, color = c.text, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}
