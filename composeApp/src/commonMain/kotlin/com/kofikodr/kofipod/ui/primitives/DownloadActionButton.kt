// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.primitives

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors

/**
 * Shared circular download-action button. Renders one of five visual states
 * derived from a [DownloadButtonState]:
 *
 * - [DownloadButtonState.Idle] — Download icon, tinted [iconColor]. Tap → [onIdleClick].
 * - [DownloadButtonState.Pending] — indeterminate gradient sweep around a Close
 *   icon. Tap → [onCancel].
 * - [DownloadButtonState.InProgress] — determinate gradient arc at the given
 *   fraction around a Close icon. Tap → [onCancel].
 * - [DownloadButtonState.Failed] — Download icon in danger red. Tap → [onRetry].
 * - [DownloadButtonState.Done] — Check icon in success green. Non-interactive.
 *
 * Used at three sizes across the app (28 dp per-row indicator, 44 dp podcast
 * detail header, 46 dp episode detail tertiary). All visual variation is
 * carried by the size + color tokens passed in; the state machine itself is
 * identical at every site.
 */
@Composable
fun DownloadActionButton(
    state: DownloadButtonState,
    size: Dp,
    iconColor: Color,
    onIdleClick: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = Color.Transparent,
    border: BorderStroke? = null,
    iconSize: Dp = (size.value * 0.4f).dp,
    arcStroke: Dp = 2.dp,
) {
    val c = LocalKofipodColors.current
    // Whole-button tap dispatch. The interactive states share the same Box
    // chrome (background + border + clip), so routing the click here lets
    // each branch focus on the inner glyph + arc only.
    val onClick: () -> Unit =
        when (state) {
            DownloadButtonState.Idle -> onIdleClick
            DownloadButtonState.Failed -> onRetry
            DownloadButtonState.Pending,
            is DownloadButtonState.InProgress,
            -> onCancel
            DownloadButtonState.Done -> ({})
        }
    val isInteractive = state !is DownloadButtonState.Done
    var box =
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
    if (border != null) box = box.border(border, CircleShape)
    if (isInteractive) box = box.clickable(onClick = onClick)

    Box(modifier = box, contentAlignment = Alignment.Center) {
        // AnimatedContent (not Crossfade) keyed by visual mode so updates that
        // keep the same mode don't re-fade. Two bugs this avoids:
        //
        //  1. Crossfade compares `targetState` by equality. `InProgress(fraction)`
        //     is a data class, so each ~5 Hz progress tick produced a new
        //     instance and restarted the alpha fade between two same-mode
        //     frames — visible as the icon "pulsating" while downloading.
        //  2. Crossfade's internal Box defaults to Alignment.TopStart, so
        //     branches that emitted a bare KPIcon (Idle/Failed/Done) landed
        //     in the upper-left of the button while the InFlight branch
        //     centred its own content — visible as a small icon flashing in
        //     the top-left corner during every transition.
        //
        // `contentKey = { it.crossfadeKey() }` collapses fraction-only updates
        // into the same slot (no transition), and each branch is wrapped in a
        // centred fillMaxSize Box so the glyph stays anchored at the centre.
        AnimatedContent(
            targetState = state,
            contentKey = { it.crossfadeKey() },
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(160)) },
            label = "downloadBtnXf",
        ) { animated ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (animated.crossfadeKey()) {
                    XfKey.IdleOrFailed -> {
                        val tint = if (animated is DownloadButtonState.Failed) c.danger else iconColor
                        KPIcon(name = KPIconName.Download, color = tint, size = iconSize)
                    }
                    XfKey.Done -> KPIcon(name = KPIconName.Check, color = c.success, size = iconSize, strokeWidth = 2.2f)
                    XfKey.InFlight ->
                        InFlightArc(
                            state = animated,
                            arcStroke = arcStroke,
                            iconSize = iconSize,
                            trackColor = c.purpleTint,
                            gradientStart = c.purple,
                            gradientEnd = c.pink,
                            iconColor = c.textSoft,
                        )
                }
            }
        }
    }
}

/**
 * Crossfade key collapses the five visual states into the three drawing modes
 * actually rendered. Failed reuses Idle's icon (different tint) so they share
 * a key — that avoids a fade flash on the (rare) Failed → re-tap → Idle path.
 */
private enum class XfKey { IdleOrFailed, InFlight, Done }

private fun DownloadButtonState.crossfadeKey(): XfKey =
    when (this) {
        DownloadButtonState.Idle, DownloadButtonState.Failed -> XfKey.IdleOrFailed
        DownloadButtonState.Done -> XfKey.Done
        DownloadButtonState.Pending,
        is DownloadButtonState.InProgress,
        -> XfKey.InFlight
    }

@Composable
private fun InFlightArc(
    state: DownloadButtonState,
    arcStroke: Dp,
    iconSize: Dp,
    trackColor: Color,
    gradientStart: Color,
    gradientEnd: Color,
    iconColor: Color,
) {
    // Indeterminate sweep when Pending; determinate at fraction when InProgress.
    val sweepFraction =
        when (state) {
            is DownloadButtonState.InProgress ->
                animateFloatAsState(targetValue = state.fraction, label = "dlArc", animationSpec = tween(160)).value
            else -> 0.28f // indeterminate arc length
        }
    val rotation =
        if (state is DownloadButtonState.InProgress) {
            -90f
        } else {
            val infinite = rememberInfiniteTransition(label = "dlSpin")
            infinite.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(animation = tween(1_200, easing = LinearEasing)),
                label = "dlSpinAngle",
            ).value
        }

    Box(modifier = Modifier.fillMaxSize().padding(2.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = arcStroke.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            val topLeft = Offset(inset, inset)
            // Background track — full circle, matches ProgressRing in DownloadsScreen.
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
            val sweep = (sweepFraction.coerceIn(0f, 1f)) * 360f
            if (sweep > 0f) {
                val brush =
                    Brush.linearGradient(
                        colors = listOf(gradientStart, gradientEnd),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height),
                    )
                drawArc(
                    brush = brush,
                    startAngle = rotation,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }
        KPIcon(name = KPIconName.Close, color = iconColor, size = iconSize)
    }
}
