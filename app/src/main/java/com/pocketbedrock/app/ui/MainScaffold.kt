package com.pocketbedrock.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pocketbedrock.app.AppGraph
import com.pocketbedrock.app.ui.addons.AddonsScreen
import com.pocketbedrock.app.ui.console.ConsoleScreen
import com.pocketbedrock.app.ui.dashboard.DashboardScreen
import com.pocketbedrock.app.ui.players.PlayersScreen
import com.pocketbedrock.app.ui.settings.BatteryHelperScreen
import com.pocketbedrock.app.ui.settings.RawEditorScreen
import com.pocketbedrock.app.ui.settings.SettingsScreen

private data class Tab(val route: String, val label: String, val icon: @Composable () -> Unit)

/** Bottom navigation with the five spec'd tabs; every sub-screen is one extra tap. */
@Composable
fun MainScaffold(graph: AppGraph) {
    val navController = rememberNavController()
    val tabs = listOf(
        Tab("dashboard", "Dashboard") { Icon(Icons.Filled.Dashboard, contentDescription = null) },
        Tab("console", "Console") { Icon(Icons.Filled.Terminal, contentDescription = null) },
        Tab("players", "Players") { Icon(Icons.Filled.Group, contentDescription = null) },
        Tab("addons", "Addons") { Icon(Icons.Filled.Extension, contentDescription = null) },
        Tab("settings", "Settings") { Icon(Icons.Filled.Settings, contentDescription = null) },
    )
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = tab.icon,
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(padding),
        ) {
            composable("dashboard") { DashboardScreen(graph, navController) }
            composable("console") { ConsoleScreen(graph) }
            composable("players") { PlayersScreen(graph) }
            composable("addons") { AddonsScreen(graph) }
            composable("settings") { SettingsScreen(graph, navController) }
            composable("settings/battery") { BatteryHelperScreen(navController) }
            composable("settings/raw") { RawEditorScreen(graph, navController) }
        }
    }
}
