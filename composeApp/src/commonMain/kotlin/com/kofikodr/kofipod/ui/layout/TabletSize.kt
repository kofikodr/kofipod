// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.layout

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Rail presentation mode for tablet layouts. See spec §3.
 */
enum class RailMode { IconOnly, IconLabel, Expanded }

/**
 * Tablet form-factor classification. See spec §2 for the canonical breakpoint matrix.
 *
 * Phone (< 600 dp width) is represented by `null` rather than an enum value, so phone
 * code paths stay free of tablet branching.
 */
enum class TabletSize(val railMode: RailMode, val isMasterDetail: Boolean) {
    Tablet8Port(RailMode.IconOnly, false),
    Tablet8Land(RailMode.IconOnly, true),
    Tablet10Port(RailMode.IconLabel, false),
    Tablet10Land(RailMode.Expanded, true),
}

/**
 * CompositionLocal carrying the current `TabletSize`. Defaults to `null` (phone).
 *
 * Populated at the app root by [WithTabletSize]. Descendants read via [rememberTabletSize].
 */
val LocalTabletSize = compositionLocalOf<TabletSize?> { null }

/**
 * Returns the current [TabletSize], or `null` on phone.
 *
 * This is a thin accessor over [LocalTabletSize]; the classification itself happens once
 * inside [WithTabletSize] at the app root.
 */
@Composable
fun rememberTabletSize(): TabletSize? = LocalTabletSize.current

/**
 * Classifies a tablet form factor from the available window size in dp.
 *
 * Returns `null` for phone widths (< 600 dp). See spec §2 — the 8"/10" boundary is
 * derived from the canonical pairs (800 × 1200, 1000 × 1400): smaller dimension < 900 dp
 * is 8", >= 900 dp is 10".
 *
 * Tie-breakers:
 * - Exactly 600.dp width: phone (`< 600` is strict; spec says "< 600 dp width").
 * - Square (width == height): treated as portrait.
 */
internal fun classifyTabletSize(
    maxWidth: Dp,
    maxHeight: Dp,
): TabletSize? {
    if (maxWidth < 600.dp) return null
    val smaller = if (maxWidth <= maxHeight) maxWidth else maxHeight
    val isLandscape = maxWidth > maxHeight
    val isTenInch = smaller >= 900.dp
    return when {
        isTenInch && isLandscape -> TabletSize.Tablet10Land
        isTenInch -> TabletSize.Tablet10Port
        isLandscape -> TabletSize.Tablet8Land
        else -> TabletSize.Tablet8Port
    }
}

/**
 * Provides [LocalTabletSize] to [content] based on the available window constraints.
 *
 * Wrap this around the app shell so every descendant can read the classification via
 * [rememberTabletSize]. Uses [BoxWithConstraints] for KMP-friendly size detection —
 * we deliberately avoid `LocalConfiguration` (Android-only) and the material3 adaptive
 * window-size-class artifact (not on the classpath).
 */
@Composable
fun WithTabletSize(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val size = classifyTabletSize(maxWidth, maxHeight)
        CompositionLocalProvider(LocalTabletSize provides size) {
            content()
        }
    }
}
