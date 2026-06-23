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
import androidx.compose.runtime.Composable
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
import com.zerovpn.app.vpn.VpnConnectionState
import com.zerovpn.app.vpn.VpnViewModel
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    snackbarHostState: SnackbarHostState,
    onDestroyStarted: () -> Unit,
    onAddExit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProvisioningViewModel = viewModel(),
    vpnViewModel: VpnViewModel = viewModel(),
) {
    val logTag = "ZeroVPN/Home"
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val exits by viewModel.configuredExits.collectAsState()
    val selectedExitId by viewModel.selectedExitId.collectAsState()
    val vpnState by vpnViewModel.state.collectAsState()
    val vpnDiagnostics by vpnViewModel.diagnostics.collectAsState()

    // Initialize prefs on first load (restores persisted state)
    viewModel.initPrefs(context)

    var showDestroyDialog by remember { mutableStateOf(false) }
    var destroyTarget by remember { mutableStateOf<ConfiguredExit?>(null) }
    var pendingPermissionExit by remember { mutableStateOf<ConfiguredExit?>(null) }
    var missingExitMessage by remember { mutableStateOf<String?>(null) }

    val hasExit = exits.isNotEmpty()
    val activeExitId = when (val currentState = vpnState) {
        is VpnConnectionState.Connected -> currentState.exitId
        is VpnConnectionState.ActiveUnknown -> vpnDiagnostics.activeExitId
        else -> null
    }
    val vpnIsActive = vpnState is VpnConnectionState.Connected ||
        vpnState is VpnConnectionState.ActiveUnknown
    val selectedExit = exits.firstOrNull { it.id == selectedExitId }
        ?: exits.singleOrNull()
    val buttonBusy = vpnState is VpnConnectionState.Connecting ||
        vpnState is VpnConnectionState.Disconnecting ||
        vpnState is VpnConnectionState.PermissionRequired
    val buttonText = when (vpnState) {
        is VpnConnectionState.Connecting -> "Connecting"
        is VpnConnectionState.Disconnecting -> "Disconnecting"
        is VpnConnectionState.PermissionRequired -> "Waiting for Permission"
        is VpnConnectionState.Connected -> "Disconnect"
        is VpnConnectionState.ActiveUnknown -> "Disconnect"
        else -> "Connect"
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
            vpnViewModel.onPermissionDenied()
            scope.launch {
                snackbarHostState.showSnackbar("VPN permission was not granted")
            }
        }
    }

    if (showDestroyDialog) {
        AlertDialog(
            onDismissRequest = { showDestroyDialog = false },
            title = { Text("Destroy ${destroyTarget?.name ?: "Exit"}?", color = TextPrimary) },
            text = {
                Text(
                    "This will terminate only this Oracle VM, delete its API key, and remove its network resources. Other exits are preserved.",
                    color = TextDim,
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDestroyDialog = false
                    scope.launch {
                        val destroyExitId = destroyTarget?.id
                        val disconnected = vpnViewModel.disconnectIfActive(destroyExitId)
                        if (!disconnected) {
                            snackbarHostState.showSnackbar("Disconnect failed. Node was not destroyed.")
                            return@launch
                        }
                        viewModel.destroyNode(context, destroyExitId)
                        destroyTarget = null
                        onDestroyStarted()
                    }
                }) {
                    Text("Destroy", color = Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDestroyDialog = false }) {
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
            statusText = when (vpnState) {
                is VpnConnectionState.Connected -> "Connected"
                is VpnConnectionState.Connecting -> "Connecting"
                is VpnConnectionState.Disconnecting -> "Disconnecting"
                is VpnConnectionState.PermissionRequired -> "Permission Required"
                is VpnConnectionState.Failed -> "Connection Failed"
                is VpnConnectionState.ActiveUnknown -> "VPN Active"
                VpnConnectionState.Disconnected -> if (hasExit) "Ready" else "Disconnected"
            },
            modeLabel = if (hasExit) {
                selectedExit?.let { "${it.name} - WireGuard" } ?: "Select an exit below"
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
                        "Connect button pressed hasExit=$hasExit, selectedExitId=$selectedExitId, selectedExit=${selectedExit?.id}, activeExitId=$activeExitId, vpnState=${vpnState::class.simpleName}",
                    )
                    if (buttonBusy) {
                        return@launch
                    } else if (vpnIsActive) {
                        vpnViewModel.disconnect()
                    } else if (!hasExit) {
                        missingExitMessage = "No exits configured. Create an exit before connecting."
                    } else if (selectedExit == null) {
                        missingExitMessage = "Select an exit before connecting."
                    } else if (selectedExit.wireGuardConfig.isBlank()) {
                        val message = "WireGuard config is empty for ${selectedExit.name}. Re-provision the exit to enable VPN connection."
                        vpnViewModel.fail(message)
                        snackbarHostState.showSnackbar(message)
                    } else {
                        Log.d(
                            logTag,
                            "Selected exit ready id=${selectedExit.id}, name=${selectedExit.name}, endpoint=${selectedExit.publicIp}:${selectedExit.wireGuardPort}, configPresent=true",
                        )
                        vpnViewModel.prepareDiagnostics(selectedExit)
                        val permissionIntent = vpnViewModel.prepareVpn()
                        if (permissionIntent != null) {
                            pendingPermissionExit = selectedExit
                            vpnViewModel.markPermissionRequired(selectedExit.id)
                            Log.d(logTag, "Launching Android VPN consent intent")
                            permissionLauncher.launch(permissionIntent)
                        } else {
                            Log.d(logTag, "VPN permission already granted; connecting immediately")
                            vpnViewModel.connect(selectedExit)
                        }
                    }
                }
            },
            connected = vpnIsActive,
            buttonEnabled = !buttonBusy,
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

        if (vpnState !is VpnConnectionState.Disconnected || vpnDiagnostics.endpoint != null) {
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
                ExitCard(
                    exit = exit,
                    selected = exit.id == selectedExit?.id,
                    active = exit.id == activeExitId,
                    busy = buttonBusy || exit.lifecycleState == ExitLifecycleState.DESTROYING,
                    onSelect = { viewModel.selectExit(exit.id) },
                    onConnect = {
                        scope.launch {
                            viewModel.selectExit(exit.id)
                            connectExit(
                                exit = exit,
                                vpnViewModel = vpnViewModel,
                                snackbarHostState = snackbarHostState,
                                permissionLauncher = { permissionIntent ->
                                    pendingPermissionExit = exit
                                    vpnViewModel.markPermissionRequired(exit.id)
                                    permissionLauncher.launch(permissionIntent)
                                },
                            )
                        }
                    },
                    onDisconnect = { vpnViewModel.disconnect() },
                    onDestroy = {
                        destroyTarget = exit
                        showDestroyDialog = true
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
    busy: Boolean,
    onSelect: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDestroy: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(8.dp))
            .border(
                1.dp,
                when {
                    active -> Accent
                    selected -> Border
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
                    color = if (active) Accent else TextPrimary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${exit.publicIp}:${exit.wireGuardPort}/udp",
                    fontSize = 13.sp,
                    color = TextDim,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${exit.region} - ${exitStatusText(exit, active, selected)}",
                    fontSize = 12.sp,
                    color = TextDim,
                )
                exit.lastError?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = it, fontSize = 12.sp, color = Danger)
                }
            }
            Text(
                text = if (active) "Connected" else if (selected) "Selected" else "",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (active) Accent else TextDim,
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
                Text(if (active) "Disconnect" else "Connect", fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                Text("Destroy", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Danger)
            }
        }
    }
}

private fun exitStatusText(exit: ConfiguredExit, active: Boolean, selected: Boolean): String = when {
    active -> "Connected"
    exit.lifecycleState == ExitLifecycleState.PROVISIONING -> "Provisioning"
    exit.lifecycleState == ExitLifecycleState.DESTROYING -> "Destroying"
    exit.lifecycleState == ExitLifecycleState.FAILED -> "Failed"
    selected -> "Ready, selected"
    else -> "Ready"
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
