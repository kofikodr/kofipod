// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofikodr.kofipod.config.AppInfo
import com.kofikodr.kofipod.data.repo.UpdateUiState
import com.kofikodr.kofipod.data.search.ItunesStorefront
import com.kofikodr.kofipod.diagnostics.DiagnosticsCapabilities
import com.kofikodr.kofipod.opml.OpmlAction
import com.kofikodr.kofipod.pro.BillingCapability
import com.kofikodr.kofipod.pro.ProEntitlement
import com.kofikodr.kofipod.pro.ProSource
import com.kofikodr.kofipod.ui.primitives.KPIcon
import com.kofikodr.kofipod.ui.primitives.KPIconName
import com.kofikodr.kofipod.ui.primitives.SectionLabel
import com.kofikodr.kofipod.ui.primitives.SettingRow
import com.kofikodr.kofipod.ui.theme.KofipodThemeMode
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import com.kofikodr.kofipod.ui.theme.LocalKofipodRadii
import com.kofikodr.kofipod.update.UpdaterCapability
import kotlinx.datetime.Clock
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

private const val MIN_CAP_BYTES: Long = 512L * 1024 * 1024
private const val MAX_CAP_BYTES: Long = 8L * 1024 * 1024 * 1024
private const val MIN_STREAM_CACHE_BYTES: Long = 128L * 1024 * 1024
private const val MAX_STREAM_CACHE_BYTES: Long = 2L * 1024 * 1024 * 1024

// Hidden reviewer-unlock affordance: 7 taps on the version row within
// REVIEWER_TAP_WINDOW_MS reveals a code-entry dialog. The window resets if the
// user pauses, so an accidental long-press / drift cannot accumulate over time.
private const val REVIEWER_TAPS_REQUIRED: Int = 7
private const val REVIEWER_TAP_WINDOW_MS: Long = 3_000L

@Composable
fun SettingsScreen(
    onOpenScheduler: () -> Unit,
    onOpenAiSetup: () -> Unit,
    onOpenConnections: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current

    // Reviewer-unlock tap state: counter resets if the gap between taps exceeds
    // REVIEWER_TAP_WINDOW_MS. Stored as plain Compose state — no need for
    // rememberSaveable since a config change cancels an in-progress 7-tap anyway.
    var reviewerTapCount by remember { mutableStateOf(0) }
    var reviewerLastTapMs by remember { mutableStateOf(0L) }
    var reviewerCodeDialogVisible by remember { mutableStateOf(false) }
    var storefrontPickerVisible by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 20.dp, bottom = 40.dp),
    ) {
        Text(
            "Settings",
            color = c.text,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 32.sp,
        )
        SectionLabel("Kofipod Pro", topSpacing = 22.dp)
        ProStatusCard(
            entitlement = state.proEntitlement,
            restoreInFlight = state.restoreInFlight,
            onUpgrade = viewModel::openPaywall,
            onRestore = viewModel::restorePurchase,
        )

        if (UpdaterCapability.enabled) {
            SectionLabel("App update", topSpacing = 22.dp)
            UpdateCard(
                update = state.update,
                action = state.updateAction,
                onCheck = viewModel::checkForUpdates,
                onDownload = viewModel::downloadUpdate,
                onInstall = viewModel::installUpdate,
                onDismiss = viewModel::dismissUpdate,
            )
        }

        SectionLabel("Library", topSpacing = 22.dp)
        val opmlAction = state.opmlAction
        val opmlIdle = opmlAction is OpmlAction.Idle || opmlAction is OpmlAction.Error
        SettingRow(
            icon = KPIconName.Library,
            title = "Import OPML",
            subtitle =
                when (opmlAction) {
                    OpmlAction.Importing -> "Importing — this can take a moment for large files"
                    is OpmlAction.Error -> opmlAction.message
                    else -> "Add subscriptions from another podcast app"
                },
            onClick = if (opmlIdle) viewModel::importOpml else null,
            trailing = {
                KPIcon(name = KPIconName.ChevronRight, color = c.textMute, size = 18.dp)
            },
        )
        Spacer(Modifier.height(8.dp))
        SettingRow(
            icon = KPIconName.Download,
            title = "Export OPML",
            subtitle =
                when (opmlAction) {
                    OpmlAction.Exporting -> "Saving…"
                    else -> "Save your subscriptions to a file"
                },
            onClick = if (opmlIdle) viewModel::exportOpml else null,
            trailing = {
                KPIcon(name = KPIconName.ChevronRight, color = c.textMute, size = 18.dp)
            },
        )

        SectionLabel("Backup", topSpacing = 22.dp)
        BackupSection(
            state = state,
            onChooseFolder = viewModel::chooseBackupFolder,
            onBackupNow = viewModel::backupNow,
            onRestore = viewModel::restoreFromBackup,
            onConfirmRestore = viewModel::confirmRestore,
            onCancelRestoreConfirm = viewModel::cancelRestoreConfirm,
        )

        SectionLabel("Connections", topSpacing = 22.dp)
        SettingRow(
            icon = KPIconName.Share,
            title = "Connections",
            subtitle = "Manage Obsidian, Readwise, and Markdown exports",
            onClick = { viewModel.tapConnections(onOpenConnections) },
            trailing = {
                KPIcon(name = KPIconName.ChevronRight, color = c.textMute, size = 18.dp)
            },
        )

        SectionLabel("Appearance", topSpacing = 22.dp)
        ThemeModeSelector(
            selected = state.themeMode,
            onSelect = viewModel::setTheme,
        )

        SectionLabel("Downloads", topSpacing = 22.dp)
        SettingRow(
            icon = KPIconName.Radar,
            title = "Daily check for new episodes",
            subtitle = "Runs about once a day while you have a network connection",
            trailing = {
                PinkSwitch(
                    checked = state.dailyCheck,
                    onCheckedChange = viewModel::setDailyCheck,
                    testTag = "dailyCheckSwitch",
                )
            },
        )
        Spacer(Modifier.height(8.dp))
        SettingRow(
            icon = KPIconName.Radar,
            title = "Download on Wi-Fi only",
            subtitle = "Cellular downloads are deferred until you're back on Wi-Fi",
            trailing = {
                PinkSwitch(
                    checked = state.wifiOnly,
                    onCheckedChange = viewModel::setWifiOnly,
                    testTag = "wifiOnlySwitch",
                )
            },
        )
        if (UpdaterCapability.enabled) {
            Spacer(Modifier.height(8.dp))
            SettingRow(
                icon = KPIconName.Download,
                title = "Check for app updates",
                subtitle = "Looks for newer Kofipod releases on GitHub during the daily check",
                trailing = {
                    PinkSwitch(
                        checked = state.autoUpdateCheck,
                        onCheckedChange = viewModel::setAutoUpdateCheck,
                        testTag = "autoUpdateCheckSwitch",
                    )
                },
            )
        }

        SectionLabel("Search", topSpacing = 22.dp)
        SettingRow(
            icon = KPIconName.Search,
            title = "Search storefront",
            subtitle = "Apple iTunes catalogue · ${state.itunesStorefront.label}",
            onClick = { storefrontPickerVisible = true },
            trailing = {
                KPIcon(name = KPIconName.ChevronRight, color = c.textMute, size = 18.dp)
            },
        )

        SectionLabel("AI features (optional)", topSpacing = 22.dp)
        SettingRow(
            icon = KPIconName.Pencil,
            title = if (state.aiConnected) "Gemini connected" else "Connect Gemini API key",
            subtitle =
                if (state.aiConnected) {
                    "Model: ${state.aiModel.displayName} · Tap to manage"
                } else {
                    "Optional. Enables on-device episode summaries with your own free Gemini key."
                },
            onClick = onOpenAiSetup,
            trailing = {
                KPIcon(name = KPIconName.ChevronRight, color = c.textMute, size = 18.dp)
            },
        )

        SectionLabel("Storage", topSpacing = 22.dp)
        MaxDownloadSizeCard(
            bytes = state.storageCapBytes,
            onChange = { viewModel.setCap(it) },
        )
        Spacer(Modifier.height(12.dp))
        PlaybackCacheCard(
            capBytes = state.streamCacheCapBytes,
            usedBytes = state.streamCacheUsedBytes,
            onChange = { viewModel.setStreamCacheCap(it) },
        )

        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        PrivacyDiagnosticsSection(
            crashesEnabled = state.crashesEnabled,
            usageEnabled = state.usageEnabled,
            crashAvailable = DiagnosticsCapabilities.crashReportingAvailable,
            usageAvailable = DiagnosticsCapabilities.usageTelemetryAvailable,
            onCrashesEnabledChange = viewModel::setCrashesEnabled,
            onUsageEnabledChange = viewModel::setUsageEnabled,
            onOpenPrivacyPolicy = {
                uriHandler.openUri("https://github.com/kofikodr/kofipod/blob/master/PRIVACY.md")
            },
        )

        // Debug-only entry point to scheduler info screen; kept intentionally minimal.
        Spacer(Modifier.height(24.dp))
        Text(
            "Scheduler details →",
            color = c.textMute,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier =
                Modifier
                    .clickable { onOpenScheduler() }
                    .padding(vertical = 4.dp),
        )

        if (AppInfo.isDebugBuild) {
            SectionLabel("Debug", topSpacing = 22.dp)
            SettingRow(
                icon = KPIconName.Bell,
                title = "Send single-episode notification",
                subtitle = "Picks a random episode from a random subscribed podcast",
                onClick = { viewModel.sendTestSingleNotification() },
            )
            Spacer(Modifier.height(10.dp))
            SettingRow(
                icon = KPIconName.Bell,
                title = "Send many-episodes notification",
                subtitle = "Generic count summary; tap opens Library",
                onClick = { viewModel.sendTestManyNotification() },
            )
            Spacer(Modifier.height(10.dp))
            SettingRow(
                icon = KPIconName.Trash,
                title = "Force crash (test GlitchTip)",
                subtitle = "Throws an unhandled exception; reopen to upload",
                onClick = { viewModel.forceCrash() },
            )
            Spacer(Modifier.height(10.dp))
            SettingRow(
                icon = KPIconName.Send,
                title = "Send test telemetry (Aptabase)",
                subtitle = "Bypasses gating; snackbar shows SDK status",
                onClick = { viewModel.debugSendTestTelemetry() },
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Kofipod · v${AppInfo.versionName}",
            color = c.textMute,
            fontSize = 11.sp,
            modifier =
                Modifier
                    .clickable(
                        // No ripple / role: the affordance is intentionally invisible to
                        // a casual user; only someone told about the gesture should land here.
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {
                            val now = Clock.System.now().toEpochMilliseconds()
                            val withinWindow =
                                reviewerLastTapMs != 0L &&
                                    now - reviewerLastTapMs <= REVIEWER_TAP_WINDOW_MS
                            reviewerTapCount = if (withinWindow) reviewerTapCount + 1 else 1
                            reviewerLastTapMs = now
                            if (reviewerTapCount >= REVIEWER_TAPS_REQUIRED) {
                                reviewerTapCount = 0
                                reviewerLastTapMs = 0L
                                reviewerCodeDialogVisible = true
                            }
                        },
                    )
                    .padding(vertical = 4.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Podcast data powered by Podcast Index and Apple iTunes",
            color = c.textMute,
            fontSize = 11.sp,
        )
    }

    if (reviewerCodeDialogVisible) {
        ReviewerUnlockDialog(
            onCancel = { reviewerCodeDialogVisible = false },
            onSubmit = { code ->
                reviewerCodeDialogVisible = false
                viewModel.submitReviewerUnlock(code)
            },
        )
    }

    if (storefrontPickerVisible) {
        StorefrontPickerDialog(
            selected = state.itunesStorefront,
            onPick = { storefront ->
                viewModel.setItunesStorefront(storefront)
                storefrontPickerVisible = false
            },
            onCancel = { storefrontPickerVisible = false },
        )
    }
}

@Composable
private fun StorefrontPickerDialog(
    selected: ItunesStorefront,
    onPick: (ItunesStorefront) -> Unit,
    onCancel: () -> Unit,
) {
    val c = LocalKofipodColors.current
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = c.surface,
        title = {
            Text("Search storefront", color = c.text, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Which national Apple Podcasts catalogue should be searched alongside Podcast Index?",
                    color = c.textMute,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                ItunesStorefront.entries.forEach { sf ->
                    val active = sf == selected
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) c.purpleTint else Color.Transparent)
                                .clickable { onPick(sf) }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("storefrontRow_${sf.iso2}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            sf.label,
                            color = c.text,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (active) {
                            KPIcon(name = KPIconName.Check, color = c.purple, size = 18.dp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Text(
                "Cancel",
                color = c.textMute,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onCancel() }.padding(8.dp),
            )
        },
    )
}

@Composable
private fun ReviewerUnlockDialog(
    onCancel: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val c = LocalKofipodColors.current
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = c.surface,
        title = { Text("Reviewer unlock", color = c.text, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "Enter the unlock code to grant Pro access on this device for review.",
                    color = c.textMute,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    singleLine = true,
                    placeholder = { Text("kofipod-…", color = c.textMute, fontSize = 13.sp) },
                    keyboardOptions =
                        KeyboardOptions(
                            autoCorrectEnabled = false,
                            capitalization = KeyboardCapitalization.None,
                        ),
                    modifier = Modifier.fillMaxWidth().testTag("reviewerUnlockField"),
                    colors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = c.surface,
                            unfocusedContainerColor = c.surface,
                            focusedTextColor = c.text,
                            unfocusedTextColor = c.text,
                            cursorColor = c.pink,
                            focusedIndicatorColor = c.purple,
                            unfocusedIndicatorColor = c.border,
                        ),
                )
            }
        },
        confirmButton = {
            Text(
                "Unlock",
                color = c.pink,
                fontWeight = FontWeight.Bold,
                modifier =
                    Modifier
                        .clickable(enabled = code.isNotBlank()) { onSubmit(code) }
                        .padding(8.dp)
                        .testTag("reviewerUnlockSubmit"),
            )
        },
        dismissButton = {
            Text(
                "Cancel",
                color = c.textMute,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onCancel() }.padding(8.dp),
            )
        },
    )
}

// --------------------------------------------------------------------------
// Theme mode selector
// --------------------------------------------------------------------------

@Composable
private fun ThemeModeSelector(
    selected: KofipodThemeMode,
    onSelect: (KofipodThemeMode) -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.pill))
            .background(c.surfaceAlt)
            .border(1.dp, c.border, RoundedCornerShape(r.pill))
            .padding(4.dp),
    ) {
        KofipodThemeMode.entries.forEach { mode ->
            val active = mode == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(r.pill))
                    .background(if (active) c.purple else Color.Transparent)
                    .clickable { onSelect(mode) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text =
                        when (mode) {
                            KofipodThemeMode.System -> "System"
                            KofipodThemeMode.Light -> "Light"
                            KofipodThemeMode.Dark -> "Dark"
                        },
                    color = if (active) Color.White else c.text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

// --------------------------------------------------------------------------
// Max auto-download size card — gradient slider
// --------------------------------------------------------------------------

@Composable
private fun MaxDownloadSizeCard(
    bytes: Long,
    onChange: (Long) -> Unit,
) {
    val c = LocalKofipodColors.current
    // Live drag value (null when not scrubbing). The pink readout reflects this
    // during scrub so users see what they're committing to before lifting their
    // finger; the commit itself only fires on release via GradientSlider.onValueChange.
    var liveBytes by remember { mutableStateOf<Long?>(null) }
    val shownBytes = liveBytes ?: bytes
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Max auto-download size",
                    color = c.text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Oldest unplayed episodes are removed first",
                    color = c.textMute,
                    fontSize = 11.5.sp,
                )
            }
            Text(
                formatGb(shownBytes),
                color = c.pink,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        Spacer(Modifier.height(12.dp))
        GradientSlider(
            value = bytes.coerceIn(MIN_CAP_BYTES, MAX_CAP_BYTES).toFloat(),
            valueRange = MIN_CAP_BYTES.toFloat()..MAX_CAP_BYTES.toFloat(),
            onValueChange = { onChange(it.toLong()) },
            onScrubbingChange = { liveBytes = it?.toLong() },
            modifier = Modifier.testTag("storageCapSlider"),
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                "500 MB",
                color = c.textMute,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text(
                "8 GB",
                color = c.textMute,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

// --------------------------------------------------------------------------
// Playback cache card — streaming cache cap with live "used" readout
// --------------------------------------------------------------------------

@Composable
private fun PlaybackCacheCard(
    capBytes: Long,
    usedBytes: Long,
    onChange: (Long) -> Unit,
) {
    val c = LocalKofipodColors.current
    var liveBytes by remember { mutableStateOf<Long?>(null) }
    val shownBytes = liveBytes ?: capBytes
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Streaming cache",
                    color = c.text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Audio is cached as you listen. Changes apply on next app restart.",
                    color = c.textMute,
                    fontSize = 11.5.sp,
                )
            }
            Text(
                formatSize(shownBytes),
                color = c.pink,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        Spacer(Modifier.height(12.dp))
        GradientSlider(
            value = capBytes.coerceIn(MIN_STREAM_CACHE_BYTES, MAX_STREAM_CACHE_BYTES).toFloat(),
            valueRange = MIN_STREAM_CACHE_BYTES.toFloat()..MAX_STREAM_CACHE_BYTES.toFloat(),
            onValueChange = { onChange(it.toLong()) },
            onScrubbingChange = { liveBytes = it?.toLong() },
            modifier = Modifier.testTag("streamCacheCapSlider"),
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                "128 MB",
                color = c.textMute,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Currently using ${formatSize(usedBytes)}",
                color = c.textMute,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "2 GB",
                color = c.textMute,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * Custom slider with a purple→pink gradient active track and a white thumb in a
 * pink halo. Long-press to arm, drag the same finger to adjust, release to commit
 * — mirrors PlayerScrubber so accidental taps while scrolling Settings don't
 * mutate quota values.
 *
 * - `onValueChange` fires once on release with the final value.
 * - `onScrubbingChange` fires with the live value during scrub (and `null` when
 *   the gesture ends), so callers can preview the value without persisting it.
 */
@Composable
private fun GradientSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onScrubbingChange: (Float?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = LocalKofipodColors.current
    val haptic = LocalHapticFeedback.current
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var armed by remember { mutableStateOf(false) }

    val committedFraction =
        if (valueRange.endInclusive == valueRange.start) {
            0f
        } else {
            ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start))
                .coerceIn(0f, 1f)
        }
    val effectiveFraction = dragFraction ?: committedFraction

    val emphasis by animateFloatAsState(
        targetValue = if (armed) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "settings-slider-emphasis",
    )
    val showArmedHint = emphasis > 0.5f

    fun fractionToValue(f: Float): Float = valueRange.start + f * (valueRange.endInclusive - valueRange.start)

    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(valueRange) {
                        // Long-press arms; drag adjusts; release commits.
                        // Quick taps and short swipes are dropped so the surrounding
                        // verticalScroll keeps working without firing seeks.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val longPress =
                                awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                            armed = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val startFraction =
                                (longPress.position.x / size.width).coerceIn(0f, 1f)
                            dragFraction = startFraction
                            onScrubbingChange(fractionToValue(startFraction))
                            longPress.consume()
                            try {
                                drag(longPress.id) { change ->
                                    val f = (change.position.x / size.width).coerceIn(0f, 1f)
                                    dragFraction = f
                                    onScrubbingChange(fractionToValue(f))
                                    change.consume()
                                }
                                dragFraction?.let { onValueChange(fractionToValue(it)) }
                            } finally {
                                armed = false
                                dragFraction = null
                                onScrubbingChange(null)
                            }
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val centerY = size.height / 2f
                    val baseThickness = 4.dp.toPx()
                    val baseRadius = baseThickness / 2f
                    // Track thickens slightly when armed.
                    val fillThickness = (6.dp.toPx()) + (2.dp.toPx() * emphasis)
                    val fillRadius = fillThickness / 2f
                    // Inactive track
                    drawRoundRect(
                        color = c.purpleTint,
                        topLeft = Offset(0f, centerY - baseRadius),
                        size = Size(size.width, baseThickness),
                        cornerRadius = CornerRadius(baseRadius, baseRadius),
                    )
                    // Active gradient. Idle softens toward surface; armed shows full vibrancy.
                    val filledWidth = size.width * effectiveFraction
                    if (filledWidth > 0f) {
                        val startColor = lerp(lerp(c.purple, c.surface, 0.20f), c.purple, emphasis)
                        val endColor = lerp(lerp(c.pink, c.surface, 0.12f), c.pink, emphasis)
                        drawRoundRect(
                            brush =
                                Brush.horizontalGradient(
                                    colors = listOf(startColor, endColor),
                                    startX = 0f,
                                    endX = size.width,
                                ),
                            topLeft = Offset(0f, centerY - fillRadius),
                            size = Size(filledWidth, fillThickness),
                            cornerRadius = CornerRadius(fillRadius, fillRadius),
                        )
                    }
                    // Clamp thumb center inside the canvas so the halo + thumb
                    // aren't half-clipped at the range endpoints.
                    val thumbOuter = (8.dp.toPx()) + (3.dp.toPx() * emphasis)
                    val thumbInner = (6.dp.toPx()) + (2.dp.toPx() * emphasis)
                    val haloRadius = (8.dp.toPx()) + (10.dp.toPx() * emphasis)
                    val thumbX =
                        (size.width * effectiveFraction)
                            .coerceIn(thumbOuter, size.width - thumbOuter)
                    val haloAlpha = 0.30f * emphasis
                    if (haloAlpha > 0f) {
                        drawCircle(
                            color = c.pink.copy(alpha = haloAlpha),
                            radius = haloRadius,
                            center = Offset(thumbX, centerY),
                        )
                    }
                    // Thumb grows from 8dp → 11dp; inner white core from 6dp → 8dp.
                    drawCircle(
                        color = c.pink,
                        radius = thumbOuter,
                        center = Offset(thumbX, centerY),
                    )
                    drawCircle(
                        color = Color.White,
                        radius = thumbInner,
                        center = Offset(thumbX, centerY),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        // Hint label flips off the animated value so the string change stays in
        // lockstep with the emphasis animation (no single-frame desync on disarm).
        Text(
            text = if (showArmedHint) "Adjusting" else "Hold to adjust",
            color = lerp(c.textMute, c.pink, emphasis),
            fontSize = 11.sp,
            fontWeight = if (showArmedHint) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// --------------------------------------------------------------------------
// Shared pink switch
// --------------------------------------------------------------------------

@Composable
private fun PinkSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    val c = LocalKofipodColors.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
        colors =
            SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = c.pink,
                checkedBorderColor = c.pink,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = c.purpleTint,
                uncheckedBorderColor = c.border,
            ),
    )
}

// --------------------------------------------------------------------------
// Update card
// --------------------------------------------------------------------------

@Composable
private fun UpdateCard(
    update: UpdateUiState,
    action: UpdateAction,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        when (update) {
            is UpdateUiState.UpToDate -> UpToDateRow(update, action, onCheck)
            is UpdateUiState.Available -> AvailableRow(update, action, onDownload, onDismiss)
            is UpdateUiState.ReadyToInstall -> ReadyToInstallRow(update, onInstall, onDismiss)
        }
        if (action is UpdateAction.Error) {
            Spacer(Modifier.height(8.dp))
            Text(
                action.message,
                color = c.pink,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun UpToDateRow(
    state: UpdateUiState.UpToDate,
    action: UpdateAction,
    onCheck: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        KPIcon(name = KPIconName.Check, color = c.purple, size = 22.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "You're up to date",
                color = c.text,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Text(
                lastCheckedSubtitle(state.lastCheckedAtMs),
                color = c.textMute,
                fontSize = 11.5.sp,
            )
        }
        UpdatePillButton(
            label = if (action is UpdateAction.Checking) "Checking…" else "Check now",
            enabled = action !is UpdateAction.Checking,
            onClick = onCheck,
        )
    }
}

@Composable
private fun AvailableRow(
    state: UpdateUiState.Available,
    action: UpdateAction,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        KPIcon(name = KPIconName.Download, color = c.pink, size = 22.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Update v${state.info.version}",
                color = c.text,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            val sizeLabel = if (state.info.apkSizeBytes > 0) " · ${formatMb(state.info.apkSizeBytes)}" else ""
            Text(
                "Newer version available$sizeLabel",
                color = c.textMute,
                fontSize = 11.5.sp,
            )
        }
        UpdatePillButton(
            label = downloadButtonLabel(action),
            enabled = action !is UpdateAction.Downloading,
            onClick = onDownload,
        )
    }
    if (action is UpdateAction.Downloading && action.totalBytes > 0) {
        Spacer(Modifier.height(8.dp))
        DownloadProgress(
            downloaded = action.downloadedBytes,
            total = action.totalBytes,
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "Skip this version",
        color = c.textMute,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier =
            Modifier
                .clickable { onDismiss() }
                .padding(vertical = 4.dp),
    )
}

@Composable
private fun ReadyToInstallRow(
    state: UpdateUiState.ReadyToInstall,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        KPIcon(name = KPIconName.Download, color = c.purple, size = 22.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Ready to install v${state.info.version}",
                color = c.text,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Text(
                "Tap install to launch the system installer. You may need to grant " +
                    "permission to install from this app the first time.",
                color = c.textMute,
                fontSize = 11.5.sp,
            )
        }
        UpdatePillButton(label = "Install", enabled = true, onClick = onInstall)
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "Skip this version",
        color = c.textMute,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier =
            Modifier
                .clickable { onDismiss() }
                .padding(vertical = 4.dp),
    )
}

@Composable
private fun UpdatePillButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(r.pill))
                .background(if (enabled) c.purple else c.purpleTint)
                .clickable(enabled = enabled) { onClick() }
                .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (enabled) Color.White else c.textMute,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun DownloadProgress(
    downloaded: Long,
    total: Long,
) {
    val c = LocalKofipodColors.current
    val fraction = if (total > 0) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    Column {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(c.purpleTint),
        ) {
            if (fraction > 0f) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                Brush.horizontalGradient(listOf(c.purple, c.pink)),
                            ),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${formatMb(downloaded)} / ${formatMb(total)}",
            color = c.textMute,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun downloadButtonLabel(action: UpdateAction): String =
    when (action) {
        is UpdateAction.Downloading -> {
            val pct = if (action.totalBytes > 0) (action.downloadedBytes * 100 / action.totalBytes).toInt() else 0
            "$pct%"
        }
        else -> "Download"
    }

private fun lastCheckedSubtitle(lastCheckedAtMs: Long?): String {
    if (lastCheckedAtMs == null) return "Tap to check for new releases"
    val ageMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - lastCheckedAtMs
    val minutes = ageMs / 60_000L
    return when {
        minutes < 1L -> "Checked just now"
        minutes < 60L -> "Checked $minutes min ago"
        minutes < 60L * 24L -> "Checked ${minutes / 60L} hr ago"
        else -> "Checked ${minutes / (60L * 24L)} day(s) ago"
    }
}

private fun formatMb(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / (1024L * 1024L)
    return "$mb MB"
}

// --------------------------------------------------------------------------
// Formatting
// --------------------------------------------------------------------------

private fun formatGb(bytes: Long): String {
    val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        val whole = gb.toInt()
        val tenths = ((gb - whole) * 10).roundToInt().coerceIn(0, 9)
        "$whole.$tenths GB"
    } else {
        val mb = (bytes / (1024L * 1024L)).toInt()
        "$mb MB"
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) formatGb(bytes) else "${(bytes / (1024L * 1024L)).toInt()} MB"
}

// --------------------------------------------------------------------------
// Kofipod Pro status card
// --------------------------------------------------------------------------

@Composable
private fun ProStatusCard(
    entitlement: ProEntitlement,
    restoreInFlight: Boolean,
    onUpgrade: () -> Unit,
    onRestore: () -> Unit,
) {
    // Foss / iOS have no real billing backend, so there's nothing to restore —
    // hide the affordance entirely on those builds.
    val showRestore = BillingCapability.restoreEnabled
    when (entitlement) {
        ProEntitlement.Unknown ->
            // While entitlement is Unknown, refreshOnStart() is in-flight. Showing an
            // active Restore link would race that pipeline (two concurrent connect()s
            // and two cache writes interleaved). Disable the link until we know the tier.
            ProActiveCard(
                label = "Checking…",
                subtitle = "Restoring purchase status",
                restoreInFlight = true,
                showRestore = showRestore,
                onRestore = {},
            )
        ProEntitlement.Free ->
            ProUpgradeCard(
                restoreInFlight = restoreInFlight,
                showRestore = showRestore,
                onUpgrade = onUpgrade,
                onRestore = onRestore,
            )
        is ProEntitlement.Pro ->
            when (entitlement.source) {
                ProSource.Individual ->
                    ProActiveCard(
                        label = "Kofipod Pro",
                        subtitle = "Active · purchased on this device",
                        restoreInFlight = restoreInFlight,
                        showRestore = showRestore,
                        onRestore = onRestore,
                    )
                ProSource.FossBuild ->
                    ProActiveCard(
                        label = "Kofipod Pro",
                        subtitle = "Self-build · all features unlocked",
                        restoreInFlight = restoreInFlight,
                        showRestore = showRestore,
                        onRestore = onRestore,
                    )
                ProSource.ReviewerUnlock ->
                    ProActiveCard(
                        label = "Kofipod Pro",
                        subtitle = "Reviewer unlock · all features active",
                        restoreInFlight = restoreInFlight,
                        showRestore = showRestore,
                        onRestore = onRestore,
                    )
            }
    }
}

@Composable
private fun ProUpgradeCard(
    restoreInFlight: Boolean,
    showRestore: Boolean,
    onUpgrade: () -> Unit,
    onRestore: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(c.pink, Color(0xFFC71D7C)),
                    ),
                )
                .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Column {
            Text(
                "KOFIPOD PRO",
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Snip, bookmark, and\nsend to your second brain.",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 21.sp,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.White)
                            .clickable(onClick = onUpgrade)
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Text(
                        "Upgrade · \$12.99",
                        color = c.pink,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
                if (showRestore) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = if (restoreInFlight) "Restoring…" else "Restore",
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier =
                            Modifier
                                .clickable(enabled = !restoreInFlight, onClick = onRestore)
                                .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProActiveCard(
    label: String,
    subtitle: String,
    restoreInFlight: Boolean,
    showRestore: Boolean,
    onRestore: () -> Unit,
) {
    val c = LocalKofipodColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(c.surface)
                .border(1.5.dp, c.borderStrong, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(colors = listOf(c.purple, c.pink)),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "PRO",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    color = c.text,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier =
                        Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(c.success),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                color = c.textMute,
                fontSize = 11.5.sp,
            )
        }
        if (showRestore) {
            Text(
                text = if (restoreInFlight) "Restoring…" else "Restore",
                color = c.purple,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier =
                    Modifier
                        .clickable(enabled = !restoreInFlight, onClick = onRestore)
                        .padding(vertical = 4.dp, horizontal = 4.dp),
            )
        }
    }
}
