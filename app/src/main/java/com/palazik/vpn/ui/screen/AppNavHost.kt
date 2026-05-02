package com.palazik.vpn.ui.screen

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.palazik.vpn.ui.viewmodel.MainViewModel

sealed class Screen(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Home          : Screen("home",    "Home",    Icons.Rounded.Home)
    object Profiles      : Screen("profiles","Profiles",Icons.Rounded.List)
    object Subscriptions : Screen("subs",    "Subs",    Icons.Rounded.Subscriptions)
    object Settings      : Screen("settings","Settings",Icons.Rounded.Settings)
}

@Composable
fun AppNavHost(
    vm: MainViewModel,
    permLauncher: ActivityResultLauncher<Intent>,
) {
    val navController = rememberNavController()
    val ui by vm.ui.collectAsState()

    val tabs = listOf(Screen.Home, Screen.Profiles, Screen.Subscriptions, Screen.Settings)

    val snackState = remember { SnackbarHostState() }
    LaunchedEffect(ui.snackMessage) {
        ui.snackMessage?.let {
            snackState.showSnackbar(it, duration = SnackbarDuration.Short)
            vm.clearSnack()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackState) },
        bottomBar = {
            NavigationBar {
                val navBackStack by navController.currentBackStackEntryAsState()
                val currentDest = navBackStack?.destination
                tabs.forEach { screen ->
                    NavigationBarItem(
                        selected  = currentDest?.hierarchy?.any { it.route == screen.route } == true,
                        onClick   = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState    = true
                            }
                        },
                        icon  = { Icon(screen.icon, screen.label) },
                        label = { Text(screen.label) },
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(navController, startDestination = Screen.Home.route, Modifier.padding(innerPadding)) {
            composable(Screen.Home.route)          { HomeScreen(vm, permLauncher) }
            composable(Screen.Profiles.route)      { ProfilesScreen(vm) }
            composable(Screen.Subscriptions.route) { SubscriptionsScreen(vm) }
            composable(Screen.Settings.route)      { SettingsScreen(vm) }
        }
    }
}
