// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofikodr.kofipod.ui.primitives.KPIcon
import com.kofikodr.kofipod.ui.primitives.KPIconName
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors

/**
 * Pill tabs under the episode description. Each tab swaps the content area below.
 *
 * Slice 2 ships [Summary] fully. [Mentioned] and [Discuss] render placeholders
 * until Slice 3 and the future Q&A slice respectively. [Chapters] is the
 * existing chapter section moved into a tab; it has no AI sparkle.
 */
enum class EpisodeDetailTab(val sparkle: Boolean) {
    Chapters(sparkle = false),
    Summary(sparkle = true),
    Mentioned(sparkle = true),
    Discuss(sparkle = true),
}

/**
 * Decides which tabs are visible for the current episode + key state. Returned
 * order matches the rendered order. When the result is empty, the caller should
 * not render the tab strip at all.
 */
fun buildVisibleTabs(
    chapterCount: Int,
    summaryEnabled: Boolean,
): List<EpisodeDetailTab> =
    buildList {
        if (chapterCount > 0) add(EpisodeDetailTab.Chapters)
        if (summaryEnabled) {
            add(EpisodeDetailTab.Summary)
            add(EpisodeDetailTab.Mentioned)
            add(EpisodeDetailTab.Discuss)
        }
    }

@Composable
fun EpisodeDetailTabRow(
    tabs: List<EpisodeDetailTab>,
    selected: EpisodeDetailTab,
    onSelect: (EpisodeDetailTab) -> Unit,
    chapterCount: Int,
    mentionedCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            TabPill(
                tab = tab,
                isSelected = tab == selected,
                count = countFor(tab, chapterCount, mentionedCount),
                onClick = { onSelect(tab) },
            )
        }
    }
}

private fun countFor(
    tab: EpisodeDetailTab,
    chapterCount: Int,
    mentionedCount: Int,
): Int? =
    when (tab) {
        EpisodeDetailTab.Chapters -> chapterCount.takeIf { it > 0 }
        EpisodeDetailTab.Mentioned -> mentionedCount.takeIf { it > 0 }
        EpisodeDetailTab.Summary, EpisodeDetailTab.Discuss -> null
    }

@Composable
private fun TabPill(
    tab: EpisodeDetailTab,
    isSelected: Boolean,
    count: Int?,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val (bg, fg, borderColor) =
        if (isSelected) {
            Triple(c.purple, Color.White, c.purple)
        } else {
            // Subtle desaturated purple border so the unselected tabs sit
            // quietly beside the bold purple selected pill — pink read as a
            // separate accent fighting the purple, even at low alpha.
            Triple(Color.Transparent, c.text, c.borderStrong)
        }
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(bg)
                .border(width = 1.5.dp, color = borderColor, shape = RoundedCornerShape(999.dp))
                .clickable { onClick() }
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .testTag("episodeDetailTab.${tab.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tab.sparkle) {
            KPIcon(
                name = KPIconName.Sparkle,
                color = fg,
                size = 12.dp,
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = tab.label(),
            color = fg,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = count.toString(),
                color = if (isSelected) Color.White.copy(alpha = 0.7f) else c.textMute,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun EpisodeDetailTab.label(): String =
    when (this) {
        EpisodeDetailTab.Chapters -> "Chapters"
        EpisodeDetailTab.Summary -> "Summary"
        EpisodeDetailTab.Mentioned -> "Mentioned"
        EpisodeDetailTab.Discuss -> "Discuss"
    }
