// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.detail.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.theme.LocalKofipodColors

// Shared primitives for the AI tabs (Summary / Mentioned / Discuss). Lifted out
// of AiSummaryPanel when the Discuss feature landed, since both surfaces draw
// the same rounded card frame, the same pinkSoft+sparkle pill chip, and the
// same dashed pink CTA pill. Kept in this package because the AI tabs are the
// only callers — promoting to ui/primitives/ would imply a wider audience that
// doesn't yet exist.

/**
 * Rounded card frame that wraps every AI panel state (Idle / Generating /
 * Ready / Error / Discuss). One frame across all states means tab-content
 * height changes don't reflow the rest of the screen on transitions.
 */
@Composable
internal fun PanelCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val c = LocalKofipodColors.current
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(20.dp))
                .padding(16.dp),
    ) {
        content()
    }
}

/**
 * Pink-on-pinkSoft pill with a leading sparkle and a short uppercase mono
 * label. Used as the "AI SUMMARY" eyebrow on the Ready/Error cards, the
 * "AI ASSIST" / "GENERATING…" pill on the Idle/Generating cards, and the
 * "AI DISCUSS" / "ASK GEMINI" eyebrows on the Discuss surfaces. Parametrised
 * by label since every callsite differs only in copy.
 */
@Composable
internal fun AiPillChip(
    label: String,
    modifier: Modifier = Modifier,
) {
    val c = LocalKofipodColors.current
    Row(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(c.pinkSoft)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KPIcon(name = KPIconName.Sparkle, color = c.pink, size = 11.dp)
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            color = c.pink,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Soft-bordered tappable card that surfaces a single suggested question on
 * the Discuss tab and on the Ask Gemini idle pane. Both call sites pass a
 * [testTag] so each surface can be addressed independently from UI tests.
 */
@Composable
internal fun SuggestionCard(
    text: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val c = LocalKofipodColors.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = c.text,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(10.dp))
        KPIcon(name = KPIconName.ChevronRight, color = c.textMute, size = 16.dp)
    }
}

/**
 * Pink circular send button used in the Discuss composer stub (inert) and
 * on the Ask Gemini full-screen composer (active). [enabled] toggles the
 * background to `pinkSoft` and disables the click, so the disabled state
 * still reads as an affordance the user can see — just one they can't tap
 * directly.
 */
@Composable
internal fun SendBubble(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalKofipodColors.current
    val bg = if (enabled) c.pink else c.pinkSoft
    val tint = if (enabled) Color.White else c.pink
    Box(
        modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(bg)
            .then(
                if (enabled) {
                    Modifier.clickable { onClick() }
                } else {
                    Modifier
                },
            )
            .testTag("discussSendButton"),
        contentAlignment = Alignment.Center,
    ) {
        KPIcon(name = KPIconName.Send, color = tint, size = 16.dp, strokeWidth = 2.0f)
    }
}

/**
 * Full-width pink-bordered pill button with a leading sparkle. Used as the
 * "Generate AI summary" CTA on the Idle Summary card. Solid border in v1 —
 * Compose lacks first-class dashed strokes for borders and a stable
 * PathEffect-based implementation is more code than the visual delta is
 * worth right now. Tracked under "later polish".
 */
@Composable
internal fun DashedGenerateButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalKofipodColors.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(999.dp))
                .border(width = 1.5.dp, color = c.pink, shape = RoundedCornerShape(999.dp))
                .clickable { onClick() }
                .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KPIcon(name = KPIconName.Sparkle, color = c.pink, size = 14.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = c.pink,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
