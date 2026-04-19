// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navOptions
import app.kofipod.ui.nav.DeepLinks
import app.kofipod.ui.nav.KofipodNavHost
import app.kofipod.ui.nav.Route
import app.kofipod.ui.player.MiniPlayer
import app.kofipod.ui.primitives.KPIcon
import app.kofipod.ui.primitives.KPIconName
import app.kofipod.ui.theme.LocalKofipodColors

@Composable
fun AppShell() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    LaunchedEffect(nav) {
        DeepLinks.openPlayer.collect {
            if (nav.currentDestination?.route != Route.Player::class.qualifiedName) {
                nav.navigate(
                    Route.Player,
                    navOptions {
                        launchSingleTop = true
                        popUpTo(Route.Splash) { inclusive = true }
                    },
                )
            }
        }
    }
    val hideChrome =
        currentRoute == Route.Onboarding::class.qualifiedName ||
            currentRoute == Route.Splash::class.qualifiedName
    val onPlayerScreen = currentRoute == Route.Player::class.qualifiedName
    Scaffold(
        containerColor = LocalKofipodColors.current.bg,
        bottomBar = {
            if (!hideChrome) {
                Column {
                    if (!onPlayerScreen) {
                        MiniPlayer(
                            onOpen = {
                                nav.navigate(
                                    Route.Player,
                                    navOptions { launchSingleTop = true },
                                )
                            },
                        )
                    }
                    BottomNav(nav)
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            KofipodNavHost(nav)
        }
    }
}

private data class Tab(
    val route: Route,
    val routeKey: String,
    val label: String,
    val icon: KPIconName,
)

private val TABS =
    listOf(
        Tab(Route.Library, Route.Library::class.qualifiedName!!, "Library", KPIconName.Library),
        Tab(Route.Search, Route.Search::class.qualifiedName!!, "Search", KPIconName.Search),
        Tab(Route.Downloads, Route.Downloads::class.qualifiedName!!, "Downloads", KPIconName.Downloads),
        Tab(Route.Settings, Route.Settings::class.qualifiedName!!, "Settings", KPIconName.Settings),
    )

@Composable
private fun BottomNav(nav: NavHostController) {
    val c = LocalKofipodColors.current
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface)
            .border(width = 0.5.dp, color = c.border),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TABS.forEach { tab ->
                val selected = currentRoute == tab.routeKey
                TabItem(
                    tab = tab,
                    selected = selected,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (selected) return@TabItem
                        val options =
                            navOptions {
                                popUpTo(nav.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        nav.navigate(tab.route, options)
                    },
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: Tab,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val c = LocalKofipodColors.current
    val bg = if (selected) c.purpleTint else androidx.compose.ui.graphics.Color.Transparent
    val iconColor = if (selected) c.purple else c.textMute
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .clickable { onClick() }
                .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KPIcon(
            name = tab.icon,
            color = iconColor,
            size = 22.dp,
            strokeWidth = if (selected) 2.2f else 1.8f,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            tab.label,
            color = if (selected) c.purple else c.textMute,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 10.5.sp,
        )
    }
}
