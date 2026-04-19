// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.splash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ui.theme.LocalKofipodColors
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SplashScreen(
    onContinue: (goOnboarding: Boolean) -> Unit,
    viewModel: SplashViewModel = koinViewModel(),
) {
    val c = LocalKofipodColors.current

    LaunchedEffect(Unit) {
        delay(1500L)
        onContinue(viewModel.needsOnboarding)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.purple),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(180.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.pink),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(160.dp)) {
                    val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                    // Outer white stroke ring
                    drawCircle(
                        color = Color.White.copy(alpha = 0.35f),
                        radius = size.minDimension / 2f,
                        center = center,
                        style = Stroke(width = 8.dp.toPx()),
                    )
                    // Solid white inner disc (~56dp)
                    drawCircle(
                        color = Color.White,
                        radius = 28.dp.toPx(),
                        center = center,
                    )
                    // Small purple dot (~18dp)
                    drawCircle(
                        color = c.purple,
                        radius = 9.dp.toPx(),
                        center = center,
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(
                "Kofipod",
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 44.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Your podcasts, your way.",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            "v 0.1 · GPL-3.0",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
        )
    }
}
