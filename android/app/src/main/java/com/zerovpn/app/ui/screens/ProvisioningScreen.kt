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
import androidx.compose.material3.Switch
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
import com.zerovpn.app.chat.node.PrivateChatInstallStatus
import com.zerovpn.app.chat.retry.CapacityRetryDiagnosticEntry
import com.zerovpn.app.chat.retry.CapacityRetrySchedulerState
import com.zerovpn.app.chat.retry.CapacityRetrySchedulerStatus
import com.zerovpn.app.chat.retry.CapacityRetrySession
import com.zerovpn.app.chat.retry.CapacityRetryState
import com.zerovpn.app.chat.retry.hasAuthoritativePrivateChatRetry
import com.zerovpn.app.oci.OciRegion
import com.zerovpn.app.oci.OciRegions
import com.zerovpn.app.vpn.VpnConnectionState
import com.zerovpn.app.vpn.VpnViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Instant
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
    onViewDiagnostics: () -> Unit = {},
    onDestroy: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    val events by viewModel.events.collectAsState()
    val currentPhase by viewModel.currentPhase.collectAsState()
    val publicIp by viewModel.publicIp.collectAsState()
    val wireGuardPort by viewModel.wireGuardPort.collectAsState()
    val isDevMode by viewModel.isDevMode.collectAsState()
    val privateChatRequested by viewModel.privateChatRequested.collectAsState()
    val capacityRetryPolicyEnabled by viewModel.capacityRetryPolicyEnabled.collectAsState()
    val onboardingState by viewModel.oracleOnboardingState.collectAsState()
    val selectedOracleRegion by viewModel.selectedOracleRegion.collectAsState()
    val exits by viewModel.configuredExits.collectAsState()
    val selectedExitId by viewModel.selectedExitId.collectAsState()
    val capacityRetrySessions by viewModel.capacityRetrySessions.collectAsState()
    val capacityRetrySchedulerStatuses by viewModel.capacityRetrySchedulerStatuses.collectAsState()
    val capacityRetryDiagnostics by viewModel.capacityRetryDiagnostics.collectAsState()
    val vpnState by vpnViewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val visibleEvents = remember(events, isDevMode) {
        if (isDevMode) events else events.filterNot { it.developerOnly }
    }
    var showDestroyDialog by remember { mutableStateOf(false) }
    var pendingPermissionExitId by remember { mutableStateOf<String?>(null) }
    var successConnectTargetId by remember { mutableStateOf<String?>(null) }
    val hasActiveCapacityRetry = capacityRetrySessions.hasAuthoritativePrivateChatRetry()

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
                text = "Create Oracle Exit",
                style = SectionTitleStyle,
            )
            Spacer(modifier = Modifier.weight(1f))
        }

        val visibleRetrySession = capacityRetrySessions.lastOrNull {
            it.state != CapacityRetryState.NONE && it.state != CapacityRetryState.SUCCEEDED
        }
        if ((state is ProvisioningState.Idle || state is ProvisioningState.PreStart) && visibleRetrySession != null) {
            CapacityRetryStatusCard(
                session = visibleRetrySession,
                schedulerStatus = capacityRetrySchedulerStatuses[visibleRetrySession.sessionId],
                diagnosticEntries = capacityRetryDiagnostics.filter {
                    it.sessionId == visibleRetrySession.sessionId
                },
                onRetry = { viewModel.retry(context) },
                onStop = { viewModel.stopActiveCapacityRetry(context) },
                onVpnOnlyInstead = { viewModel.setupVpnOnlyInstead(context) },
                onViewDiagnostics = onViewDiagnostics,
                onViewDevLog = { viewModel.setDevMode(true) },
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        when (val s = state) {
            is ProvisioningState.Idle -> {
                OracleOnboardingContent(
                    onboardingState = onboardingState,
                    selectedRegion = selectedOracleRegion,
                    regions = viewModel.oracleRegions,
                    privateChatRequested = privateChatRequested,
                    capacityRetryPolicyEnabled = capacityRetryPolicyEnabled,
                    privateChatToggleEnabled = isPrivateChatRequestToggleEnabled(hasActiveCapacityRetry),
                    hasActiveCapacityRetry = hasActiveCapacityRetry,
                    hidePrivateChatSwitch = shouldHidePrivateChatSwitch(visibleRetrySession),
                    onPrivateChatRequestedChange = viewModel::setPrivateChatRequested,
                    onCapacityRetryPolicyChange = viewModel::setCapacityRetryPolicyEnabled,
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
                    privateChatRequested = privateChatRequested,
                    capacityRetryPolicyEnabled = capacityRetryPolicyEnabled,
                    privateChatToggleEnabled = isPrivateChatRequestToggleEnabled(hasActiveCapacityRetry),
                    hasActiveCapacityRetry = hasActiveCapacityRetry,
                    hidePrivateChatSwitch = shouldHidePrivateChatSwitch(visibleRetrySession),
                    onPrivateChatRequestedChange = viewModel::setPrivateChatRequested,
                    onCapacityRetryPolicyChange = viewModel::setCapacityRetryPolicyEnabled,
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
                    privateChatStatus = s.privateChatStatus,
                    privateChatError = s.privateChatError,
                    events = visibleEvents,
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
                    onRetryPrivateChat = {
                        val exit = exits.firstOrNull { it.id == selectedExitId } ?: exits.lastOrNull()
                        if (exit != null) viewModel.retryPrivateChat(context, exit.id)
                    },
                    onRemovePrivateChat = {
                        val exit = exits.firstOrNull { it.id == selectedExitId } ?: exits.lastOrNull()
                        if (exit != null) viewModel.removePrivateChat(context, exit.id)
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
                    capacityRetryPolicyEnabled = capacityRetryPolicyEnabled,
                    capacityRetrySchedulerStatuses = capacityRetrySchedulerStatuses,
                    capacityRetryDiagnostics = capacityRetryDiagnostics,
                    capacityRetrySession = capacityRetrySessions.lastOrNull {
                        it.state in setOf(
                            CapacityRetryState.WAITING_FOR_RETRY,
                            CapacityRetryState.ACTIVE,
                            CapacityRetryState.ACQUIRING,
                            CapacityRetryState.PAUSED_AUTH_REQUIRED,
                            CapacityRetryState.FAILED_TERMINAL,
                            CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED,
                        )
                    },
                    onRetry = { viewModel.retry(context) },
                    onCleanup = { viewModel.cleanup(context) },
                    onKeepTrying24h = { viewModel.startCapacityRetry(context) },
                    onStopRetry = { viewModel.stopActiveCapacityRetry(context) },
                    onVpnOnlyInstead = { viewModel.setupVpnOnlyInstead(context) },
                    onViewDiagnostics = onViewDiagnostics,
                    onViewDevLog = { viewModel.setDevMode(true) },
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

// -- Capacity retry status -------------------------------------

@Composable
private fun CapacityRetryStatusCard(
    session: CapacityRetrySession,
    schedulerStatus: CapacityRetrySchedulerStatus?,
    diagnosticEntries: List<CapacityRetryDiagnosticEntry>,
    onRetry: () -> Unit,
    onStop: () -> Unit,
    onVpnOnlyInstead: () -> Unit,
    onViewDiagnostics: () -> Unit,
    onViewDevLog: () -> Unit,
) {
    var nowMillis by remember(session.sessionId, session.nextEligibleAttemptAtUtc) {
        mutableStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(session.sessionId, session.nextEligibleAttemptAtUtc, session.state) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val now = Instant.ofEpochMilli(nowMillis)
    val deadline = Instant.parse(session.deadlineUtc)
    val rawRemaining = Duration.between(now, deadline)
    val remaining = if (rawRemaining.isNegative) Duration.ZERO else rawRemaining
    val localFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val workerTimeFormat = remember { SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()) }
    val lastAttempt = session.lastLaunchAttemptFinishedAtUtc
        ?: session.lastWorkerFinishedAtUtc
        ?: session.lastWorkerStartedAtUtc
    val nextEligible = session.nextEligibleAttemptAtUtc?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val cooldown = nextEligible?.let { Duration.between(now, it) }
        ?.takeUnless { it.isNegative }
        ?: Duration.ZERO
    val eligibilityReached = nextEligible?.let { !now.isBefore(it) } == true
    val active = session.state in setOf(
        CapacityRetryState.WAITING_FOR_RETRY,
        CapacityRetryState.ACTIVE,
        CapacityRetryState.ACQUIRING,
        CapacityRetryState.PAUSED_AUTH_REQUIRED,
    )
    val reconciliationRequired =
        session.state == CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED
    val displayedScheduler = schedulerStatus ?: CapacityRetrySchedulerStatus.checking(now)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = when (session.state) {
                CapacityRetryState.WAITING_FOR_RETRY -> "Private Chat retry waiting for first interval"
                CapacityRetryState.ACTIVE, CapacityRetryState.ACQUIRING -> "Private Chat capacity retry active"
                CapacityRetryState.PAUSED_AUTH_REQUIRED -> "Private Chat retry paused"
                CapacityRetryState.TIMED_OUT -> "Private Chat retry timed out"
                CapacityRetryState.CANCELLED -> "Private Chat retry cancelled"
                CapacityRetryState.INSTANCE_ACQUIRED, CapacityRetryState.RESUME_PROVISIONING_REQUIRED -> "Oracle VM acquired"
                CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED -> "Retry needs reconciliation"
                CapacityRetryState.FAILED_TERMINAL -> "Private Chat retry stopped"
                else -> "Private Chat retry status"
            },
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
        InfoRow("Scheduler", displayedScheduler.displayLabel)
        Text(
            text = displayedScheduler.detail,
            fontSize = 12.sp,
            color = TextDim,
            lineHeight = 17.sp,
        )
        displayedScheduler.reconciliationMessage?.let { message ->
            Text(
                text = message,
                fontSize = 12.sp,
                color = WarningYellow,
                lineHeight = 17.sp,
            )
        }
        if (
            eligibilityReached &&
            displayedScheduler.state == CapacityRetrySchedulerState.ENQUEUED
        ) {
            Text(
                text = "Waiting for Android to schedule the next attempt. Android controls background timing.",
                fontSize = 12.sp,
                color = WarningYellow,
                lineHeight = 17.sp,
            )
        }
        InfoRow("Background retries attempted", session.retryCycleCount.toString())
        InfoRow("Last attempt", lastAttempt?.let { localFormat.format(Date.from(Instant.parse(it))) } ?: "not yet")
        if (session.lastResult == "RATE_LIMITED") {
            Text(
                text = "Oracle is temporarily rate limiting VM requests.",
                fontSize = 13.sp,
                color = WarningYellow,
                lineHeight = 18.sp,
            )
        }
        if (session.lastResult == "TRANSIENT_NETWORK_FAILURE") {
            Text(
                text = "Network unavailable during Oracle preparation. No VM request was sent. Automatic retry remains active.",
                fontSize = 13.sp,
                color = WarningYellow,
                lineHeight = 18.sp,
            )
            InfoRow("Transient deferrals", session.transientNetworkDeferrals.toString())
        }
        InfoRow("Last result", session.lastResult ?: session.lastSafeErrorCategory ?: "not yet")
        InfoRow(
            "Next target attempt",
            if (reconciliationRequired) {
                "blocked pending reconciliation"
            } else {
                session.nextEligibleAttemptAtUtc
                    ?.let { localFormat.format(Date.from(Instant.parse(it))) }
                    ?: "waiting for Android"
            },
        )
        InfoRow("Next configuration", "A1 Flex, 1 OCPU / ${session.pendingMemoryGb} GB")
        if (!cooldown.isZero) InfoRow("Cooldown", formatCooldown(cooldown))
        InfoRow("Time remaining", formatRemaining(remaining))
        InfoRow("Deadline", localFormat.format(Date.from(deadline)))
        InfoRow("Preferred", "A1 Flex, 1 OCPU / 6 GB")
        InfoRow("Fallback", "A1 Flex, 1 OCPU / 4 GB")
        if (session.terminalReason != null) {
            Text(
                text = session.terminalReason,
                fontSize = 12.sp,
                color = TextDim,
                lineHeight = 17.sp,
            )
        }
        if (reconciliationRequired) {
            Text(
                text = "VPN-only provisioning and further launch attempts are unavailable until reconciliation confirms that Oracle did not create an A1 instance.",
                fontSize = 12.sp,
                color = WarningYellow,
                lineHeight = 17.sp,
            )
        }
        if (diagnosticEntries.isNotEmpty()) {
            Text(
                text = "BACKGROUND WORKER LOG",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextDim,
            )
            diagnosticEntries.takeLast(8).forEach { entry ->
                val timestamp = runCatching {
                    workerTimeFormat.format(Date.from(Instant.parse(entry.timestampUtc)))
                }.getOrDefault(entry.timestampUtc)
                Text(
                    text = timestamp + "  " + entry.message,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextDim,
                    lineHeight = 14.sp,
                )
            }
        }
        OutlinedButton(
            onClick = onRetry,
            enabled = session.state in setOf(CapacityRetryState.WAITING_FOR_RETRY, CapacityRetryState.ACTIVE) && cooldown.isZero,
            modifier = Modifier.fillMaxWidth().height(40.dp),
        ) {
            Text(
                text = if (cooldown.isZero) "Retry now" else "Next attempt in ${formatCooldown(cooldown)}",
                fontSize = 12.sp,
                color = if (cooldown.isZero) Accent else TextDim,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onStop,
                enabled = active,
                modifier = Modifier.weight(1f).height(40.dp),
            ) { Text("Stop retrying", fontSize = 12.sp, color = if (active) Danger else TextDim) }
            if (!reconciliationRequired) {
                OutlinedButton(
                    onClick = onVpnOnlyInstead,
                    modifier = Modifier.weight(1f).height(40.dp),
                ) { Text("VPN only", fontSize = 12.sp, color = Accent) }
            }
        }
        OutlinedButton(
            onClick = if (reconciliationRequired) onViewDiagnostics else onViewDevLog,
            modifier = Modifier.fillMaxWidth().height(40.dp),
        ) {
            Text(
                if (reconciliationRequired) "View diagnostics" else "View Dev Mode log",
                fontSize = 12.sp,
                color = TextPrimary,
            )
        }
    }
}

private fun formatRemaining(duration: Duration): String {
    if (duration.isZero) return "expired"
    val hours = duration.toHours()
    val minutes = duration.minusHours(hours).toMinutes()
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

private fun formatCooldown(duration: Duration): String {
    val seconds = duration.seconds.coerceAtLeast(0L)
    return "${seconds / 60}m ${seconds % 60}s"
}

// -- Pre-start -------------------------------------------------

internal enum class PrivateChatSwitchRole {
    PRIVATE_CHAT_REQUEST,
    CAPACITY_RETRY_POLICY,
}

internal fun isPrivateChatRequestToggleEnabled(hasActiveCapacityRetry: Boolean): Boolean =
    !hasActiveCapacityRetry

/**
 * Whether the Private Chat setup switch should be hidden entirely because a retry card owns the actions.
 */
internal fun shouldHidePrivateChatSwitch(retrySession: CapacityRetrySession?): Boolean {
    if (retrySession == null) return false
    return retrySession.state in setOf(
        CapacityRetryState.ACTIVE,
        CapacityRetryState.WAITING_FOR_RETRY,
        CapacityRetryState.PAUSED_AUTH_REQUIRED,
        CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED,
        CapacityRetryState.FAILED_TERMINAL,
        CapacityRetryState.TIMED_OUT,
    )
}

internal fun visiblePrivateChatSwitchRoles(
    privateChatRequested: Boolean,
    onboardingState: OracleOnboardingState,
    hasActiveCapacityRetry: Boolean,
): List<PrivateChatSwitchRole> = buildList {
    add(PrivateChatSwitchRole.PRIVATE_CHAT_REQUEST)
    val beforeAuthentication = onboardingState in setOf(
        OracleOnboardingState.NotStarted,
        OracleOnboardingState.SignupLaunched,
        OracleOnboardingState.WaitingForAccountSetup,
        OracleOnboardingState.ReadyToAuthenticate,
    )
    if (privateChatRequested && beforeAuthentication && !hasActiveCapacityRetry) {
        add(PrivateChatSwitchRole.CAPACITY_RETRY_POLICY)
    }
}

@Composable
private fun OracleOnboardingContent(
    onboardingState: OracleOnboardingState,
    selectedRegion: String?,
    regions: List<OciRegion>,
    privateChatRequested: Boolean,
    capacityRetryPolicyEnabled: Boolean,
    privateChatToggleEnabled: Boolean,
    hasActiveCapacityRetry: Boolean,
    hidePrivateChatSwitch: Boolean,
    onPrivateChatRequestedChange: (Boolean) -> Unit,
    onCapacityRetryPolicyChange: (Boolean) -> Unit,
    onSelectRegion: (String?) -> Unit,
    onExistingAccount: () -> Unit,
    onCreateAccount: () -> Unit,
    onAccountCreated: () -> Unit,
    onCancel: () -> Unit,
) {
    var showNext by remember { mutableStateOf(false) }
    var regionMenuExpanded by remember { mutableStateOf(false) }
    val visibleSwitchRoles = visiblePrivateChatSwitchRoles(
        privateChatRequested = privateChatRequested,
        onboardingState = onboardingState,
        hasActiveCapacityRetry = hasActiveCapacityRetry,
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Create Oracle Exit",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )

        Text(
            text = "ZeroVPN creates your own Oracle VM and turns it into a WireGuard exit. To do that, you need an Oracle Cloud account.",
            fontSize = 14.sp,
            color = TextDim,
            lineHeight = 20.sp,
        )

        Text(
            text = if (privateChatRequested) {
                "ZeroVPN first creates and saves a working WireGuard exit, then installs PostgreSQL, Synapse, private TLS, and runs a real encrypted Matrix self-test. Chat failure does not remove the VPN."
            } else {
                "First-time setup takes about 5 minutes. ZeroVPN creates an Oracle VM, configures networking, waits for SSH, installs WireGuard, and creates your owner and friend invite keys. Once this exit exists, reconnecting is fast."
            },
            fontSize = 14.sp,
            color = TextPrimary,
            lineHeight = 20.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface, RoundedCornerShape(8.dp))
                .padding(12.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface, RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!hidePrivateChatSwitch) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Private Chat Node (Phase 1)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                        )
                        Text(
                            text = "Optional owner node, private to WireGuard. No invitations or public Matrix access yet.",
                            fontSize = 12.sp,
                            color = TextDim,
                            lineHeight = 17.sp,
                        )
                    }
                    Switch(
                        checked = privateChatRequested,
                        onCheckedChange = onPrivateChatRequestedChange,
                        enabled = privateChatToggleEnabled,
                    )
                }
            }
            if (hasActiveCapacityRetry) {
                Text(
                    text = "Private Chat stays on while a capacity retry session is active. Use the status card to review the available actions.",
                    fontSize = 12.sp,
                    color = TextDim,
                    lineHeight = 17.sp,
                )
            }
            if (privateChatRequested) {
                Text(
                    text = "Uses VM.Standard.A1.Flex with 1 OCPU, 6 GB RAM, and a 50 GB boot volume. Requested resources appear Free Tier eligible. Oracle, not ZeroVPN, determines actual billing. Review the Oracle cost estimate before creating the VM. ZeroVPN will not resize or recreate it silently.",
                    fontSize = 12.sp,
                    color = WarningYellow,
                    lineHeight = 17.sp,
                )
                if (PrivateChatSwitchRole.CAPACITY_RETRY_POLICY in visibleSwitchRoles) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Automatically retry if A1 capacity is unavailable",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                            )
                        }
                        Switch(
                            checked = capacityRetryPolicyEnabled,
                            onCheckedChange = onCapacityRetryPolicyChange,
                        )
                    }
                    Text(
                        text = "ZeroVPN will try once now. If Oracle has no A1 capacity, it will retry approximately every 15 minutes for up to 24 hours. You can stop retrying or choose VPN-only setup at any time.",
                        fontSize = 12.sp,
                        color = TextDim,
                        lineHeight = 17.sp,
                    )
                }
            }
        }

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
                BulletPoint("ZeroVPN will then create the API key and provision the selected Oracle VM exit.")
                BulletPoint("The first provisioning run usually takes several minutes; later reconnects use the existing WireGuard tunnel and are fast.")
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
                "This can be used for development/testing. Region capacity and Free Tier eligibility " +
                "are determined by Oracle. Choose the region shown in Oracle and review its cost estimate.",
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
        val totalPhases = if (currentPhase.isPrivateChat) 16 else 6
        Text(
            text = "Phase ${currentPhase.number}/$totalPhases: ${currentPhase.label}",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Accent,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }

    // Terminal-style console
    Text(
        text = "First setup can take several minutes on a fresh Oracle VM. This is normal; future reconnects are fast once the exit exists.",
        fontSize = 13.sp,
        color = TextDim,
        lineHeight = 18.sp,
        modifier = Modifier.padding(bottom = 10.dp),
    )

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
            val phaseTotal = if (event.phase.isPrivateChat) 16 else 6
            val phaseStr = "[${event.phase.number}/$phaseTotal]"
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
            event.technicalDetail?.let { detail ->
                Text(
                    text = "  $detail",
                    modifier = Modifier.padding(start = 78.dp, bottom = 2.dp),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextDim,
                    lineHeight = 14.sp,
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
    privateChatStatus: PrivateChatInstallStatus?,
    privateChatError: String?,
    events: List<ProvisioningEvent>,
    vpnState: VpnConnectionState,
    onConnect: () -> Unit,
    onRetryPrivateChat: () -> Unit,
    onRemovePrivateChat: () -> Unit,
    onDestroy: () -> Unit,
) {
    val connecting = vpnState is VpnConnectionState.Connecting ||
        vpnState is VpnConnectionState.PermissionRequired
    val connectionError = (vpnState as? VpnConnectionState.Failed)?.message
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
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
            if (privateChatStatus != null) {
                InfoRow(
                    "Private Chat",
                    when (privateChatStatus) {
                        PrivateChatInstallStatus.HEALTHY -> "Healthy - encrypted self-test passed"
                        PrivateChatInstallStatus.FAILED -> "Install failed - VPN retained"
                        PrivateChatInstallStatus.INSTALLING -> "Installing"
                        PrivateChatInstallStatus.REMOVING -> "Removing"
                    },
                )
            }
        }

        if (privateChatError != null) {
            Text(
                text = privateChatError,
                fontSize = 12.sp,
                color = Danger,
                lineHeight = 17.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (privateChatStatus == PrivateChatInstallStatus.FAILED) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onRetryPrivateChat,
                    modifier = Modifier.weight(1f).height(44.dp),
                ) {
                    Text("Retry Chat", color = Accent)
                }
                OutlinedButton(
                    onClick = onRemovePrivateChat,
                    modifier = Modifier.weight(1f).height(44.dp),
                ) {
                    Text("Remove Chat", color = Danger)
                }
            }
        }

        val privateChatEvents = events.filter { it.phase.isPrivateChat }
        if (isDevMode && privateChatEvents.isNotEmpty()) {
            Text(
                text = "PRIVATE CHAT PROVISIONING LOG",
                style = SectionTitleStyle,
            )
            PrivateChatProvisioningLog(privateChatEvents)
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

@Composable
private fun PrivateChatProvisioningLog(events: List<ProvisioningEvent>) {
    val scrollState = rememberScrollState()
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    LaunchedEffect(events.size) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(Surface, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .verticalScroll(scrollState),
    ) {
        events.forEach { event ->
            val statusColor = when (event.status) {
                Status.RUNNING -> Accent
                Status.SUCCESS -> SuccessGreen
                Status.WARNING -> WarningYellow
                Status.ERROR -> Danger
            }
            val status = when (event.status) {
                Status.RUNNING -> "START"
                Status.SUCCESS -> "PASS"
                Status.WARNING -> "WARN"
                Status.ERROR -> "FAIL"
            }
            Text(
                text = "${timeFormat.format(Date(event.timestamp))} [$status] ${event.phase.label}: ${event.message}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = statusColor,
                lineHeight = 15.sp,
            )
            event.technicalDetail?.let { detail ->
                Text(
                    text = "  $detail",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextDim,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
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
    capacityRetryPolicyEnabled: Boolean,
    capacityRetrySchedulerStatuses: Map<String, CapacityRetrySchedulerStatus>,
    capacityRetryDiagnostics: List<CapacityRetryDiagnosticEntry>,
    capacityRetrySession: CapacityRetrySession?,
    onRetry: () -> Unit,
    onCleanup: () -> Unit,
    onKeepTrying24h: () -> Unit,
    onStopRetry: () -> Unit,
    onVpnOnlyInstead: () -> Unit,
    onViewDiagnostics: () -> Unit,
    onViewDevLog: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    var regionMenuExpanded by remember { mutableStateOf(false) }
    val isCapacityFailure = failedPhase == Phase.VM_LAUNCH && (
        capacityRetrySession != null ||
            errorMessage?.contains("A1 host capacity", ignoreCase = true) == true ||
            errorMessage?.contains("rate limiting VM requests", ignoreCase = true) == true
        )
    val isActiveCapacityRetry = capacityRetrySession?.state in setOf(
        CapacityRetryState.WAITING_FOR_RETRY,
        CapacityRetryState.ACTIVE,
        CapacityRetryState.ACQUIRING,
    )
    val isRateLimited = when {
        capacityRetrySession?.lastResult == "RATE_LIMITED" -> true
        errorMessage?.contains("rate limiting VM requests", ignoreCase = true) == true -> true
        else -> false
    }
    val failureStatusColor = if (isActiveCapacityRetry) WarningYellow else Danger

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
                tint = failureStatusColor,
                modifier = Modifier.size(32.dp),
            )
            Column {
                Text(
                    text = when {
                        isRateLimited && isActiveCapacityRetry -> "VM request temporarily limited"
                        isActiveCapacityRetry -> "Private Chat capacity retry active"
                        else -> "Provisioning failed"
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = failureStatusColor,
                )
                Text(
                    text = if (isActiveCapacityRetry) {
                        "Waiting for the next eligible VM launch"
                    } else {
                        "Failed at: ${failedPhase.label}"
                    },
                    fontSize = 13.sp,
                    color = TextDim,
                )
                if (!isActiveCapacityRetry && lastSuccessPhase != null) {
                    Text(
                        text = "Last success: ${lastSuccessPhase.label}",
                        fontSize = 13.sp,
                        color = TextDim,
                    )
                }
                if (!isActiveCapacityRetry && errorMessage != null) {
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

        if (isCapacityFailure) {
            if (capacityRetrySession != null) {
                CapacityRetryStatusCard(
                    session = capacityRetrySession,
                    schedulerStatus = capacityRetrySchedulerStatuses[capacityRetrySession.sessionId],
                    diagnosticEntries = capacityRetryDiagnostics.filter {
                        it.sessionId == capacityRetrySession.sessionId
                    },
                    onRetry = onRetry,
                    onStop = onStopRetry,
                    onVpnOnlyInstead = onVpnOnlyInstead,
                    onViewDiagnostics = onViewDiagnostics,
                    onViewDevLog = onViewDevLog,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Surface, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Oracle login and API signing succeeded, but the single 6 GB launch request could not run now. ZeroVPN did not send an immediate 4 GB request, and no VM was created if no instance OCID exists.",
                        fontSize = 13.sp,
                        color = TextPrimary,
                        lineHeight = 18.sp,
                    )
                    Text(
                        text = if (capacityRetryPolicyEnabled) {
                            "Automatic retry was enabled before Oracle sign-in. ZeroVPN is preserving the saved signing credentials for the next eligible 15-minute slot. Android scheduling is best-effort and the fixed window is 24 hours."
                        } else {
                            "Background retry is Android best-effort. If you opt in now, the fixed retry window lasts 24 hours. You can stop it later or create the normal VPN-only exit and add Private Chat on a second capable VM."
                        },
                        fontSize = 12.sp,
                        color = TextDim,
                        lineHeight = 17.sp,
                    )
                    if (!capacityRetryPolicyEnabled) {
                        Button(
                            onClick = onKeepTrying24h,
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg),
                        ) { Text("Keep trying for 24 hours", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    } else {
                        OutlinedButton(
                            onClick = onStopRetry,
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                        ) { Text("Stop automatic retry", fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                    }
                    OutlinedButton(
                        onClick = onVpnOnlyInstead,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                    ) { Text("Set up VPN only instead", fontSize = 13.sp, fontWeight = FontWeight.Medium) }
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
                event.technicalDetail?.let { detail ->
                    Text(
                        text = "  $detail",
                        modifier = Modifier.padding(start = 78.dp, bottom = 2.dp),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextDim,
                        lineHeight = 14.sp,
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
            if (capacityRetrySession == null) {
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
                    Text("Retry now", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
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
