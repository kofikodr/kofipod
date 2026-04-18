// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ui.primitives.KPButton
import app.kofipod.ui.theme.LocalKofipodColors

@Composable
private fun Placeholder(title: String, subtitle: String = "Coming soon.") {
    val c = LocalKofipodColors.current
    Column(Modifier.fillMaxSize().background(c.bg).padding(24.dp)) {
        Spacer(Modifier.height(40.dp))
        Text(title, color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = c.textMute)
    }
}

@Composable
fun OnboardingScreen(onContinue: () -> Unit) {
    val c = LocalKofipodColors.current
    Column(Modifier.fillMaxSize().background(c.bg).padding(24.dp)) {
        Spacer(Modifier.height(80.dp))
        Text("Kofipod", color = c.purple, fontWeight = FontWeight.ExtraBold, fontSize = 42.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "A cheerful, purple-forward podcast companion.",
            color = c.text,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(40.dp))
        KPButton(label = "Sign in with Google", onClick = onContinue)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Skip for now →", color = c.textSoft, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable fun DownloadsScreen() = Placeholder("Downloads")
@Composable fun SchedulerInfoScreen(onBack: () -> Unit) = Placeholder("Scheduler", "Battery-aware daily check.")
