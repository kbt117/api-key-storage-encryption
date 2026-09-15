package dev.kbt117.keyproxy.presentation.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.kbt117.keyproxy.R
import dev.kbt117.keyproxy.presentation.dashboard.DashboardScreen
import dev.kbt117.keyproxy.presentation.logs.LogsScreen
import dev.kbt117.keyproxy.presentation.settings.SettingsScreen

/** The three screens from the brief, in tab order. */
enum class Destination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    Dashboard("dashboard", R.string.dashboard_title, Icons.Filled.Home),
    Settings("settings", R.string.settings_title, Icons.Filled.Settings),
    Logs("logs", R.string.logs_title, Icons.Filled.List),
}

/**
 * App shell: a `Scaffold` with a top bar and three-tab bottom navigation.
 *
 * `Modifier.padding(innerPadding)` on the `NavHost` is what makes the app
 * edge-to-edge-safe on Android 16: the scaffold measures the system bar insets
 * and hands them down, so content never slides under the status or navigation
 * bar. The `NavigationBar` applies its own bottom inset internally.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyProxyApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val currentDestination = Destination.entries.firstOrNull { it.route == currentRoute }
        ?: Destination.Dashboard

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(currentDestination.labelRes)) })
        },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = destination.route == currentRoute,
                        onClick = { navController.navigateToTab(destination.route) },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Dashboard.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Destination.Dashboard.route) {
                DashboardScreen(
                    onOpenSettings = { navController.navigateToTab(Destination.Settings.route) },
                )
            }
            composable(Destination.Settings.route) { SettingsScreen() }
            composable(Destination.Logs.route) { LogsScreen() }
        }
    }
}

/**
 * Single-top tab navigation: re-selecting the current tab is a no-op rather
 * than pushing a duplicate entry, and switching tabs pops back to the graph's
 * start so the back stack stays shallow and predictable.
 */
private fun androidx.navigation.NavController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
