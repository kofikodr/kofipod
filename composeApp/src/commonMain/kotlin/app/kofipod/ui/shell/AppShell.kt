// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.kofipod.ui.nav.KofipodNavHost
import app.kofipod.ui.nav.Route
import app.kofipod.ui.theme.LocalKofipodColors

@Composable
fun AppShell() {
    val nav = rememberNavController()
    Scaffold(
        containerColor = LocalKofipodColors.current.bg,
        bottomBar = { BottomNav(nav) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            KofipodNavHost(nav)
        }
    }
}

@Composable
private fun BottomNav(nav: NavHostController) {
    val c = LocalKofipodColors.current
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    Row(
        Modifier.fillMaxWidth().background(c.surface).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItem("Search", matches(currentRoute, Route.Search::class.qualifiedName)) { nav.navigate(Route.Search) }
        NavItem("Library", matches(currentRoute, Route.Library::class.qualifiedName)) { nav.navigate(Route.Library) }
        NavItem("Downloads", matches(currentRoute, Route.Downloads::class.qualifiedName)) { nav.navigate(Route.Downloads) }
        NavItem("Settings", matches(currentRoute, Route.Settings::class.qualifiedName)) { nav.navigate(Route.Settings) }
    }
}

private fun matches(current: String?, target: String?): Boolean =
    current != null && target != null && current == target

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalKofipodColors.current
    Text(
        text = label,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        color = if (selected) c.pink else c.textSoft,
        modifier = Modifier.clickable { onClick() }.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
