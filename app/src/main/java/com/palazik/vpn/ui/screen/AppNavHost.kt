package com.palazik.vpn.ui.screen

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.palazik.vpn.ui.viewmodel.MainViewModel

sealed class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    object Home          : Screen("home",     "Home",    Icons.Rounded.Home)
    object Profiles      : Screen("profiles", "Profiles", Icons.AutoMirrored.Rounded.List)
    object Subscriptions : Screen("subs",     "Subs",    Icons.Rounded.Subscriptions)
    object Settings      : Screen("settings", "Settings", Icons.Rounded.Settings)
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
        modifier     = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackState) },
        bottomBar = {
            NavigationBar(tonalElevation = NavigationBarDefaults.Elevation) {
                val navBackStack by navController.currentBackStackEntryAsState()
                val currentDest = navBackStack?.destination
                tabs.forEach { screen ->
                    val selected = currentDest?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick  = {
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
        },
    ) { innerPadding ->
        NavHost(
            navController    = navController,
            startDestination = Screen.Home.route,
            modifier         = Modifier.padding(innerPadding),
            enterTransition  = {
                fadeIn(tween(220, easing = EaseOutQuart)) +
                    slideInHorizontally(tween(220, easing = EaseOutQuart)) { it / 10 }
            },
            exitTransition   = {
                fadeOut(tween(180)) +
                    slideOutHorizontally(tween(180)) { -it / 10 }
            },
            popEnterTransition = {
                fadeIn(tween(220, easing = EaseOutQuart)) +
                    slideInHorizontally(tween(220, easing = EaseOutQuart)) { -it / 10 }
            },
            popExitTransition  = {
                fadeOut(tween(180)) +
                    slideOutHorizontally(tween(180)) { it / 10 }
            },
        ) {
            composable(Screen.Home.route)          { HomeScreen(vm, permLauncher) }
            composable(Screen.Profiles.route)      { ProfilesScreen(vm) }
            composable(Screen.Subscriptions.route) { SubscriptionsScreen(vm) }
            composable(Screen.Settings.route)      { SettingsScreen(vm) }
        }
    }
}

private val EaseOutQuart = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)
