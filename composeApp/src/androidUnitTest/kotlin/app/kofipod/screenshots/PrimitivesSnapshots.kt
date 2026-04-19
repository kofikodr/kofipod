// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.kofipod.ui.primitives.KPBadge
import app.kofipod.ui.primitives.KPButton
import app.kofipod.ui.primitives.KPButtonStyle
import app.kofipod.ui.primitives.KPCard
import app.kofipod.ui.theme.KofipodTheme
import app.kofipod.ui.theme.KofipodThemeMode
import app.kofipod.ui.theme.LocalKofipodColors
import org.junit.Rule
import org.junit.Test

class PrimitivesSnapshots {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = DeviceConfig.PIXEL_5,
            theme = "android:Theme.Material.Light.NoActionBar",
        )

    @Test
    fun primitives_light() =
        paparazzi.snapshot {
            ThemedPreview(KofipodThemeMode.Light) { Primitives() }
        }

    @Test
    fun primitives_dark() =
        paparazzi.snapshot {
            ThemedPreview(KofipodThemeMode.Dark) { Primitives() }
        }
}

@Composable
private fun ThemedPreview(
    mode: KofipodThemeMode,
    content: @Composable () -> Unit,
) {
    KofipodTheme(mode) {
        Column(
            Modifier
                .fillMaxSize()
                .background(LocalKofipodColors.current.bg)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}

@Composable
private fun Primitives() {
    KPButton(label = "Primary pink", onClick = {}, style = KPButtonStyle.PrimaryPink)
    KPButton(label = "Secondary purple", onClick = {}, style = KPButtonStyle.SecondaryPurple)
    KPButton(label = "Outline", onClick = {}, style = KPButtonStyle.Outline)
    KPBadge(label = "Playing")
    KPBadge(label = "Downloaded")
    KPCard { KPButton(label = "Card action", onClick = {}, style = KPButtonStyle.PrimaryPink) }
}
