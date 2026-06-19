package com.zerovpn.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zerovpn.app.ui.screens.*
import com.zerovpn.app.ui.theme.*

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Screen("home", "Home", Icons.Default.Home)
    data object AddExit : Screen("add_exit", "Add Exit", Icons.Default.Add)
    data object NodeInvite : Screen("node_invite", "Nodes", Icons.Default.Hub)
    data object Diagnostics : Screen("diagnostics", "Diagnostics", Icons.Default.Build)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    data object OracleProvision : Screen("oracle_provision", "Provision", Icons.Default.Cloud)
}

private val screens = listOf(
    Screen.Home,
    Screen.AddExit,
    Screen.NodeInvite,
    Screen.Diagnostics,
    Screen.Settings,
)

@Composable
fun NavGraph() {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        modifier = Modifier.background(Bg),
        bottomBar = {
            NavigationBar(
                containerColor = Surface,
                contentColor = TextPrimary,
                tonalElevation = 0.dp,
            ) {
                screens.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = null,
                                tint = if (selected) Accent else TextDim,
                            )
                        },
                        label = {
                            Text(
                                text = screen.label,
                                color = if (selected) Accent else TextDim,
                            )
                        },
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Surface,
                            selectedIconColor = Accent,
                            selectedTextColor = Accent,
                            unselectedIconColor = TextDim,
                            unselectedTextColor = TextDim,
                        ),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Bg,
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Home.route) {
                HomeScreen(snackbarHostState = snackbarHostState)
            }
            composable(Screen.AddExit.route) {
                AddExitScreen(
                    snackbarHostState = snackbarHostState,
                    onNavigateToProvision = { navController.navigate(Screen.OracleProvision.route) },
                )
            }
            composable(Screen.OracleProvision.route) {
                ProvisioningScreen(
                    snackbarHostState = snackbarHostState,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Screen.NodeInvite.route) {
                NodeInviteScreen()
            }
            composable(Screen.Diagnostics.route) {
                DiagnosticsScreen()
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
        }
    }
}