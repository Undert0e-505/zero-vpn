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
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zerovpn.app.oci.OciAuthReturn
import com.zerovpn.app.BuildConfig
import com.zerovpn.app.ui.provisioning.ProvisioningState
import com.zerovpn.app.ui.provisioning.ProvisioningViewModel
import com.zerovpn.app.ui.screens.*
import com.zerovpn.app.ui.theme.*
import com.zerovpn.app.vpn.VpnViewModel
import kotlinx.coroutines.launch

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Screen("home", "Home", Icons.Default.Home)
    data object AddExit : Screen("add_exit", "Add Exit", Icons.Default.Add)
    data object Friends : Screen("node_invite", "Friends", Icons.Default.Hub)
    data object Diagnostics : Screen("diagnostics", "Diagnostics", Icons.Default.Build)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    data object OracleProvision : Screen("oracle_provision", "Provision", Icons.Default.Cloud)
    data object ScanInvite : Screen("scan_invite", "Scan", Icons.Default.Add)
    data object VolunteerIntro : Screen("volunteer_intro", "Volunteer", Icons.Default.VolunteerActivism)
    data object VolunteerDetails : Screen("volunteer_details", "Volunteer", Icons.Default.VolunteerActivism)
}

private val screens = listOf(
    Screen.Home,
    Screen.AddExit,
    Screen.Friends,
    Screen.Diagnostics,
    Screen.Settings,
)

@Composable
fun NavGraph() {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val provisioningViewModel: ProvisioningViewModel = viewModel()
    val vpnViewModel: VpnViewModel = viewModel()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuredExits by provisioningViewModel.configuredExits.collectAsState()
    val selectedExitId by provisioningViewModel.selectedExitId.collectAsState()
    val isDevMode by provisioningViewModel.isDevMode.collectAsState()
    val provisioningState by provisioningViewModel.state.collectAsState()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    LaunchedEffect(Unit) {
        provisioningViewModel.initPrefs(context)
    }

    LaunchedEffect(Unit) {
        OciAuthReturn.returns.collect {
            provisioningViewModel.markAuthReturned()
            navController.navigate(Screen.OracleProvision.route) {
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(configuredExits, selectedExitId) {
        vpnViewModel.reconcile(configuredExits, selectedExitId)
    }

    DisposableEffect(lifecycleOwner, configuredExits, selectedExitId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vpnViewModel.reconcile(configuredExits, selectedExitId)
                provisioningViewModel.onAppResumed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(currentDestination?.route, configuredExits, provisioningState) {
        if (
            currentDestination?.route == Screen.OracleProvision.route &&
            configuredExits.isNotEmpty() &&
            provisioningState is ProvisioningState.Idle
        ) {
            navController.navigate(Screen.Home.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = false
                }
                launchSingleTop = true
                restoreState = false
            }
        }
    }

    val navigateToTopLevel: (Screen) -> Unit = navigateToTopLevel@{ screen ->
        if (screen == Screen.AddExit) {
            provisioningViewModel.prepareNewProvisioningFlow()
            navController.navigate(Screen.AddExit.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = false
                }
                launchSingleTop = true
                restoreState = false
            }
            return@navigateToTopLevel
        }
        navController.navigate(screen.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

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
                            navigateToTopLevel(screen)
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
                HomeScreen(
                    snackbarHostState = snackbarHostState,
                    viewModel = provisioningViewModel,
                    vpnViewModel = vpnViewModel,
                    onDestroyStarted = { navController.navigate(Screen.OracleProvision.route) },
                    onAddExit = { navigateToTopLevel(Screen.AddExit) },
                )
            }
            composable(Screen.AddExit.route) {
                AddExitScreen(
                    snackbarHostState = snackbarHostState,
                    showVolunteerDebug = BuildConfig.VOLUNTEER_DEBUG_ENABLED && isDevMode,
                    onNavigateToProvision = {
                        provisioningViewModel.prepareNewProvisioningFlow()
                        navController.navigate(Screen.OracleProvision.route)
                    },
                    onNavigateToVolunteer = {
                        navController.navigate(Screen.VolunteerIntro.route)
                    },
                    onNavigateToScanInvite = {
                        navController.navigate(Screen.ScanInvite.route)
                    },
                )
            }
            composable(Screen.ScanInvite.route) {
                ScanInviteScreen(
                    viewModel = provisioningViewModel,
                    onCancel = { navController.popBackStack() },
                    onImported = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = false
                            }
                            launchSingleTop = true
                            restoreState = false
                        }
                    },
                )
            }
            composable(Screen.VolunteerIntro.route) {
                VolunteerIntroScreen(
                    snackbarHostState = snackbarHostState,
                    provisioningViewModel = provisioningViewModel,
                    onCancel = { navController.popBackStack() },
                    onCreated = {
                        navController.navigate(Screen.VolunteerDetails.route) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Screen.VolunteerDetails.route) {
                VolunteerDetailsScreen(
                    snackbarHostState = snackbarHostState,
                    provisioningViewModel = provisioningViewModel,
                    vpnViewModel = vpnViewModel,
                    onBack = { navController.popBackStack() },
                    onHome = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(Screen.OracleProvision.route) {
                ProvisioningScreen(
                    snackbarHostState = snackbarHostState,
                    onBack = { navController.popBackStack() },
                    viewModel = provisioningViewModel,
                    vpnViewModel = vpnViewModel,
                    onConnectedHome = {
                        provisioningViewModel.clearTransientProvisioningSuccess()
                        navController.navigate(Screen.Home.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = false
                            }
                            launchSingleTop = true
                            restoreState = false
                        }
                    },
                    onDestroy = {
                        scope.launch {
                            val exitId = provisioningViewModel.selectedExitId.value
                            val disconnected = vpnViewModel.disconnectIfActive(exitId)
                            if (!disconnected) {
                                snackbarHostState.showSnackbar("Disconnect failed. Node was not destroyed.")
                                return@launch
                            }
                            provisioningViewModel.destroyNode(context)
                        }
                    },
                )
            }
            composable(Screen.Friends.route) {
                FriendsScreen(
                    viewModel = provisioningViewModel,
                    onCreateOracleExit = {
                        provisioningViewModel.prepareNewProvisioningFlow()
                        navController.navigate(Screen.OracleProvision.route)
                    },
                )
            }
            composable(Screen.Diagnostics.route) {
                DiagnosticsScreen(
                    provisioningViewModel = provisioningViewModel,
                    vpnViewModel = vpnViewModel,
                )
            }
            composable(Screen.Settings.route) {
                val devMode by provisioningViewModel.isDevMode.collectAsState()
                SettingsScreen(
                    isDevMode = devMode,
                    onDevModeChange = { newValue ->
                        provisioningViewModel.setDevMode(newValue)
                    },
                )
            }
        }
    }
}
