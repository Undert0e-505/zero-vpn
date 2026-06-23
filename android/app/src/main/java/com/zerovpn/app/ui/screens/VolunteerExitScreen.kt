package com.zerovpn.app.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zerovpn.app.ui.provisioning.ProvisioningViewModel
import com.zerovpn.app.ui.theme.Accent
import com.zerovpn.app.ui.theme.Bg
import com.zerovpn.app.ui.theme.Border
import com.zerovpn.app.ui.theme.Danger
import com.zerovpn.app.ui.theme.SectionTitleStyle
import com.zerovpn.app.ui.theme.Surface
import com.zerovpn.app.ui.theme.TextDim
import com.zerovpn.app.ui.theme.TextPrimary
import com.zerovpn.app.vpn.VpnViewModel
import com.zerovpn.app.volunteer.VolunteerNetworkController
import com.zerovpn.app.volunteer.vpn.VolunteerVpnState
import kotlinx.coroutines.launch

@Composable
fun VolunteerIntroScreen(
    snackbarHostState: SnackbarHostState,
    onCancel: () -> Unit,
    onCreated: () -> Unit,
    modifier: Modifier = Modifier,
    provisioningViewModel: ProvisioningViewModel = viewModel(),
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("VOLUNTEER EXIT", style = SectionTitleStyle, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.VolunteerActivism, contentDescription = null, tint = Accent, modifier = Modifier.size(32.dp))
            Text("Volunteer Exit", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        }
        Text(
            text = "This creates a local experimental Volunteer Exit profile. It does not create a cloud server.",
            fontSize = 14.sp,
            color = TextPrimary,
            lineHeight = 20.sp,
        )
        VolunteerFacts()
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Cancel", color = TextDim)
            }
            Button(
                onClick = {
                    provisioningViewModel.createVolunteerExit()
                    onCreated()
                },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg),
            ) {
                Text("Create Volunteer Exit", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun VolunteerDetailsScreen(
    snackbarHostState: SnackbarHostState,
    onHome: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    provisioningViewModel: ProvisioningViewModel = viewModel(),
    vpnViewModel: VpnViewModel = viewModel(),
    volunteerNetworkController: VolunteerNetworkController = viewModel(),
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val exits by provisioningViewModel.configuredExits.collectAsState()
    val selectedExitId by provisioningViewModel.selectedExitId.collectAsState()
    val volunteerState by volunteerNetworkController.volunteerVpnState.collectAsState()
    val volunteerDiagnostics by volunteerNetworkController.volunteerVpnDiagnostics.collectAsState()
    val volunteerExit = exits.firstOrNull { it.id == selectedExitId && it.provider.name == "VOLUNTEER" }
        ?: exits.firstOrNull { it.provider.name == "VOLUNTEER" }
    var showRemoveDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            volunteerNetworkController.startVolunteerVpnTest()
        } else {
            volunteerNetworkController.markVolunteerVpnPermissionNeeded()
            scope.launch { snackbarHostState.showSnackbar("VPN permission was not granted") }
        }
    }

    LaunchedEffect(volunteerState, volunteerDiagnostics.androidVpnActiveDetected, volunteerDiagnostics.hevRunning) {
        if (volunteerState is VolunteerVpnState.Running &&
            volunteerDiagnostics.androidVpnActiveDetected &&
            volunteerDiagnostics.hevRunning
        ) {
            onHome()
        }
    }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text("Remove Volunteer Exit?", color = TextPrimary) },
            text = {
                Text(
                    "This removes the local Volunteer Exit profile from ZeroVPN. No cloud server will be deleted. If it is currently connected, ZeroVPN will disconnect it first.",
                    color = TextDim,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val targetId = volunteerExit?.id
                    showRemoveDialog = false
                    scope.launch {
                        volunteerNetworkController.stopVolunteerVpnTest()
                        if (targetId != null) {
                            provisioningViewModel.removeLocalExit(targetId)
                        }
                        onHome()
                    }
                }) {
                    Text("Remove exit", color = Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
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
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("VOLUNTEER EXIT", style = SectionTitleStyle, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.VolunteerActivism, contentDescription = null, tint = Accent, modifier = Modifier.size(32.dp))
            Column {
                Text("Volunteer Exit ready", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text("This exit is ready to connect.", fontSize = 13.sp, color = TextDim)
            }
        }
        VolunteerFacts()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface, RoundedCornerShape(8.dp))
                .border(1.dp, Border, RoundedCornerShape(8.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DetailRow("Status", volunteerStatusText(volunteerState, volunteerDiagnostics.androidVpnActiveDetected, volunteerDiagnostics.hevRunning))
            DetailRow("Transport", "Volunteer Network")
            DetailRow("Exit IP", volunteerDiagnostics.torSocksBaselineExitIp ?: "Unknown")
            DetailRow("UDP", "Not supported")
            DetailRow("DNS", "Under validation")
            DetailRow("Mode", "Experimental")
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        val disconnected = vpnViewModel.disconnectIfActive(null)
                        if (!disconnected) {
                            snackbarHostState.showSnackbar("Could not disconnect the current exit")
                            return@launch
                        }
                        val permissionIntent = volunteerNetworkController.volunteerVpnPermissionIntent()
                        if (permissionIntent != null) {
                            volunteerNetworkController.markVolunteerVpnPermissionNeeded()
                            permissionLauncher.launch(permissionIntent)
                        } else {
                            volunteerNetworkController.startVolunteerVpnTest()
                        }
                    }
                },
                enabled = volunteerState !is VolunteerVpnState.StartingTor &&
                    volunteerState !is VolunteerVpnState.StartingVpn &&
                    volunteerState !is VolunteerVpnState.Stopping,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg),
            ) {
                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    when (volunteerState) {
                        VolunteerVpnState.PermissionNeeded -> "Waiting for Permission"
                        VolunteerVpnState.StartingTor, VolunteerVpnState.StartingVpn -> "Connecting"
                        else -> "Connect"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            OutlinedButton(
                onClick = { showRemoveDialog = true },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = Danger, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Remove exit", color = Danger, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
            Text("Back", color = TextDim)
        }
    }
}

@Composable
private fun VolunteerFacts() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("No cloud server is created.", fontSize = 13.sp, color = TextPrimary)
        Text("Usually slower than a normal VPN; some websites may block volunteer exits.", fontSize = 13.sp, color = TextDim, lineHeight = 18.sp)
        Text("Best suited to web browsing and basic TCP traffic.", fontSize = 13.sp, color = TextDim, lineHeight = 18.sp)
        Text("UDP is not supported.", fontSize = 13.sp, color = TextDim)
        Text("DNS leak status is still under validation.", fontSize = 13.sp, color = TextDim)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = TextDim)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
    }
}

private fun volunteerStatusText(
    state: VolunteerVpnState,
    androidVpnActive: Boolean,
    hevRunning: Boolean,
): String = when (state) {
    VolunteerVpnState.Idle, VolunteerVpnState.Stopped -> "Disconnected"
    VolunteerVpnState.PermissionNeeded -> "Permission required"
    VolunteerVpnState.StartingTor, VolunteerVpnState.StartingVpn -> "Connecting"
    VolunteerVpnState.Running -> if (androidVpnActive && hevRunning) "Connected" else "Connecting"
    VolunteerVpnState.Stopping -> "Disconnecting"
    is VolunteerVpnState.Failed -> "Failed"
}
