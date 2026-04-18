// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ui.theme.KofipodThemeMode
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsScreen(
    onOpenScheduler: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("Settings", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp)
        Spacer(Modifier.height(24.dp))

        Section("Theme")
        ThemeRow(state.themeMode, viewModel::setTheme)

        Section("Daily episode check")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Checks ~once a day", color = c.text, fontWeight = FontWeight.Medium)
                Text(
                    "Battery-aware; on Wi-Fi while charging.",
                    color = c.textMute,
                    fontSize = 12.sp,
                )
            }
            Switch(
                checked = state.dailyCheck,
                onCheckedChange = viewModel::setDailyCheck,
                modifier = Modifier.testTag("dailyCheckSwitch"),
            )
        }
        Text(
            "About the scheduler →",
            color = c.pink,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp).clickable { onOpenScheduler() },
        )

        Section("Downloads cap")
        Text(
            formatBytes(state.storageCapBytes),
            color = c.purple,
            fontWeight = FontWeight.Bold,
        )
        Slider(
            value = state.storageCapBytes.toFloat(),
            valueRange = MIN_CAP_BYTES.toFloat()..MAX_CAP_BYTES.toFloat(),
            onValueChange = { viewModel.setCap(it.toLong()) },
            modifier = Modifier.testTag("storageCapSlider"),
        )

        Section("Skip durations")
        SkipRow(
            label = "Back",
            seconds = state.skipBack,
            onChange = viewModel::setSkipBack,
            options = listOf(5, 10, 15, 30),
        )
        Spacer(Modifier.height(8.dp))
        SkipRow(
            label = "Forward",
            seconds = state.skipForward,
            onChange = viewModel::setSkipForward,
            options = listOf(15, 30, 45, 60),
        )

        Section("About")
        Text("Kofipod 0.1.0", color = c.text, fontWeight = FontWeight.Medium)
        Text("GPL-3.0-or-later", color = c.textMute, fontSize = 12.sp)
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Section(title: String) {
    val c = LocalKofipodColors.current
    Spacer(Modifier.height(20.dp))
    Text(
        title.uppercase(),
        color = c.textMute,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ThemeRow(current: KofipodThemeMode, onSelect: (KofipodThemeMode) -> Unit) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KofipodThemeMode.entries.forEach { m ->
            val selected = m == current
            Box(
                Modifier
                    .clip(RoundedCornerShape(r.pill))
                    .background(if (selected) c.purple else c.purpleTint)
                    .clickable { onSelect(m) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = m.name,
                    color = if (selected) c.surface else c.text,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun SkipRow(
    label: String,
    seconds: Int,
    onChange: (Int) -> Unit,
    options: List<Int>,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.text, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { value ->
                val selected = value == seconds
                Box(
                    Modifier
                        .clip(RoundedCornerShape(r.pill))
                        .background(if (selected) c.pink else c.purpleTint)
                        .clickable { onChange(value) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        "${value}s",
                        color = if (selected) c.surface else c.text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

private const val MIN_CAP_BYTES: Long = 512L * 1024 * 1024
private const val MAX_CAP_BYTES: Long = 20L * 1024 * 1024 * 1024

private fun formatBytes(b: Long): String {
    val gb = b.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        val whole = gb.toInt()
        val frac = ((gb - whole) * 10).toInt()
        "$whole.$frac GB"
    } else {
        "${b / (1024 * 1024)} MB"
    }
}
