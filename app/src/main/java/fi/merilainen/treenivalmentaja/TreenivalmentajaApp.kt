package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument

@Composable
fun TreenivalmentajaApp(
    viewModel: WorkoutViewModel = viewModel(factory = WorkoutViewModel.Factory)
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val activeWorkoutVisible = currentDestination?.route?.startsWith("active/") == true

    Scaffold(
        bottomBar = {
          if (!activeWorkoutVisible) {
            NavigationBar(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer) {
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Home, contentDescription = "Tänään") },
                    label = { Text("Tänään") },
                    selected = currentDestination?.hierarchy?.any { it.route == "today" } == true,
                    onClick = {
                        navController.navigate("today") {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.CalendarMonth, contentDescription = "Viikko") },
                    label = { Text("Kalenteri") },
                    selected = currentDestination?.hierarchy?.any { it.route == "week" } == true,
                    onClick = {
                        navController.navigate("week") {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Asetukset") },
                    label = { Text("Asetukset") },
                    selected = currentDestination?.hierarchy?.any { it.route == "settings" } == true,
                    onClick = {
                        navController.navigate("settings") {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
          }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "today",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("today") {
              TodayScreen(
                viewModel = viewModel,
                onOpenActiveWorkout = { sessionId -> navController.navigate("active/$sessionId") },
              )
            }
            composable("week") { WeekScreen(viewModel) }
            composable("settings") { SettingsScreen(viewModel) }
            composable(
              route = "active/{sessionId}",
              arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            ) { entry ->
              ActiveWorkoutScreen(
                sessionId = entry.arguments?.getString("sessionId").orEmpty(),
                viewModel = viewModel,
                onClose = { navController.popBackStack() },
              )
            }
        }
    }
}
