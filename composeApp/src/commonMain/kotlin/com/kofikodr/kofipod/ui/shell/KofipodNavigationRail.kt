// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navOptions
import com.kofikodr.kofipod.ui.layout.RailMode
import com.kofikodr.kofipod.ui.layout.TabletSize
import com.kofikodr.kofipod.ui.nav.Route
import com.kofikodr.kofipod.ui.primitives.KPIcon
import com.kofikodr.kofipod.ui.primitives.KPIconName
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors

/**
 * Mode-aware tablet navigation rail. Phase 1 Task 1.3 of
 * `docs/superpowers/plans/2026-05-11-tablet-phase-01-foundation.md`.
 *
 * Renders the 5 tablet destinations (Library, Search, Downloads, Stats, Settings) in
 * icon-only, icon+label, or expanded layout per [TabletSize.railMode]. Selection
 * mechanics mirror the phone bottom nav: pop-to-start, launchSingleTop, no-op on
 * re-selecting the active tab.
 */
@Composable
fun KofipodNavigationRail(
    nav: NavHostController,
    size: TabletSize,
    modifier: Modifier = Modifier,
) {
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    RailContent(
        currentRoute = currentRoute,
        mode = size.railMode,
        onSelect = { destination ->
            nav.popBackStack()
            nav.navigate(destination.route, navOptions { launchSingleTop = true })
        },
        modifier = modifier,
    )
}

internal data class RailDestination(
    val route: Route,
    val routeKey: String,
    val label: String,
    val icon: KPIconName,
)

internal val TABS_TABLET =
    listOf(
        RailDestination(
            Route.Library,
            Route.Library::class.qualifiedName!!,
            "Library",
            KPIconName.Library,
        ),
        RailDestination(
            Route.Search,
            Route.Search::class.qualifiedName!!,
            "Search",
            KPIconName.Search,
        ),
        RailDestination(
            Route.Downloads,
            Route.Downloads::class.qualifiedName!!,
            "Downloads",
            KPIconName.Downloads,
        ),
        RailDestination(
            Route.Stats,
            Route.Stats::class.qualifiedName!!,
            "Stats",
            KPIconName.Chart,
        ),
        RailDestination(
            Route.Settings,
            Route.Settings::class.qualifiedName!!,
            "Settings",
            KPIconName.Settings,
        ),
    )

/**
 * Pure selection logic: returns the destination to navigate to on tap, or null if the
 * tapped destination is already the active route (no-op-on-reselect, matching the phone
 * `BottomNav` behavior at AppShell.kt:265). Extracted so it can be unit-tested without
 * Compose UI test infrastructure.
 */
internal fun nextSelection(
    currentRoute: String?,
    tapped: RailDestination,
): RailDestination? = if (currentRoute == tapped.routeKey) null else tapped

/**
 * Pure presentational rail surface. Exposed `internal` so Paparazzi + UI tests can render
 * the rail without constructing a real [NavHostController]. Tap a destination that is
 * already selected returns null from [nextSelection] and [onSelect] is NOT invoked,
 * matching the phone `BottomNav` no-op-on-reselect behavior.
 */
@Composable
internal fun RailContent(
    currentRoute: String?,
    mode: RailMode,
    onSelect: (RailDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalKofipodColors.current
    val railWidth =
        when (mode) {
            RailMode.IconOnly -> 72.dp
            RailMode.IconLabel -> 200.dp
            RailMode.Expanded -> 240.dp
        }
    Column(
        modifier =
            modifier
                .width(railWidth)
                .fillMaxHeight()
                .background(c.surface)
                .border(width = 0.5.dp, color = c.border)
                .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (mode == RailMode.Expanded) {
            BrandBlock()
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = c.border, thickness = 0.5.dp)
            Spacer(Modifier.height(12.dp))
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TABS_TABLET.forEach { destination ->
                val selected = currentRoute == destination.routeKey
                RailItem(
                    destination = destination,
                    selected = selected,
                    mode = mode,
                    onClick = {
                        nextSelection(currentRoute, destination)?.let(onSelect)
                    },
                )
            }
        }
        if (mode == RailMode.Expanded) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = c.border, thickness = 0.5.dp)
            Spacer(Modifier.height(12.dp))
            ProfileChip()
        }
    }
}

@Composable
private fun RailItem(
    destination: RailDestination,
    selected: Boolean,
    mode: RailMode,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val bg = if (selected) c.purpleTint else Color.Transparent
    val fg = if (selected) c.purple else c.textMute
    val shape = RoundedCornerShape(12.dp)
    when (mode) {
        RailMode.IconOnly -> {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(shape)
                        .background(bg)
                        .clickable { onClick() },
                contentAlignment = Alignment.Center,
            ) {
                KPIcon(
                    name = destination.icon,
                    color = fg,
                    size = 24.dp,
                    strokeWidth = if (selected) 2.2f else 1.8f,
                )
            }
        }
        RailMode.IconLabel, RailMode.Expanded -> {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(shape)
                        .background(bg)
                        .clickable { onClick() }
                        .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                KPIcon(
                    name = destination.icon,
                    color = fg,
                    size = 22.dp,
                    strokeWidth = if (selected) 2.2f else 1.8f,
                )
                Text(
                    text = destination.label,
                    color = fg,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * Brand block shown at the top of the Expanded rail. Logo placeholder uses the brand
 * color rather than importing a new asset — keeps the surface area small for Phase 1.
 */
@Composable
private fun BrandBlock() {
    val c = LocalKofipodColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.purple),
        )
        Text(
            text = "Kofipod",
            color = c.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Profile chip shown at the bottom of the Expanded rail.
 *
 * NOTE: data is a presentational stub matching the design mock ("James M." / "● Drive").
 * No [com.kofikodr.kofipod.auth] / Drive sync repository is wired in commonMain today;
 * plumbing real account + sync state is intentionally deferred — out of scope for
 * Phase 1 Task 1.3 and tracked for a future phase.
 */
@Composable
private fun ProfileChip() {
    val c = LocalKofipodColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(c.purpleTint),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "JM",
                color = c.purple,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "James M.",
                color = c.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "● Drive",
                color = c.textMute,
                fontSize = 11.sp,
            )
        }
    }
}
