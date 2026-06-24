package com.zerovpn.app.ui.screens

import android.app.Activity
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zerovpn.app.ui.components.StatusCard
import com.zerovpn.app.ui.provisioning.ProvisioningViewModel
import com.zerovpn.app.ui.theme.*
import com.zerovpn.app.vpn.ConfiguredExit
import com.zerovpn.app.vpn.ExitLifecycleState
import com.zerovpn.app.vpn.ExitProvider
import com.zerovpn.app.vpn.ProviderSwitchDiagnostics
import com.zerovpn.app.vpn.VpnConnectionState
import com.zerovpn.app.vpn.VpnViewModel
import com.zerovpn.app.volunteer.VolunteerNetworkController
import com.zerovpn.app.volunteer.vpn.VolunteerVpnState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun HomeScreen(
    snackbarHostState: SnackbarHostState,
    onDestroyStarted: () -> Unit,
    onAddExit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProvisioningViewModel = viewModel(),
    vpnViewModel: VpnViewModel = viewModel(),
    volunteerNetworkController: VolunteerNetworkController = viewModel(),
) {
    val logTag = "ZeroVPN/Home"
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val exits by viewModel.configuredExits.collectAsState()
    val selectedExitId by viewModel.selectedExitId.collectAsState()
    val vpnState by vpnViewModel.state.collectAsState()
    val vpnDiagnostics by vpnViewModel.diagnostics.collectAsState()
    val volunteerState by volunteerNetworkController.volunteerVpnState.collectAsState()
    val volunteerDiagnostics by volunteerNetworkController.volunteerVpnDiagnostics.collectAsState()

    // Initialize prefs on first load (restores persisted state)
    viewModel.initPrefs(context)

    var showDestroyDialog by remember { mutableStateOf(false) }
    var destroyTarget by remember { mutableStateOf<ConfiguredExit?>(null) }
    var renameTarget by remember { mutableStateOf<ConfiguredExit?>(null) }
    var pendingPermissionExit by remember { mutableStateOf<ConfiguredExit?>(null) }
    var missingExitMessage by remember { mutableStateOf<String?>(null) }
    var removingExitId by remember { mutableStateOf<String?>(null) }
    var switchTargetExitId by remember { mutableStateOf<String?>(null) }
    var lastProviderSwitchStartedAt by remember { mutableStateOf<Long?>(null) }
    var lastProviderSwitchCompletedAt by remember { mutableStateOf<Long?>(null) }
    var lastProviderSwitchError by remember { mutableStateOf<String?>(null) }

    val hasExit = exits.isNotEmpty()
    val wireGuardActiveExitId = when (val currentState = vpnState) {
        is VpnConnectionState.Connected -> currentState.exitId
        is VpnConnectionState.ActiveUnknown -> vpnDiagnostics.activeExitId
        else -> null
    }
    val wireGuardKnownConnected = vpnState is VpnConnectionState.Connected
    val wireGuardIsActive = vpnState is VpnConnectionState.Connected ||
        vpnState is VpnConnectionState.ActiveUnknown
    val selectedExit = exits.firstOrNull { it.id == selectedExitId }
        ?: exits.singleOrNull()
    val selectedProviderType = selectedExit?.provider.toProviderType()
    val volunteerConnected = volunteerState is VolunteerVpnState.Running &&
        volunteerDiagnostics.androidVpnActiveDetected &&
        volunteerDiagnostics.hevRunning
    val volunteerBusy = volunteerState is VolunteerVpnState.StartingTor ||
        volunteerState is VolunteerVpnState.StartingVpn ||
        volunteerState is VolunteerVpnState.PermissionNeeded ||
        volunteerState is VolunteerVpnState.Stopping
    val wireGuardBusy = vpnState is VpnConnectionState.Connecting ||
        vpnState is VpnConnectionState.Disconnecting ||
        vpnState is VpnConnectionState.PermissionRequired
    val volunteerExit = exits.firstOrNull { it.provider == ExitProvider.VOLUNTEER }
    val activeExit = when {
        wireGuardKnownConnected -> wireGuardActiveExitId?.let { id -> exits.firstOrNull { it.id == id } }
            ?: exits.firstOrNull { it.provider == ExitProvider.OCI || it.provider == ExitProvider.SHARED_WIREGUARD }
        volunteerConnected -> volunteerExit
        wireGuardIsActive -> wireGuardActiveExitId?.let { id -> exits.firstOrNull { it.id == id } }
            ?: exits.firstOrNull { it.provider == ExitProvider.OCI || it.provider == ExitProvider.SHARED_WIREGUARD }
        else -> null
    }
    val bothProvidersReportedActive = wireGuardIsActive && volunteerConnected
    val activeExitId = activeExit?.id
    val activeProviderType = activeExit?.provider.toProviderType()
    val switchInProgress = switchTargetExitId != null || wireGuardBusy || volunteerBusy
    val selectedIsActive = selectedExit != null && selectedExit.id == activeExitId && activeProviderType != null
    val buttonText = when {
        switchTargetExitId != null && switchTargetExitId != selectedExit?.id -> "Switching"
        switchTargetExitId != null -> if (activeExit == null) "Connecting" else "Switching"
        selectedExit == null -> "Connect"
        selectedIsActive -> "Disconnect"
        else -> "Connect"
    }
    val connectedForStatus = activeExit != null

    LaunchedEffect(
        selectedExitId,
        activeExitId,
        activeProviderType,
        selectedProviderType,
        switchTargetExitId,
        lastProviderSwitchStartedAt,
        lastProviderSwitchCompletedAt,
        lastProviderSwitchError,
        bothProvidersReportedActive,
    ) {
        viewModel.updateProviderSwitchDiagnostics(
            ProviderSwitchDiagnostics(
                selectedExitId = selectedExitId,
                activeExitId = activeExitId,
                activeProviderType = activeProviderType?.name,
                selectedProviderType = selectedProviderType?.name,
                switchingTargetExitId = switchTargetExitId,
                lastProviderSwitchStartedAt = lastProviderSwitchStartedAt,
                lastProviderSwitchCompletedAt = lastProviderSwitchCompletedAt,
                lastProviderSwitchError = if (bothProvidersReportedActive) {
                    "Both providers reported active; Home is showing ${activeProviderType?.name ?: "one provider"} as active."
                } else {
                    lastProviderSwitchError
                },
            ),
        )
    }

    LaunchedEffect(switchTargetExitId, vpnState, volunteerState, volunteerDiagnostics) {
        val targetId = switchTargetExitId ?: return@LaunchedEffect
        val target = exits.firstOrNull { it.id == targetId } ?: return@LaunchedEffect
        val targetConnected = when (target.provider) {
            ExitProvider.VOLUNTEER -> volunteerConnected
            ExitProvider.OCI,
            ExitProvider.SHARED_WIREGUARD -> (vpnState as? VpnConnectionState.Connected)?.exitId == targetId
        }
        val targetFailed = when (target.provider) {
            ExitProvider.VOLUNTEER -> volunteerState is VolunteerVpnState.Failed
            ExitProvider.OCI,
            ExitProvider.SHARED_WIREGUARD -> vpnState is VpnConnectionState.Failed
        }
        when {
            targetConnected -> {
                switchTargetExitId = null
                lastProviderSwitchCompletedAt = System.currentTimeMillis()
            }
            targetFailed -> {
                switchTargetExitId = null
                lastProviderSwitchError = when (target.provider) {
                    ExitProvider.VOLUNTEER -> (volunteerState as? VolunteerVpnState.Failed)?.message
                        ?: "Volunteer Exit failed to connect."
                    ExitProvider.OCI,
                    ExitProvider.SHARED_WIREGUARD -> (vpnState as? VpnConnectionState.Failed)?.message
                        ?: "WireGuard exit failed to connect."
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val exit = pendingPermissionExit
        pendingPermissionExit = null
        Log.d(logTag, "VPN permission ActivityResult resultCode=${result.resultCode}, hasPendingExit=${exit != null}")
        if (result.resultCode == Activity.RESULT_OK && exit != null) {
            vpnViewModel.connect(exit)
        } else {
            switchTargetExitId = null
            lastProviderSwitchError = "Android VPN permission was not granted."
            vpnViewModel.onPermissionDenied()
            scope.launch {
                snackbarHostState.showSnackbar("VPN permission was not granted")
            }
        }
    }

    val volunteerPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            volunteerNetworkController.startVolunteerVpnTest()
        } else {
            switchTargetExitId = null
            lastProviderSwitchError = "Android VPN permission was not granted."
            volunteerNetworkController.markVolunteerVpnPermissionNeeded()
            scope.launch {
                snackbarHostState.showSnackbar("VPN permission was not granted")
            }
        }
    }

    if (showDestroyDialog) {
        val targetProvider = destroyTarget?.provider
        AlertDialog(
            onDismissRequest = { showDestroyDialog = false },
            title = {
                Text(
                    when (targetProvider) {
                        ExitProvider.VOLUNTEER -> "Remove Volunteer Exit?"
                        ExitProvider.SHARED_WIREGUARD -> "Remove shared exit?"
                        else -> "Destroy ${destroyTarget?.name ?: "Exit"}?"
                    },
                    color = TextPrimary,
                )
            },
            text = {
                Text(
                    when (targetProvider) {
                        ExitProvider.VOLUNTEER ->
                            "This removes the local Volunteer Exit profile from ZeroVPN. No cloud server will be deleted. If it is currently connected, ZeroVPN will disconnect it first."
                        ExitProvider.SHARED_WIREGUARD ->
                            "This removes the profile from this phone. It does not revoke access on the owner's server."
                        else ->
                            "This will terminate only this Oracle VM, delete its API key, and remove its network resources. Other exits are preserved."
                    },
                    color = TextDim,
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = removingExitId == null,
                    onClick = {
                        val target = destroyTarget
                        val destroyExitId = target?.id
                        showDestroyDialog = false
                        if (target == null || destroyExitId == null || removingExitId != null) return@TextButton
                        scope.launch {
                            removingExitId = destroyExitId
                            if (target.provider == ExitProvider.VOLUNTEER && destroyExitId == activeExitId) {
                                volunteerNetworkController.stopVolunteerVpnTest()
                                if (!waitForVolunteerStopped(volunteerNetworkController)) {
                                    removingExitId = null
                                    snackbarHostState.showSnackbar("Disconnect failed. Exit was not removed.")
                                    return@launch
                                }
                            } else {
                                val disconnected = vpnViewModel.disconnectIfActive(destroyExitId)
                                if (!disconnected) {
                                    removingExitId = null
                                    snackbarHostState.showSnackbar("Disconnect failed. Exit was not removed.")
                                    return@launch
                                }
                            }
                            when (target.provider) {
                                ExitProvider.VOLUNTEER -> viewModel.removeLocalExit(destroyExitId)
                                ExitProvider.SHARED_WIREGUARD -> viewModel.removeSharedExitProfile(destroyExitId)
                                ExitProvider.OCI -> {
                                    viewModel.destroyNode(context, destroyExitId)
                                    onDestroyStarted()
                                }
                            }
                            destroyTarget = null
                            removingExitId = null
                        }
                    },
                ) {
                    Text(if (destroyTarget?.provider == ExitProvider.OCI) "Destroy" else "Remove exit", color = Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDestroyDialog = false }) {
                    Text("Cancel", color = TextDim)
                }
            },
        )
    }

    renameTarget?.let { target ->
        var name by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename shared exit", color = TextPrimary) },
            text = {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameSharedExitProfile(target.id, name)
                    renameTarget = null
                }) {
                    Text("Save", color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel", color = TextDim)
                }
            },
        )
    }

    missingExitMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { missingExitMessage = null },
            title = { Text("Set up an exit first", color = TextPrimary) },
            text = {
                Text(
                    text = message,
                    color = TextDim,
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    missingExitMessage = null
                    onAddExit()
                }) {
                    Text("Add Exit", color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { missingExitMessage = null }) {
                    Text("Cancel", color = TextDim)
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 40.dp),
    ) {
        // Section title
        Text(
            text = "STATUS",
            style = SectionTitleStyle,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )

        // Status card
        StatusCard(
            statusText = when {
                switchTargetExitId != null -> if (activeExit == null) "Connecting" else "Switching"
                volunteerState is VolunteerVpnState.Failed -> "Connection Failed"
                vpnState is VpnConnectionState.Failed -> "Connection Failed"
                activeExit != null -> "Connected"
                wireGuardBusy || volunteerBusy -> "Disconnecting"
                hasExit -> "Ready"
                else -> "Disconnected"
            },
            modeLabel = if (hasExit) {
                when {
                    switchTargetExitId != null -> {
                        val target = exits.firstOrNull { it.id == switchTargetExitId }
                        "Switching to ${target?.name ?: "selected exit"}"
                    }
                    activeExit != null -> activeExit.let {
                        when (it.provider) {
                            ExitProvider.VOLUNTEER -> "${it.name} - Volunteer Network"
                            ExitProvider.SHARED_WIREGUARD -> "${it.name} - Shared Exit"
                            ExitProvider.OCI -> "${it.name} - WireGuard"
                        }
                    }
                    selectedExit != null -> "Selected: ${selectedExit.name}"
                    else -> "Select an exit below"
                }
            } else if (vpnState is VpnConnectionState.ActiveUnknown) {
                "Active tunnel - exit metadata unavailable"
            } else {
                "No mode selected"
            },
            buttonText = buttonText,
            onButtonClick = {
                scope.launch {
                    Log.d(
                        logTag,
                        "Connect button pressed hasExit=$hasExit, selectedExitId=$selectedExitId, selectedExit=${selectedExit?.id}, activeExitId=$activeExitId, activeProvider=$activeProviderType, vpnState=${vpnState::class.simpleName}, volunteerState=${volunteerState::class.simpleName}",
                    )
                    if (switchInProgress) {
                        return@launch
                    } else if (!hasExit) {
                        missingExitMessage = "No exits configured. Create an exit before connecting."
                    } else if (selectedExit == null) {
                        missingExitMessage = "Select an exit before connecting."
                    } else if (selectedIsActive) {
                        disconnectActiveExit(
                            activeExit = activeExit,
                            activeProviderType = activeProviderType,
                            vpnViewModel = vpnViewModel,
                            volunteerNetworkController = volunteerNetworkController,
                            onSwitchStart = { target ->
                                switchTargetExitId = target
                                lastProviderSwitchStartedAt = System.currentTimeMillis()
                                lastProviderSwitchError = null
                            },
                            onSwitchDone = {
                                switchTargetExitId = null
                                lastProviderSwitchCompletedAt = System.currentTimeMillis()
                            },
                            onSwitchError = { message ->
                                switchTargetExitId = null
                                lastProviderSwitchError = message
                                snackbarHostState.showSnackbar(message)
                            },
                        )
                    } else {
                        connectSelectedExit(
                            selectedExit = selectedExit,
                            activeExit = activeExit,
                            activeProviderType = activeProviderType,
                            vpnViewModel = vpnViewModel,
                            volunteerNetworkController = volunteerNetworkController,
                            snackbarHostState = snackbarHostState,
                            wireGuardPermissionLauncher = { permissionIntent ->
                                pendingPermissionExit = selectedExit
                                vpnViewModel.markPermissionRequired(selectedExit.id)
                                permissionLauncher.launch(permissionIntent)
                            },
                            volunteerPermissionLauncher = volunteerPermissionLauncher::launch,
                            onSwitchStart = { target ->
                                switchTargetExitId = target
                                lastProviderSwitchStartedAt = System.currentTimeMillis()
                                lastProviderSwitchError = null
                            },
                            onSwitchDone = {
                                switchTargetExitId = null
                                lastProviderSwitchCompletedAt = System.currentTimeMillis()
                            },
                            onSwitchError = { message ->
                                switchTargetExitId = null
                                lastProviderSwitchError = message
                                snackbarHostState.showSnackbar(message)
                            },
                        )
                    }
                }
            },
            connected = connectedForStatus,
            buttonEnabled = !switchInProgress,
        )

        if (vpnState is VpnConnectionState.Failed) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = (vpnState as VpnConnectionState.Failed).message,
                fontSize = 12.sp,
                color = Danger,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (activeExit?.provider == ExitProvider.VOLUNTEER) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Connected | Volunteer Exit | Exit IP ${volunteerDiagnostics.torSocksBaselineExitIp ?: "Unknown"} | Best for Web browsing | UDP Not supported | Status Experimental",
                fontSize = 11.sp,
                color = TextDim,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (
            (activeExit?.provider == ExitProvider.OCI || activeExit?.provider == ExitProvider.SHARED_WIREGUARD) &&
            (vpnState !is VpnConnectionState.Disconnected || vpnDiagnostics.endpoint != null)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "VPN: ${vpnDiagnostics.backendState} | ${vpnDiagnostics.tunnelAddress ?: "no address"} -> ${vpnDiagnostics.endpoint ?: "no endpoint"} | DNS ${vpnDiagnostics.dns ?: "none"} | Allowed ${vpnDiagnostics.allowedIps ?: "none"}",
                fontSize = 11.sp,
                color = TextDim,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Exits section
        Text(
            text = "EXITS",
            style = SectionTitleStyle,
            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
        )

        if (hasExit) {
            exits.sortedBy { it.createdAt }.forEach { exit ->
                val exitIsActive = exit.id == activeExitId
                val exitIsSwitchTarget = exit.id == switchTargetExitId
                ExitCard(
                    exit = exit,
                    selected = exit.id == selectedExit?.id,
                    active = exitIsActive,
                    switchingTarget = exitIsSwitchTarget,
                    busy = switchInProgress ||
                        removingExitId == exit.id ||
                        exit.lifecycleState == ExitLifecycleState.DESTROYING,
                    onSelect = { viewModel.selectExit(exit.id) },
                    onConnect = {
                        scope.launch {
                            viewModel.selectExit(exit.id)
                            if (switchInProgress) return@launch
                            connectSelectedExit(
                                selectedExit = exit,
                                activeExit = activeExit,
                                activeProviderType = activeProviderType,
                                vpnViewModel = vpnViewModel,
                                volunteerNetworkController = volunteerNetworkController,
                                snackbarHostState = snackbarHostState,
                                wireGuardPermissionLauncher = { permissionIntent ->
                                    pendingPermissionExit = exit
                                    vpnViewModel.markPermissionRequired(exit.id)
                                    permissionLauncher.launch(permissionIntent)
                                },
                                volunteerPermissionLauncher = volunteerPermissionLauncher::launch,
                                onSwitchStart = { target ->
                                    switchTargetExitId = target
                                    lastProviderSwitchStartedAt = System.currentTimeMillis()
                                    lastProviderSwitchError = null
                                },
                                onSwitchDone = {
                                    switchTargetExitId = null
                                    lastProviderSwitchCompletedAt = System.currentTimeMillis()
                                },
                                onSwitchError = { message ->
                                    switchTargetExitId = null
                                    lastProviderSwitchError = message
                                    snackbarHostState.showSnackbar(message)
                                },
                            )
                        }
                    },
                    onDisconnect = {
                        scope.launch {
                            if (switchInProgress) return@launch
                            disconnectActiveExit(
                                activeExit = activeExit,
                                activeProviderType = activeProviderType,
                                vpnViewModel = vpnViewModel,
                                volunteerNetworkController = volunteerNetworkController,
                                onSwitchStart = { target ->
                                    switchTargetExitId = target
                                    lastProviderSwitchStartedAt = System.currentTimeMillis()
                                    lastProviderSwitchError = null
                                },
                                onSwitchDone = {
                                    switchTargetExitId = null
                                    lastProviderSwitchCompletedAt = System.currentTimeMillis()
                                },
                                onSwitchError = { message ->
                                    switchTargetExitId = null
                                    lastProviderSwitchError = message
                                    snackbarHostState.showSnackbar(message)
                                },
                            )
                        }
                    },
                    onDestroy = {
                        destroyTarget = exit
                        showDestroyDialog = true
                    },
                    onRename = if (exit.provider == ExitProvider.SHARED_WIREGUARD) {
                        { renameTarget = exit }
                    } else {
                        null
                    },
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            OutlinedButton(
                onClick = onAddExit,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Exit", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Accent)
            }
        } else {
            // Empty exit list
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface, RoundedCornerShape(8.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "No exits configured",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                        color = TextDim,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.clickable(onClick = onAddExit),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = Accent,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Add Exit",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Accent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExitCard(
    exit: ConfiguredExit,
    selected: Boolean,
    active: Boolean,
    switchingTarget: Boolean,
    busy: Boolean,
    onSelect: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDestroy: () -> Unit,
    onRename: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(8.dp))
            .border(
                1.dp,
                when {
                    selected -> Accent
                    else -> Border.copy(alpha = 0.4f)
                },
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onSelect)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = exit.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (exit.provider == ExitProvider.VOLUNTEER) {
                        "Transport: Volunteer Network"
                    } else if (exit.provider == ExitProvider.SHARED_WIREGUARD) {
                        "Shared WireGuard exit"
                    } else {
                        "${exit.publicIp}:${exit.wireGuardPort}/udp"
                    },
                    fontSize = 13.sp,
                    color = TextDim,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${exit.region} - ${exitStatusText(exit, active, selected, switchingTarget)}",
                    fontSize = 12.sp,
                    color = TextDim,
                )
                exit.lastError?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = it, fontSize = 12.sp, color = Danger)
                }
            }
            Text(
                text = when {
                    active -> "Connected"
                    switchingTarget -> "Switching"
                    exit.provider == ExitProvider.SHARED_WIREGUARD -> "Shared Exit"
                    selected -> "Selected"
                    else -> ""
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (active || switchingTarget) Accent else TextDim,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = if (active) onDisconnect else onConnect,
                enabled = !busy && exit.lifecycleState == ExitLifecycleState.READY,
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (active) Danger else Accent,
                    contentColor = Bg,
                    disabledContainerColor = Border,
                    disabledContentColor = TextDim,
                ),
            ) {
                Text(
                    when {
                        switchingTarget -> "Switching..."
                        active -> "Disconnect"
                        else -> "Connect"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            OutlinedButton(
                onClick = onDestroy,
                enabled = !busy,
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Danger.copy(alpha = 0.5f)),
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = Danger,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (exit.provider == ExitProvider.OCI) "Destroy" else "Remove",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Danger,
                )
            }
        }
        if (onRename != null) {
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = onRename,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(40.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Rename", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Accent)
            }
        }
    }
}

private fun exitStatusText(
    exit: ConfiguredExit,
    active: Boolean,
    selected: Boolean,
    switchingTarget: Boolean,
): String = when {
    active -> "Connected"
    switchingTarget -> "Switching to this exit..."
    exit.lifecycleState == ExitLifecycleState.PROVISIONING -> "Provisioning"
    exit.lifecycleState == ExitLifecycleState.DESTROYING -> "Destroying"
    exit.lifecycleState == ExitLifecycleState.FAILED -> "Failed"
    selected -> "Ready, selected"
    else -> "Ready"
}

private enum class ProviderType {
    ORACLE_WIREGUARD,
    VOLUNTEER,
}

private fun ExitProvider?.toProviderType(): ProviderType? = when (this) {
    ExitProvider.OCI -> ProviderType.ORACLE_WIREGUARD
    ExitProvider.SHARED_WIREGUARD -> ProviderType.ORACLE_WIREGUARD
    ExitProvider.VOLUNTEER -> ProviderType.VOLUNTEER
    null -> null
}

private suspend fun connectSelectedExit(
    selectedExit: ConfiguredExit,
    activeExit: ConfiguredExit?,
    activeProviderType: ProviderType?,
    vpnViewModel: VpnViewModel,
    volunteerNetworkController: VolunteerNetworkController,
    snackbarHostState: SnackbarHostState,
    wireGuardPermissionLauncher: (android.content.Intent) -> Unit,
    volunteerPermissionLauncher: (android.content.Intent) -> Unit,
    onSwitchStart: (String) -> Unit,
    onSwitchDone: () -> Unit,
    onSwitchError: suspend (String) -> Unit,
) {
    val selectedProvider = selectedExit.provider.toProviderType()
    if (selectedProvider == null) {
        onSwitchError("This exit has an unknown provider type.")
        return
    }
    if (activeExit?.id == selectedExit.id && activeProviderType == selectedProvider) {
        onSwitchDone()
        return
    }

    onSwitchStart(selectedExit.id)
    try {
        when (activeProviderType) {
            ProviderType.VOLUNTEER -> {
                volunteerNetworkController.stopVolunteerVpnTest()
                if (!waitForVolunteerStopped(volunteerNetworkController)) {
                    onSwitchError("Could not stop Volunteer Exit before switching.")
                    return
                }
            }
            ProviderType.ORACLE_WIREGUARD -> {
                val disconnected = vpnViewModel.disconnectIfActive(null)
                if (!disconnected || !waitForWireGuardStopped(vpnViewModel)) {
                    onSwitchError("Could not disconnect the current Oracle exit.")
                    return
                }
            }
            null -> Unit
        }

        when (selectedProvider) {
            ProviderType.VOLUNTEER -> {
                val permissionIntent = volunteerNetworkController.volunteerVpnPermissionIntent()
                if (permissionIntent != null) {
                    volunteerNetworkController.markVolunteerVpnPermissionNeeded()
                    volunteerPermissionLauncher(permissionIntent)
                    return
                }
                volunteerNetworkController.startVolunteerVpnTest()
                if (!waitForVolunteerConnected(volunteerNetworkController)) {
                    onSwitchError("Volunteer Exit did not finish starting.")
                    return
                }
                onSwitchDone()
            }
            ProviderType.ORACLE_WIREGUARD -> {
                if (selectedExit.wireGuardConfig.isBlank()) {
                    val message = "WireGuard config is empty for ${selectedExit.name}. Re-provision the exit to enable VPN connection."
                    vpnViewModel.fail(message)
                    snackbarHostState.showSnackbar(message)
                    onSwitchError(message)
                    return
                }
                vpnViewModel.prepareDiagnostics(selectedExit)
                val permissionIntent = vpnViewModel.prepareVpn()
                if (permissionIntent != null) {
                    wireGuardPermissionLauncher(permissionIntent)
                    return
                }
                vpnViewModel.connect(selectedExit)
                if (!waitForWireGuardConnected(vpnViewModel, selectedExit.id)) {
                    onSwitchError("WireGuard exit did not finish connecting.")
                    return
                }
                onSwitchDone()
            }
        }
    } catch (e: Exception) {
        onSwitchError(e.message ?: "Provider switch failed.")
    }
}

private suspend fun disconnectActiveExit(
    activeExit: ConfiguredExit?,
    activeProviderType: ProviderType?,
    vpnViewModel: VpnViewModel,
    volunteerNetworkController: VolunteerNetworkController,
    onSwitchStart: (String?) -> Unit,
    onSwitchDone: () -> Unit,
    onSwitchError: suspend (String) -> Unit,
) {
    if (activeProviderType == null) return
    onSwitchStart(activeExit?.id)
    try {
        when (activeProviderType) {
            ProviderType.VOLUNTEER -> {
                volunteerNetworkController.stopVolunteerVpnTest()
                if (!waitForVolunteerStopped(volunteerNetworkController)) {
                    onSwitchError("Could not stop Volunteer Exit.")
                    return
                }
            }
            ProviderType.ORACLE_WIREGUARD -> {
                val disconnected = vpnViewModel.disconnectIfActive(null)
                if (!disconnected || !waitForWireGuardStopped(vpnViewModel)) {
                    onSwitchError("Could not disconnect the current Oracle exit.")
                    return
                }
            }
        }
        onSwitchDone()
    } catch (e: Exception) {
        onSwitchError(e.message ?: "Disconnect failed.")
    }
}

private suspend fun waitForVolunteerStopped(
    volunteerNetworkController: VolunteerNetworkController,
): Boolean = withTimeoutOrNull(15_000L) {
    volunteerNetworkController.volunteerVpnState.first { state ->
        state is VolunteerVpnState.Idle ||
            state is VolunteerVpnState.Stopped ||
            state is VolunteerVpnState.Failed
    }
    true
} ?: false

private suspend fun waitForVolunteerConnected(
    volunteerNetworkController: VolunteerNetworkController,
): Boolean = withTimeoutOrNull(180_000L) {
    var connected = false
    while (!connected) {
        val state = volunteerNetworkController.volunteerVpnState.value
        val diagnostics = volunteerNetworkController.volunteerVpnDiagnostics.value
        if (state is VolunteerVpnState.Running &&
            diagnostics.androidVpnActiveDetected &&
            diagnostics.hevRunning
        ) {
            connected = true
            continue
        }
        if (state is VolunteerVpnState.Failed) {
            return@withTimeoutOrNull false
        }
        kotlinx.coroutines.delay(500L)
    }
    true
} ?: false

private suspend fun waitForWireGuardStopped(vpnViewModel: VpnViewModel): Boolean =
    withTimeoutOrNull(20_000L) {
        vpnViewModel.state.first { state ->
            state is VpnConnectionState.Disconnected ||
                state is VpnConnectionState.Failed
        }
        vpnViewModel.state.value is VpnConnectionState.Disconnected
    } ?: false

private suspend fun waitForWireGuardConnected(vpnViewModel: VpnViewModel, exitId: String): Boolean =
    withTimeoutOrNull(90_000L) {
        vpnViewModel.state.first { state ->
            state is VpnConnectionState.Connected && state.exitId == exitId ||
                state is VpnConnectionState.Failed
        }
        vpnViewModel.state.value is VpnConnectionState.Connected &&
            (vpnViewModel.state.value as VpnConnectionState.Connected).exitId == exitId
    } ?: false

private fun connectVolunteerExit(
    volunteerNetworkController: VolunteerNetworkController,
    permissionLauncher: (android.content.Intent) -> Unit,
) {
    val permissionIntent = volunteerNetworkController.volunteerVpnPermissionIntent()
    if (permissionIntent != null) {
        volunteerNetworkController.markVolunteerVpnPermissionNeeded()
        permissionLauncher(permissionIntent)
    } else {
        volunteerNetworkController.startVolunteerVpnTest()
    }
}

internal suspend fun connectExit(
    exit: ConfiguredExit,
    vpnViewModel: VpnViewModel,
    snackbarHostState: SnackbarHostState,
    permissionLauncher: (android.content.Intent) -> Unit,
) {
    if (exit.wireGuardConfig.isBlank()) {
        val message = "WireGuard config is empty for ${exit.name}. Re-provision the exit to enable VPN connection."
        vpnViewModel.fail(message)
        snackbarHostState.showSnackbar(message)
        return
    }
    val disconnected = vpnViewModel.disconnectIfActive(exit.id)
    if (!disconnected) {
        snackbarHostState.showSnackbar("Could not disconnect the current exit")
        return
    }
    vpnViewModel.prepareDiagnostics(exit)
    val permissionIntent = vpnViewModel.prepareVpn()
    if (permissionIntent != null) {
        permissionLauncher(permissionIntent)
    } else {
        vpnViewModel.connect(exit)
    }
}
