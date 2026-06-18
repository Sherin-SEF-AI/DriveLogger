package com.blurabbit.drivelogger.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.blurabbit.drivelogger.ui.dashboard.DashboardScreen
import com.blurabbit.drivelogger.ui.settings.SettingsScreen
import com.blurabbit.drivelogger.ui.tripdetail.TripDetailScreen
import com.blurabbit.drivelogger.ui.trips.TripsScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val TRIPS = "trips"
    const val SETTINGS = "settings"
    const val TRIP_DETAIL = "trip/{tripId}"
    fun tripDetail(id: String) = "trip/$id"
}

private data class Tab(val route: String, val label: String, val icon: @Composable () -> Unit)

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val tabs = listOf(
        Tab(Routes.DASHBOARD, "Dashboard") { Icon(Icons.Default.Speed, null) },
        Tab(Routes.TRIPS, "Trips") { Icon(Icons.Default.DirectionsCar, null) },
        Tab(Routes.SETTINGS, "Settings") { Icon(Icons.Default.Settings, null) },
    )

    Scaffold(
        bottomBar = {
            val backStack by navController.currentBackStackEntryAsState()
            val current = backStack?.destination
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
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
            startDestination = Routes.DASHBOARD,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.DASHBOARD) { DashboardScreen() }
            composable(Routes.TRIPS) { TripsScreen(onOpenTrip = { navController.navigate(Routes.tripDetail(it)) }) }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(Routes.TRIP_DETAIL) { TripDetailScreen(onBack = { navController.popBackStack() }) }
        }
    }
}
