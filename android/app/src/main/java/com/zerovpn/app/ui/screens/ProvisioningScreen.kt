package com.zerovpn.app.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zerovpn.app.ui.provisioning.OracleOnboardingState
import com.zerovpn.app.ui.provisioning.Phase
import com.zerovpn.app.ui.provisioning.ProvisioningEvent
import com.zerovpn.app.ui.provisioning.ProvisioningState
import com.zerovpn.app.ui.provisioning.ProvisioningViewModel
import com.zerovpn.app.ui.provisioning.Status
import com.zerovpn.app.ui.theme.*
import com.zerovpn.app.oci.OciRegion
import com.zerovpn.app.oci.OciRegions
import com.zerovpn.app.vpn.VpnConnectionState
import com.zerovpn.app.vpn.VpnViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Status colors for terminal output
private val SuccessGreen = Color(0xFF4CAF50)
private val WarningYellow = Color(0xFFFFC107)

@Composable
fun ProvisioningScreen(
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProvisioningViewModel = viewModel(),
    vpnViewModel: VpnViewModel = viewModel(),
    onConnectedHome: () -> Unit = {},
    onDestroy: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    val events by viewModel.events.collectAsState()
    val currentPhase by viewModel.currentPhase.collectAsState()
    val publicIp by viewModel.publicIp.collectAsState()
    val wireGuardPort by viewModel.wireGuardPort.collectAsState()
    val isDevMode by viewModel.isDevMode.collectAsState()
    val onboardingState by viewModel.oracleOnboardingState.collectAsState()
    val selectedOracleRegion by viewModel.selectedOracleRegion.collectAsState()
    val exits by viewModel.configuredExits.collectAsState()
    val selectedExitId by viewModel.selectedExitId.collectAsState()
    val vpnState by vpnViewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val visibleEvents = remember(events, isDevMode) {
        if (isDevMode) events else events.filterNot { it.developerOnly }
    }
    var showDestroyDialog by remember { mutableStateOf(false) }
    var pendingPermissionExitId by remember { mutableStateOf<String?>(null) }
    var successConnectTargetId by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val exit = pendingPermissionExitId?.let { id -> exits.firstOrNull { it.id == id } }
        pendingPermissionExitId = null
        if (result.resultCode == Activity.RESULT_OK && exit != null) {
            vpnViewModel.connect(exit)
        } else {
            vpnViewModel.onPermissionDenied()
            scope.launch {
                snackbarHostState.showSnackbar("VPN permission was not granted")
            }
        }
    }

    // Initialize prefs on first composition
    LaunchedEffect(Unit) {
        viewModel.initPrefs(context)
    }

    LaunchedEffect(vpnState, successConnectTargetId) {
        val targetId = successConnectTargetId ?: return@LaunchedEffect
        val connected = vpnState as? VpnConnectionState.Connected ?: return@LaunchedEffect
        if (connected.exitId == targetId) {
            successConnectTargetId = null
            onConnectedHome()
        }
    }

    if (showDestroyDialog) {
        AlertDialog(
            onDismissRequest = { showDestroyDialog = false },
            title = { Text("Destroy this exit?", color = TextPrimary) },
            text = {
                Text(
                    "This will delete the Oracle VM and remove this exit from ZeroVPN. You cannot undo this action.",
                    color = TextDim,
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDestroyDialog = false
                    onDestroy?.invoke() ?: viewModel.destroyNode(context)
                }) {
                    Text("Destroy exit", color = Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDestroyDialog = false }) {
                    Text("Cancel", color = TextDim)
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 40.dp),
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Cloud,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Create Oracle Free Exit",
                style = SectionTitleStyle,
            )
            Spacer(modifier = Modifier.weight(1f))
        }

        when (val s = state) {
            is ProvisioningState.Idle -> {
                OracleOnboardingContent(
                    onboardingState = onboardingState,
                    selectedRegion = selectedOracleRegion,
                    regions = viewModel.oracleRegions,
                    onSelectRegion = viewModel::selectOracleRegion,
                    onExistingAccount = { viewModel.startProvisioning(context) },
                    onCreateAccount = { viewModel.launchOracleSignup(context) },
                    onAccountCreated = {
                        viewModel.acknowledgeAccountCreated()
                        viewModel.startProvisioning(context)
                    },
                    onCancel = { viewModel.cancel(); onBack() },
                )
            }

            is ProvisioningState.PreStart -> {
                OracleOnboardingContent(
                    onboardingState = onboardingState,
                    selectedRegion = selectedOracleRegion,
                    regions = viewModel.oracleRegions,
                    onSelectRegion = viewModel::selectOracleRegion,
                    onExistingAccount = { viewModel.startProvisioning(context) },
                    onCreateAccount = { viewModel.launchOracleSignup(context) },
                    onAccountCreated = {
                        viewModel.acknowledgeAccountCreated()
                        viewModel.startProvisioning(context)
                    },
                    onCancel = { viewModel.cancel(); onBack() },
                )
            }

            is ProvisioningState.Running -> {
                if (currentPhase == Phase.AUTH) {
                    AuthWaitingContent(
                        events = visibleEvents,
                        onboardingState = onboardingState,
                        onContinue = { viewModel.onAppResumed() },
                        onRetry = { viewModel.retry(context) },
                        onBack = { viewModel.cancel(); onBack() },
                    )
                } else {
                    ProgressContent(
                        events = visibleEvents,
                        currentPhase = currentPhase,
                    )
                }
            }

            is ProvisioningState.UkWarning -> {
                UkWarningContent(
                    homeRegion = s.homeRegion,
                    onContinue = { viewModel.continueAfterUkWarning(context) },
                    onCancel = { viewModel.cancel(); onBack() },
                )
            }

            is ProvisioningState.Success -> {
                SuccessContent(
                    publicIp = s.publicIp,
                    wireGuardPort = s.wireGuardPort,
                    region = s.region,
                    isDevMode = s.isDevMode,
                    vpnState = vpnState,
                    onConnect = {
                        scope.launch {
                            val exit = exits.firstOrNull { it.id == selectedExitId } ?: exits.lastOrNull()
                            if (exit == null) {
                                snackbarHostState.showSnackbar("No configured exit was found.")
                                return@launch
                            }
                            viewModel.selectExit(exit.id)
                            successConnectTargetId = exit.id
                            connectExit(
                                exit = exit,
                                vpnViewModel = vpnViewModel,
                                snackbarHostState = snackbarHostState,
                                permissionLauncher = { permissionIntent ->
                                    pendingPermissionExitId = exit.id
                                    vpnViewModel.markPermissionRequired(exit.id)
                                    permissionLauncher.launch(permissionIntent)
                                },
                            )
                        }
                    },
                    onDestroy = { showDestroyDialog = true },
                )
            }

            is ProvisioningState.Failure -> {
                FailureContent(
                    failedPhase = s.failedPhase,
                    lastSuccessPhase = s.lastSuccessPhase,
                    errorMessage = s.errorMessage,
                    events = visibleEvents,
                    selectedRegion = selectedOracleRegion,
                    regions = viewModel.oracleRegions,
                    onSelectRegion = viewModel::selectOracleRegion,
                    onRetry = { viewModel.retry(context) },
                    onCleanup = { viewModel.cleanup(context) },
                )
            }

            is ProvisioningState.Destroying -> {
                ProgressContent(
                    events = visibleEvents,
                    currentPhase = currentPhase,
                )
            }

            is ProvisioningState.Destroyed -> {
                DestroyedContent(
                    onBack = onBack,
                )
            }
        }
    }
}

// -- Pre-start -------------------------------------------------

@Composable
private fun OracleOnboardingContent(
    onboardingState: OracleOnboardingState,
    selectedRegion: String?,
    regions: List<OciRegion>,
    onSelectRegion: (String?) -> Unit,
    onExistingAccount: () -> Unit,
    onCreateAccount: () -> Unit,
    onAccountCreated: () -> Unit,
    onCancel: () -> Unit,
) {
    var showNext by remember { mutableStateOf(false) }
    var regionMenuExpanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Create Oracle Free Exit",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )

        Text(
            text = "ZeroVPN creates your own Oracle Always Free VM and turns it into a WireGuard exit. To do that, you need an Oracle Cloud account.",
            fontSize = 14.sp,
            color = TextDim,
            lineHeight = 20.sp,
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Oracle region",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            Text(
                text = "ZeroVPN will try to discover your home region automatically after sign-in. If discovery fails, choose the region shown in Oracle Cloud Console and retry.",
                fontSize = 13.sp,
                color = TextDim,
                lineHeight = 18.sp,
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { regionMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                ) {
                    Text(
                        text = selectedRegion?.let { OciRegions.labelFor(it) } ?: "Optional: choose region manually",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                DropdownMenu(
                    expanded = regionMenuExpanded,
                    onDismissRequest = { regionMenuExpanded = false },
                    modifier = Modifier.background(Surface),
                ) {
                    regions.forEach { region ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "${region.label} - ${region.id}",
                                    fontSize = 13.sp,
                                    color = TextPrimary,
                                )
                            },
                            onClick = {
                                onSelectRegion(region.id)
                                regionMenuExpanded = false
                            },
                        )
                    }
                }
            }
        }

        if (onboardingState == OracleOnboardingState.WaitingForAccountSetup ||
            onboardingState == OracleOnboardingState.ReadyToAuthenticate
        ) {
            Text(
                text = "When Oracle account setup is complete, continue here.",
                fontSize = 14.sp,
                color = TextPrimary,
                lineHeight = 20.sp,
            )
            Button(
                onClick = onAccountCreated,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Bg,
                ),
            ) {
                Text("I've created my account - continue", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = onExistingAccount,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Bg,
                ),
            ) {
                Text("I already have an Oracle Cloud account", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onCreateAccount,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Accent,
                ),
            ) {
                Text("Create a free Oracle Cloud account", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            OutlinedButton(
                onClick = { showNext = !showNext },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = TextDim,
                ),
            ) {
                Text("What happens next?", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }

        if (showNext) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                BulletPoint("Oracle may ask for email, phone, or payment verification.")
                BulletPoint("Oracle may require MFA or two-factor authentication setup.")
                BulletPoint("Complete those steps in Oracle.")
                BulletPoint("Return to ZeroVPN.")
                BulletPoint("ZeroVPN will then create the API key and provision the Always Free VM exit.")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = TextDim,
                ),
            ) {
                Text("Cancel", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun BulletPoint(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "•",
            fontSize = 15.sp,
            color = Accent,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            color = TextDim,
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AuthWaitingContent(
    events: List<ProvisioningEvent>,
    onboardingState: OracleOnboardingState,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Complete Oracle sign-in in your browser.",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
        Text(
            text = if (onboardingState == OracleOnboardingState.AuthReturned) {
                "Oracle returned to ZeroVPN. Continuing setup..."
            } else {
                "After sign-in, ZeroVPN should return here automatically. If it does not, switch back to ZeroVPN and tap Continue."
            },
            fontSize = 14.sp,
            color = TextDim,
            lineHeight = 20.sp,
        )
        if (onboardingState == OracleOnboardingState.WaitingForAuthReturn) {
            Text(
                text = "Still waiting for Oracle sign-in to complete.",
                fontSize = 14.sp,
                color = WarningYellow,
                lineHeight = 20.sp,
            )
        }
        val latestEvent = events.lastOrNull()
        Text(
            text = latestEvent?.message ?: "Opening Oracle sign-in...",
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = if (latestEvent?.status == Status.ERROR) Danger else Accent,
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface, RoundedCornerShape(8.dp))
                .padding(12.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextDim),
            ) {
                Text("Back", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
            ) {
                Text("Open Oracle sign-in again", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent,
                contentColor = Bg,
            ),
        ) {
            Text("I've finished signing in - continue", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// -- UK Warning ------------------------------------------------

@Composable
private fun UkWarningContent(
    homeRegion: String,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = WarningYellow,
                modifier = Modifier.size(32.dp),
            )
            Text(
                text = "UK Region Detected",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = WarningYellow,
            )
        }

        Text(
            text = "Your Oracle home region is $homeRegion. " +
                "This can be used for development/testing, but not as a non-UK " +
                "Always Free exit. Create an Oracle account with a non-UK home region " +
                "(e.g., us-ashburn-1, eu-frankfurt-1) for a production exit.",
            fontSize = 14.sp,
            color = TextDim,
            lineHeight = 20.sp,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = TextDim,
                ),
            ) {
                Text("Cancel", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Button(
                onClick = onContinue,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = WarningYellow,
                    contentColor = Bg,
                ),
            ) {
                Text("Continue (dev/test)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// -- Progress (terminal-style) --------------------------------

@Composable
private fun ProgressContent(
    events: List<ProvisioningEvent>,
    currentPhase: Phase?,
) {
    val scrollState = rememberScrollState()
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Phase progress indicator
    if (currentPhase != null && currentPhase != Phase.DONE) {
        Text(
            text = "Phase ${currentPhase.number}/6: ${currentPhase.label}",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Accent,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }

    // Terminal-style console
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(Surface, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .verticalScroll(scrollState),
    ) {
        LaunchedEffect(events.size) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }

        if (events.isEmpty()) {
            Text(
                text = "Initializing...",
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = TextDim,
            )
        }

        events.forEach { event ->
            val timeStr = timeFormat.format(Date(event.timestamp))
            val phaseStr = "[${event.phase.number}/${Phase.entries.size}]"
            val statusColor = when (event.status) {
                Status.RUNNING -> Accent
                Status.SUCCESS -> SuccessGreen
                Status.WARNING -> WarningYellow
                Status.ERROR -> Danger
            }
            val statusIcon = when (event.status) {
                Status.RUNNING -> ">"
                Status.SUCCESS -> "+"
                Status.WARNING -> "!"
                Status.ERROR -> "x"
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            ) {
                Text(
                    text = "$timeStr $phaseStr",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextDim,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = statusIcon,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = statusColor,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = event.message,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = statusColor,
                )
            }
        }
    }
}

// -- Success --------------------------------------------------

@Composable
private fun SuccessContent(
    publicIp: String,
    wireGuardPort: Int,
    region: String,
    isDevMode: Boolean,
    vpnState: VpnConnectionState,
    onConnect: () -> Unit,
    onDestroy: () -> Unit,
) {
    val connecting = vpnState is VpnConnectionState.Connecting ||
        vpnState is VpnConnectionState.PermissionRequired
    val connectionError = (vpnState as? VpnConnectionState.Failed)?.message
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = SuccessGreen,
                modifier = Modifier.size(32.dp),
            )
            Text(
                text = "Exit created successfully",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface, RoundedCornerShape(8.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InfoRow("Public IP", publicIp)
            InfoRow("WireGuard Port", "$wireGuardPort/udp")
            val regionLabel = if (isDevMode) "$region (dev/test mode)" else region
            InfoRow("Region", regionLabel)
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (connectionError != null) {
            Text(
                text = connectionError,
                fontSize = 12.sp,
                color = Danger,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onConnect,
                enabled = !connecting,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Bg,
                    disabledContainerColor = Border,
                    disabledContentColor = TextDim,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (vpnState) {
                        is VpnConnectionState.PermissionRequired -> "Waiting for Permission"
                        is VpnConnectionState.Connecting -> "Connecting"
                        else -> "Connect Now"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            OutlinedButton(
                onClick = onDestroy,
                enabled = !connecting,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Danger,
                    disabledContentColor = TextDim,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Destroy Node", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// -- Failure --------------------------------------------------

@Composable
private fun FailureContent(
    failedPhase: Phase,
    lastSuccessPhase: Phase?,
    errorMessage: String?,
    events: List<ProvisioningEvent>,
    selectedRegion: String?,
    regions: List<OciRegion>,
    onSelectRegion: (String?) -> Unit,
    onRetry: () -> Unit,
    onCleanup: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    var regionMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Danger,
                modifier = Modifier.size(32.dp),
            )
            Column {
                Text(
                    text = "Provisioning failed",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Danger,
                )
                Text(
                    text = "Failed at: ${failedPhase.label}",
                    fontSize = 13.sp,
                    color = TextDim,
                )
                if (lastSuccessPhase != null) {
                    Text(
                        text = "Last success: ${lastSuccessPhase.label}",
                        fontSize = 13.sp,
                        color = TextDim,
                    )
                }
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        fontSize = 12.sp,
                        color = Danger,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        if (failedPhase == Phase.API_KEY) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Oracle home region",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
                Text(
                    text = "ZeroVPN tries to discover this automatically. If discovery failed, choose the region shown in Oracle Cloud Console and retry.",
                    fontSize = 13.sp,
                    color = TextDim,
                    lineHeight = 18.sp,
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { regionMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    ) {
                        Text(
                            text = selectedRegion?.let { OciRegions.labelFor(it) } ?: "Choose region manually",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    DropdownMenu(
                        expanded = regionMenuExpanded,
                        onDismissRequest = { regionMenuExpanded = false },
                        modifier = Modifier.background(Surface),
                    ) {
                        regions.forEach { region ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "${region.label} - ${region.id}",
                                        fontSize = 13.sp,
                                        color = TextPrimary,
                                    )
                                },
                                onClick = {
                                    onSelectRegion(region.id)
                                    regionMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }

        // Event log (scrollable, terminal style)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Surface, RoundedCornerShape(8.dp))
                .padding(12.dp)
                .verticalScroll(scrollState),
        ) {
            events.forEach { event ->
                val timeStr = timeFormat.format(Date(event.timestamp))
                val phaseStr = "[${event.phase.number}/${Phase.entries.size}]"
                val statusColor = when (event.status) {
                    Status.RUNNING -> Accent
                    Status.SUCCESS -> SuccessGreen
                    Status.WARNING -> WarningYellow
                    Status.ERROR -> Danger
                }
                val statusIcon = when (event.status) {
                    Status.RUNNING -> ">"
                    Status.SUCCESS -> "+"
                    Status.WARNING -> "!"
                    Status.ERROR -> "x"
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                ) {
                    Text(
                        text = "$timeStr $phaseStr",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextDim,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusIcon,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = statusColor,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = event.message,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = statusColor,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onCleanup,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = TextDim,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.CleaningServices,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cleanup", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Button(
                onClick = onRetry,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Bg,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retry", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// -- Destroyed ------------------------------------------------

@Composable
private fun DestroyedContent(
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = SuccessGreen,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = "Exit destroyed",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
        Text(
            text = "This exit's resources have been released.",
            fontSize = 14.sp,
            color = TextDim,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onBack,
            modifier = Modifier.height(48.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent,
                contentColor = Bg,
            ),
        ) {
            Text("Back", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// -- Helper ---------------------------------------------------

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = TextDim,
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
        )
    }
}
