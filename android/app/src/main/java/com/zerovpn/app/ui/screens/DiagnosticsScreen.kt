package com.zerovpn.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.Activity
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zerovpn.app.ui.provisioning.ProvisioningViewModel
import com.zerovpn.app.BuildConfig
import com.zerovpn.app.friends.InviteSlot
import com.zerovpn.app.friends.SharedExitProfile
import com.zerovpn.app.ui.theme.Accent
import com.zerovpn.app.ui.theme.Bg
import com.zerovpn.app.ui.theme.Border
import com.zerovpn.app.ui.theme.Danger
import com.zerovpn.app.ui.theme.SectionTitleStyle
import com.zerovpn.app.ui.theme.Surface
import com.zerovpn.app.ui.theme.TextDim
import com.zerovpn.app.ui.theme.TextPrimary
import com.zerovpn.app.vpn.DnsLeakStatus
import com.zerovpn.app.vpn.ExitIpStatus
import com.zerovpn.app.vpn.LastHandshakeStatus
import com.zerovpn.app.vpn.ProviderSwitchDiagnostics
import com.zerovpn.app.vpn.UserDiagnosticsState
import com.zerovpn.app.vpn.VpnConnectionState
import com.zerovpn.app.vpn.VpnDiagnostics
import com.zerovpn.app.vpn.VpnViewModel
import com.zerovpn.app.volunteer.VolunteerNetworkController
import com.zerovpn.app.volunteer.VolunteerNetworkDiagnostics
import com.zerovpn.app.volunteer.VolunteerNetworkState
import com.zerovpn.app.volunteer.tun2socks.HevNativeDiagnostics
import com.zerovpn.app.volunteer.tun2socks.HevNativeLoader
import com.zerovpn.app.volunteer.vpn.VolunteerVpnDiagnostics
import com.zerovpn.app.volunteer.vpn.VolunteerVpnState
import java.text.DateFormat
import java.util.Date

private val DiagnosticsWarning = Color(0xFFFFC107)

@Composable
fun DiagnosticsScreen(
    modifier: Modifier = Modifier,
    provisioningViewModel: ProvisioningViewModel = viewModel(),
    vpnViewModel: VpnViewModel = viewModel(),
    volunteerNetworkController: VolunteerNetworkController = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isDevMode by provisioningViewModel.isDevMode.collectAsState()
    val sshDebugInfo by provisioningViewModel.sshDebugInfo.collectAsState()
    val exits by provisioningViewModel.configuredExits.collectAsState()
    val inviteSlots by provisioningViewModel.inviteSlots.collectAsState()
    val sharedExitProfiles by provisioningViewModel.sharedExitProfiles.collectAsState()
    val selectedExitId by provisioningViewModel.selectedExitId.collectAsState()
    val providerSwitchDiagnostics by provisioningViewModel.providerSwitchDiagnostics.collectAsState()
    val vpnState by vpnViewModel.state.collectAsState()
    val vpnDiagnostics by vpnViewModel.diagnostics.collectAsState()
    val userDiagnostics by vpnViewModel.userDiagnostics.collectAsState()
    val volunteerState by volunteerNetworkController.state.collectAsState()
    val volunteerDiagnostics by volunteerNetworkController.diagnostics.collectAsState()
    val volunteerVpnState by volunteerNetworkController.volunteerVpnState.collectAsState()
    val volunteerVpnDiagnostics by volunteerNetworkController.volunteerVpnDiagnostics.collectAsState()
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            volunteerNetworkController.startVolunteerVpnTest()
        } else {
            volunteerNetworkController.markVolunteerVpnPermissionNeeded()
        }
    }
    val scrollState = rememberScrollState()
    val activeExitId = when (val currentState = vpnState) {
        is VpnConnectionState.Connected -> currentState.exitId
        is VpnConnectionState.ActiveUnknown -> vpnDiagnostics.activeExitId
        else -> null
    }
    val selectedExit = exits.firstOrNull { it.id == selectedExitId } ?: exits.singleOrNull()
    val diagnosticsExit = activeExitId?.let { id -> exits.firstOrNull { it.id == id } } ?: selectedExit

    LaunchedEffect(diagnosticsExit?.id, diagnosticsExit?.wireGuardConfig) {
        diagnosticsExit?.let { vpnViewModel.prepareDiagnostics(it) }
    }

    LaunchedEffect(vpnState) {
        vpnViewModel.refreshUserDiagnostics(
            force = vpnState is VpnConnectionState.Connected || vpnState is VpnConnectionState.ActiveUnknown,
        )
    }

    DisposableEffect(lifecycleOwner, vpnState) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vpnViewModel.refreshUserDiagnostics(force = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 40.dp)
            .verticalScroll(scrollState),
    ) {
        Text(
            text = "DIAGNOSTICS",
            style = SectionTitleStyle,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )

        Spacer(modifier = Modifier.height(4.dp))

        ConnectionDiagnosticsCard(
            vpnState = vpnState,
            selectedExitName = diagnosticsExit?.name,
            selectedExitEndpoint = diagnosticsExit?.let { "${it.publicIp}:${it.wireGuardPort}" },
            diagnostics = vpnDiagnostics,
            userDiagnostics = userDiagnostics,
            onRefresh = { vpnViewModel.refreshUserDiagnostics(force = true) },
        )

        if (isDevMode) {
            Spacer(modifier = Modifier.height(12.dp))
            WireGuardDebugCard(vpnDiagnostics)
            Spacer(modifier = Modifier.height(12.dp))
            ProviderSwitchDebugCard(providerSwitchDiagnostics)
            Spacer(modifier = Modifier.height(12.dp))
            FriendsShareDebugCard(
                inviteSlots = inviteSlots,
                sharedExitProfiles = sharedExitProfiles,
            )
            if (BuildConfig.VOLUNTEER_DEBUG_ENABLED) {
                Spacer(modifier = Modifier.height(12.dp))
                VolunteerNetworkSpikeCard(
                    state = volunteerState,
                    diagnostics = volunteerDiagnostics,
                    vpnState = volunteerVpnState,
                    vpnDiagnostics = volunteerVpnDiagnostics,
                    hevNativeDiagnostics = HevNativeLoader.smokeTest(),
                    onStart = { volunteerNetworkController.startTest() },
                    onStop = { volunteerNetworkController.stopTest() },
                    onStartVolunteerVpn = {
                        val permissionIntent = volunteerNetworkController.volunteerVpnPermissionIntent()
                        if (permissionIntent != null) {
                            volunteerNetworkController.markVolunteerVpnPermissionNeeded()
                            vpnPermissionLauncher.launch(permissionIntent)
                        } else {
                            volunteerNetworkController.startVolunteerVpnTest()
                        }
                    },
                    onStopVolunteerVpn = { volunteerNetworkController.stopVolunteerVpnTest() },
                    onClearState = { volunteerNetworkController.clearTorState() },
                    onCopyDiagnostics = {
                        copyToClipboard(
                            context,
                            "ZeroVPN Volunteer Network diagnostics",
                            volunteerDiagnostics.copyText(
                                stateText = volunteerStateText(volunteerState),
                                vpnDiagnostics = volunteerVpnDiagnostics,
                            ),
                        )
                    },
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            SshDebugCard(
                sshDebugInfo = sshDebugInfo,
                onCopyPrivateKey = {
                    sshDebugInfo?.privateKey?.let { key ->
                        copyToClipboard(context, "ZeroVPN VM SSH private key", key)
                    }
                },
                onCopyCommand = {
                    sshDebugInfo?.windowsSshCommand?.let { command ->
                        copyToClipboard(context, "ZeroVPN VM SSH command", command)
                    }
                },
            )
        }
    }
}

@Composable
private fun FriendsShareDebugCard(
    inviteSlots: List<InviteSlot>,
    sharedExitProfiles: List<SharedExitProfile>,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "FRIENDS / SHARED EXIT STATE",
            style = SectionTitleStyle,
        )
        Text(
            text = "Developer-mode metadata only. Private keys, configs, and QR payloads are not displayed.",
            fontSize = 13.sp,
            color = TextDim,
            lineHeight = 18.sp,
        )
        DebugValue("Invite slot count", inviteSlots.size.toString())
        DebugValue("Owner exit count with slots", inviteSlots.map { it.ownerExitId }.distinct().size.toString())
        DebugValue("Shared exit profile count", sharedExitProfiles.size.toString())
        DebugBlock(
            "Invite slot states",
            inviteSlots
                .sortedWith(compareBy<InviteSlot>({ it.ownerExitId }, { it.slotIndex }, { it.slotId }))
                .joinToString("\n") { slot ->
                    "owner=${slot.ownerExitId} slot=${slot.slotIndex} state=${slot.state.name} " +
                        "ip=${slot.tunnelIp ?: "N/A"} peer=${slot.peerPublicKey.prefixForDiagnostics()}"
                }
                .ifBlank { "N/A" },
        )
        DebugBlock(
            "Shared exit profiles",
            sharedExitProfiles
                .sortedBy { it.importedAt }
                .joinToString("\n") { profile ->
                    "id=${profile.id} name=${profile.displayName} source=${profile.source.name} " +
                        "provider=${profile.providerType.name} endpoint=${profile.endpointHost ?: "N/A"} " +
                        "importedAt=${profile.importedAt.formatDebugTime()}"
                }
                .ifBlank { "N/A" },
        )
    }
}

@Composable
private fun VolunteerNetworkSpikeCard(
    state: VolunteerNetworkState,
    diagnostics: VolunteerNetworkDiagnostics,
    vpnState: VolunteerVpnState,
    vpnDiagnostics: VolunteerVpnDiagnostics,
    hevNativeDiagnostics: HevNativeDiagnostics,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStartVolunteerVpn: () -> Unit,
    onStopVolunteerVpn: () -> Unit,
    onClearState: () -> Unit,
    onCopyDiagnostics: () -> Unit,
) {
    val startEnabled = state is VolunteerNetworkState.Idle ||
        state is VolunteerNetworkState.Stopped ||
        state is VolunteerNetworkState.Failed
    val stopEnabled = state !is VolunteerNetworkState.Idle &&
        state !is VolunteerNetworkState.Stopped &&
        state !is VolunteerNetworkState.Stopping
    val clearEnabled = state is VolunteerNetworkState.Idle ||
        state is VolunteerNetworkState.Stopped ||
        state is VolunteerNetworkState.Failed ||
        state is VolunteerNetworkState.Ready
    val vpnStartEnabled = vpnState is VolunteerVpnState.Idle ||
        vpnState is VolunteerVpnState.PermissionNeeded ||
        vpnState is VolunteerVpnState.Stopped ||
        vpnState is VolunteerVpnState.Failed
    val vpnStopEnabled = vpnState is VolunteerVpnState.StartingTor ||
        vpnState is VolunteerVpnState.StartingVpn ||
        vpnState is VolunteerVpnState.Running

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "VOLUNTEER NETWORK DEBUG",
            style = SectionTitleStyle,
        )
        Text(
            text = "Experimental developer-only test. This routes other apps through embedded Tor using Android VpnService and HEV. Not product-ready.",
            fontSize = 13.sp,
            color = TextDim,
            lineHeight = 18.sp,
        )
        DebugValue("State", volunteerStateText(state))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onStart,
                enabled = startEnabled,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Bg,
                    disabledContainerColor = Border,
                    disabledContentColor = TextDim,
                ),
            ) {
                Text("Developer test start", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onStop,
                enabled = stopEnabled,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = TextPrimary,
                    disabledContentColor = TextDim,
                ),
            ) {
                Text("Developer test stop", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onCopyDiagnostics,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = TextPrimary,
                ),
            ) {
                Text("Copy diagnostics", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            OutlinedButton(
                onClick = onClearState,
                enabled = clearEnabled,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = TextPrimary,
                    disabledContentColor = TextDim,
                ),
            ) {
                Text("Clear Tor state", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onStartVolunteerVpn,
                enabled = vpnStartEnabled,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Bg,
                    disabledContainerColor = Border,
                    disabledContentColor = TextDim,
                ),
            ) {
                Text("Developer VPN start", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onStopVolunteerVpn,
                enabled = vpnStopEnabled,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = TextPrimary,
                    disabledContentColor = TextDim,
                ),
            ) {
                Text("Developer VPN stop", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
        DebugBlock("Embedded Tor", diagnostics.toDebugText(volunteerStateText(state)))
        DebugBlock("HEV Native", hevNativeDiagnostics.toDebugText())
        DebugBlock("Volunteer VPN", vpnDiagnostics.toDebugText())
        DebugBlock(
            "DNS caveats",
            "dnsMode=${vpnDiagnostics.dnsMode}\n" +
                "dnsServer=${vpnDiagnostics.dnsServer}\n" +
                "mapDnsAddress=198.18.0.2\n" +
                "mapDnsNetwork=100.64.0.0/10\n" +
                "dnsLeakRisk=${vpnDiagnostics.dnsLeakRisk}\n" +
                "dnsValidationMethod=manual-browser-check\n" +
                "browserValidationRequired=true\n" +
                "ZeroVPN app is excluded from the VPN, so in-app DNS tests do not prove the TUN path.",
        )
        DebugBlock(
            "UDP caveats",
            "udpMode=${vpnDiagnostics.udpMode}\n" +
                "udpRisk=udp-traffic-may-fail-or-be-dropped\n" +
                "udpProductStatus=not-supported\n" +
                "Tor SOCKS is TCP-stream oriented; arbitrary UDP is not validated.",
        )
        DebugBlock(
            "Lifecycle known unknowns",
            "appKilledWhileVolunteerVpnRunning=not-solved\n" +
                "appReopenedWhileAndroidVpnActive=diagnostic-only\n" +
                "torRunningButHevStopped=diagnostic-only\n" +
                "hevRunningButTorStopped=diagnostic-only\n" +
                "androidVpnActiveButAppStateLost=diagnostic-only\n" +
                "stopPressedTwice=idempotent-best-effort\n" +
                "startPressedTwice=guarded-by-state\n" +
                "permissionDenied=visible-state\n" +
                "serviceDestroyedByOs=best-effort-stop",
        )
        DebugBlock(
            "Browser validation",
            "1. Start test until embedded Tor is Ready.\n" +
                "2. Start VPN test and allow Android VPN permission.\n" +
                "3. Open Chrome to https://check.torproject.org/ and verify it reports Tor.\n" +
                "4. Browse a normal DNS-heavy site.\n" +
                "5. Optionally run a browser DNS leak test manually.\n" +
                "6. Stop VPN test and verify the Android VPN key clears.",
        )
    }
}

@Composable
private fun ConnectionDiagnosticsCard(
    vpnState: VpnConnectionState,
    selectedExitName: String?,
    selectedExitEndpoint: String?,
    diagnostics: VpnDiagnostics,
    userDiagnostics: UserDiagnosticsState,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "CONNECTION DIAGNOSTICS",
                style = SectionTitleStyle,
            )
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Bg,
                ),
            ) {
                Text("Refresh", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        DebugValue("Connection", connectionText(vpnState))
        DebugValue("Selected exit", selectedExitName ?: "N/A")
        DebugValue("Endpoint", diagnostics.endpoint ?: selectedExitEndpoint ?: "N/A")
        DebugValue("Exit IP", exitIpText(userDiagnostics))
        DebugValue("Country", countryText(userDiagnostics))
        DebugValue("DNS Leak Check", dnsLeakText(userDiagnostics))
        userDiagnostics.dnsLeakDetail?.let { detail ->
            Text(
                text = detail,
                fontSize = 12.sp,
                color = dnsStatusColor(userDiagnostics.dnsLeakStatus),
                lineHeight = 17.sp,
            )
        }
        DebugValue("Last Handshake", handshakeText(userDiagnostics))
        DebugValue("Logs", "Coming later")
        userDiagnostics.lastUpdated?.let {
            DebugValue("Last updated", DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(it)))
        }
        userDiagnostics.userVisibleError?.let { error ->
            Text(
                text = "Diagnostic warning: $error",
                fontSize = 12.sp,
                color = DiagnosticsWarning,
                lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun WireGuardDebugCard(
    diagnostics: VpnDiagnostics,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "WIREGUARD KEY DIAGNOSTICS",
            style = SectionTitleStyle,
        )
        Text(
            text = "Developer-mode public-key summary. WireGuard private keys are not displayed.",
            fontSize = 13.sp,
            color = TextDim,
            lineHeight = 18.sp,
        )

        DebugValue("Backend state", diagnostics.backendState)
        DebugValue("Reconcile source", diagnostics.reconciliationSource ?: "N/A")
        DebugValue("Selected exit id", diagnostics.selectedExitId ?: "N/A")
        DebugValue("Active exit id", diagnostics.activeExitId ?: "N/A")
        DebugValue("Persisted active exit id", diagnostics.persistedActiveExitId ?: "N/A")
        DebugValue("Persisted tunnel", diagnostics.persistedTunnelName ?: "N/A")
        diagnostics.persistedConnectedAt?.let {
            DebugValue("Persisted connected", DateFormat.getDateTimeInstance().format(Date(it)))
        }
        DebugValue("GoBackend running", diagnostics.goBackendRunningTunnelNames ?: "N/A")
        DebugValue("Android VPN transport", if (diagnostics.androidVpnDetected) "Detected" else "Not detected")
        DebugValue("Android VPN interface", diagnostics.androidVpnInterfaceName ?: "N/A")
        DebugBlock("Android VPN DNS", diagnostics.androidVpnDnsServers ?: "N/A")
        DebugBlock("Android VPN routes", diagnostics.androidVpnRoutes ?: "N/A")
        DebugBlock("Android VPN addresses", diagnostics.androidVpnLinkAddresses ?: "N/A")
        DebugValue("Tunnel name", diagnostics.tunnelName ?: "N/A")
        DebugValue("Config.parse", diagnostics.configParseStatus ?: "N/A")
        DebugValue("Validation", diagnostics.keyValidation ?: "N/A")
        DebugValue("Interface Address", diagnostics.tunnelAddress ?: "N/A")
        DebugValue("DNS", diagnostics.dns ?: "N/A")
        DebugValue("Endpoint", diagnostics.endpoint ?: "N/A")
        DebugValue("AllowedIPs", diagnostics.allowedIps ?: "N/A")
        DebugBlock("Android client public key", diagnostics.androidClientPublicKey ?: "N/A")
        DebugBlock("Android peer/server public key", diagnostics.androidPeerServerPublicKey ?: "N/A")
        DebugBlock("Selected node server public key", diagnostics.selectedNodeServerPublicKey ?: "N/A")
        DebugBlock("Provisioned server peer public key", diagnostics.provisionedServerPeerPublicKey ?: "N/A")
        DebugBlock("Exact runtime config passed to Config.parse", diagnostics.runtimeConfigRedacted ?: "N/A")
    }
}

@Composable
private fun ProviderSwitchDebugCard(
    diagnostics: ProviderSwitchDiagnostics,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "PROVIDER HANDOFF",
            style = SectionTitleStyle,
        )
        DebugValue("Selected exit id", diagnostics.selectedExitId ?: "N/A")
        DebugValue("Selected provider", diagnostics.selectedProviderType ?: "N/A")
        DebugValue("Active exit id", diagnostics.activeExitId ?: "N/A")
        DebugValue("Active provider", diagnostics.activeProviderType ?: "N/A")
        DebugValue("Switch target exit id", diagnostics.switchingTargetExitId ?: "N/A")
        DebugValue("Last switch started", diagnostics.lastProviderSwitchStartedAt.formatDebugTime())
        DebugValue("Last switch completed", diagnostics.lastProviderSwitchCompletedAt.formatDebugTime())
        DebugBlock("Last switch error", diagnostics.lastProviderSwitchError ?: "N/A")
    }
}

@Composable
private fun SshDebugCard(
    sshDebugInfo: ProvisioningViewModel.SshDebugInfo?,
    onCopyPrivateKey: () -> Unit,
    onCopyCommand: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(8.dp))
            .border(1.dp, Danger.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "SSH DEBUG ACCESS",
            style = SectionTitleStyle,
            color = Danger,
        )
        Text(
            text = "Temporary debug only. Anyone with this key can SSH into this VM. Destroy this node after debugging.",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Danger,
            lineHeight = 18.sp,
        )

        if (sshDebugInfo == null) {
            Text(
                text = "No generated VM SSH private key is available in memory. Provision a node in this app session, then return here.",
                fontSize = 13.sp,
                color = TextDim,
                lineHeight = 18.sp,
            )
            return
        }

        DebugValue("VM IP", sshDebugInfo.publicIp)
        DebugValue("SSH user", sshDebugInfo.username)
        DebugBlock("SSH command", sshDebugInfo.windowsSshCommand)
        DebugBlock("Private key", sshDebugInfo.privateKey)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onCopyPrivateKey,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Danger,
                    contentColor = Bg,
                ),
            ) {
                Text("Copy Key", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onCopyCommand,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = TextPrimary,
                ),
            ) {
                Text("Copy Command", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun Long?.formatDebugTime(): String =
    this?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "N/A"

private fun String?.prefixForDiagnostics(): String =
    this?.take(8)?.takeIf { it.isNotBlank() } ?: "N/A"

@Composable
private fun DebugValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 13.sp, color = TextDim)
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun DebugBlock(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 13.sp, color = TextDim)
        Text(
            text = value,
            modifier = Modifier
                .fillMaxWidth()
                .background(Bg, RoundedCornerShape(6.dp))
                .border(1.dp, Border, RoundedCornerShape(6.dp))
                .padding(10.dp),
            fontSize = 11.sp,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            lineHeight = 15.sp,
        )
    }
}

private fun connectionText(vpnState: VpnConnectionState): String = when (vpnState) {
    is VpnConnectionState.Connected -> "Connected"
    is VpnConnectionState.ActiveUnknown -> "VPN active"
    is VpnConnectionState.Connecting -> "Connecting"
    is VpnConnectionState.Disconnecting -> "Disconnecting"
    is VpnConnectionState.PermissionRequired -> "Permission required"
    is VpnConnectionState.Failed -> "Failed"
    VpnConnectionState.Disconnected -> "Disconnected"
}

private fun volunteerStateText(state: VolunteerNetworkState): String = when (state) {
    VolunteerNetworkState.Idle -> "Idle"
    VolunteerNetworkState.StartingTor -> "StartingTor"
    is VolunteerNetworkState.BootstrappingTor -> {
        state.progress?.let { "BootstrappingTor ($it%)" } ?: "BootstrappingTor"
    }
    VolunteerNetworkState.SocksReady -> "SocksReady"
    VolunteerNetworkState.TestingSocks -> "TestingSocks"
    VolunteerNetworkState.Ready -> "Ready"
    VolunteerNetworkState.Stopping -> "Stopping"
    VolunteerNetworkState.Stopped -> "Stopped"
    is VolunteerNetworkState.Failed -> listOfNotNull(
        "Failed",
        state.throwableClass,
        state.message.takeIf { it.isNotBlank() },
    ).joinToString(": ")
}

private fun VolunteerNetworkDiagnostics.copyText(
    stateText: String,
    vpnDiagnostics: VolunteerVpnDiagnostics,
): String = "Volunteer Network Spike\n${toDebugText(stateText)}\n\n${vpnDiagnostics.toDebugText()}"

private fun exitIpText(state: UserDiagnosticsState): String = when (state.exitIpStatus) {
    ExitIpStatus.Idle -> "Not checked"
    ExitIpStatus.Loading -> "Checking..."
    ExitIpStatus.Available -> state.exitIp ?: "Available"
    ExitIpStatus.Failed -> "Unavailable"
    ExitIpStatus.NotConnected -> "Not connected"
}

private fun countryText(state: UserDiagnosticsState): String = when (state.exitIpStatus) {
    ExitIpStatus.Available -> listOfNotNull(state.exitCountry, state.exitProviderOrAsn)
        .joinToString(" - ")
        .ifBlank { "Unavailable" }
    ExitIpStatus.Loading -> "Checking..."
    ExitIpStatus.NotConnected -> "Not connected"
    ExitIpStatus.Failed -> "Unavailable"
    ExitIpStatus.Idle -> "Not checked"
}

private fun dnsLeakText(state: UserDiagnosticsState): String = when (state.dnsLeakStatus) {
    DnsLeakStatus.Idle -> "Not checked"
    DnsLeakStatus.Loading -> "Checking..."
    DnsLeakStatus.Passed -> "Using configured DNS (${state.dnsResolverSummary ?: "Cloudflare"})"
    DnsLeakStatus.Warning -> "Check resolver (${state.dnsResolverSummary ?: "unknown"})"
    DnsLeakStatus.Failed -> "Unavailable"
    DnsLeakStatus.Unknown -> "Unknown"
    DnsLeakStatus.NotConnected -> "Not connected"
}

private fun handshakeText(state: UserDiagnosticsState): String = when (state.lastHandshakeStatus) {
    LastHandshakeStatus.Available -> state.lastHandshakeText ?: "Available"
    LastHandshakeStatus.Unavailable -> state.lastHandshakeText ?: "Unavailable"
    LastHandshakeStatus.NotConnected -> "Not connected"
}

@Composable
private fun dnsStatusColor(status: DnsLeakStatus): Color = when (status) {
    DnsLeakStatus.Passed -> Accent
    DnsLeakStatus.Warning -> DiagnosticsWarning
    DnsLeakStatus.Failed -> DiagnosticsWarning
    DnsLeakStatus.Unknown,
    DnsLeakStatus.Loading,
    DnsLeakStatus.Idle,
    DnsLeakStatus.NotConnected -> TextDim
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}
