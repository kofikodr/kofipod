// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.kofipod.ui.theme.KofipodColors
import app.kofipod.ui.theme.KofipodTheme
import app.kofipod.ui.theme.KofipodThemeMode
import app.kofipod.ui.theme.LocalKofipodColors
import org.junit.Rule
import org.junit.Test

class TokensSnapshots {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = DeviceConfig.PIXEL_5,
            theme = "android:Theme.Material.Light.NoActionBar",
        )

    @Test
    fun tokens_light() =
        paparazzi.snapshot {
            KofipodTheme(KofipodThemeMode.Light) { Palette() }
        }

    @Test
    fun tokens_dark() =
        paparazzi.snapshot {
            KofipodTheme(KofipodThemeMode.Dark) { Palette() }
        }
}

@Composable
private fun Palette() {
    val c = LocalKofipodColors.current
    Column(
        Modifier.fillMaxSize().background(c.bg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Palette", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
        SwatchGrid(c)
        Spacer(Modifier.height(4.dp))
        Text("Text", color = c.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text("Primary text", color = c.text, fontWeight = FontWeight.Medium)
        Text("Soft text", color = c.textSoft, fontWeight = FontWeight.Medium)
        Text("Muted text", color = c.textMute, fontSize = 12.sp)
    }
}

@Composable
private fun SwatchGrid(c: KofipodColors) {
    val entries: List<Pair<String, Color>> =
        listOf(
            "purple" to c.purple,
            "purpleDeep" to c.purpleDeep,
            "purpleSoft" to c.purpleSoft,
            "purpleTint" to c.purpleTint,
            "pink" to c.pink,
            "pinkSoft" to c.pinkSoft,
            "success" to c.success,
            "warn" to c.warn,
            "danger" to c.danger,
            "border" to c.border,
            "surface" to c.surface,
            "surfaceAlt" to c.surfaceAlt,
        )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.chunked(3).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { (name, color) -> Swatch(name, color) }
            }
        }
    }
}

@Composable
private fun Swatch(
    name: String,
    color: Color,
) {
    val c = LocalKofipodColors.current
    Column(Modifier.width(116.dp)) {
        Box(
            Modifier
                .size(height = 56.dp, width = 116.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color),
        )
        Text(name, color = c.text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}
