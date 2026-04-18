// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ui.primitives.KPButton
import app.kofipod.ui.theme.LocalKofipodColors
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun OnboardingScreen(
    onContinue: () -> Unit,
    viewModel: OnboardingViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current

    LaunchedEffect(state.done) {
        if (state.done) onContinue()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Top,
    ) {
        Spacer(Modifier.height(80.dp))
        Text("Kofipod", color = c.purple, fontWeight = FontWeight.ExtraBold, fontSize = 48.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "A cheerful, purple-forward podcast companion.",
            color = c.text,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(40.dp))
        KPButton(
            label = if (state.signingIn) "Signing in…" else "Sign in with Google",
            onClick = viewModel::signIn,
        )
        Spacer(Modifier.height(16.dp))
        state.error?.let {
            Text(it, color = c.danger, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Skip for now →",
                    color = c.textSoft,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { viewModel.skip() }.padding(12.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "You can sign in later from Settings.",
                    color = c.textMute,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
