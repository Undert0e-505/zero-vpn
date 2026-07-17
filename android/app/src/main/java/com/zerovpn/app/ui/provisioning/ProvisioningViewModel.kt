package com.zerovpn.app.ui.provisioning

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.browser.customtabs.CustomTabsIntent
import androidx.work.WorkInfo
import com.zerovpn.app.chat.node.PrivateChatInstallStatus
import com.zerovpn.app.chat.node.PrivateChatHealthChecks
import com.zerovpn.app.chat.node.PrivateChatNodeManifest
import com.zerovpn.app.chat.node.PrivateChatNodeProvisioner
import com.zerovpn.app.chat.node.PrivateChatNodeState
import com.zerovpn.app.chat.node.PrivateChatOwnerVerificationResult
import com.zerovpn.app.chat.node.PrivateChatOwnerVerifier
import com.zerovpn.app.chat.node.PrivateChatProvisioningException
import com.zerovpn.app.chat.node.PrivateChatRemoteEvent
import com.zerovpn.app.chat.node.PrivateChatSelfTestStatus
import com.zerovpn.app.chat.node.PrivateChatStageState
import com.zerovpn.app.chat.node.PrivateChatStageStatus
import com.zerovpn.app.chat.node.PRIVATE_CHAT_STAGE_ORDER
import com.zerovpn.app.chat.node.redactPrivateChatDiagnostic
import com.zerovpn.app.chat.retry.CapacityRetryMode
import com.zerovpn.app.chat.retry.BackgroundLaunchCredentials
import com.zerovpn.app.chat.retry.BackgroundLaunchParams
import com.zerovpn.app.chat.retry.BackgroundLaunchResult
import com.zerovpn.app.chat.retry.CapacityRetryDiagnosticEntry
import com.zerovpn.app.chat.retry.CapacityRetryDiagnosticLog
import com.zerovpn.app.chat.retry.CapacityRetryReconciliationAction
import com.zerovpn.app.chat.retry.CapacityRetryRepository
import com.zerovpn.app.chat.retry.CapacityRetrySchedulerState
import com.zerovpn.app.chat.retry.CapacityRetrySchedulerStatus
import com.zerovpn.app.chat.retry.CapacityRetrySession
import com.zerovpn.app.chat.retry.CapacityRetrySessionStarter
import com.zerovpn.app.chat.retry.CapacityRetryStartContext
import com.zerovpn.app.chat.retry.CapacityRetryStartResult
import com.zerovpn.app.chat.retry.CapacityRetryState
import com.zerovpn.app.chat.retry.CapacityRetryWorkMonitor
import com.zerovpn.app.chat.retry.CapacityRetryWorkScheduler
import com.zerovpn.app.chat.retry.DeferredCandidateState
import com.zerovpn.app.chat.retry.OciBackgroundLauncher
import com.zerovpn.app.chat.retry.PrivateChatCandidate
import com.zerovpn.app.chat.retry.PrivateChatCapabilityStatus
import com.zerovpn.app.chat.retry.ProvisioningLeaseOperation
import com.zerovpn.app.chat.retry.ProvisioningOperationLease
import com.zerovpn.app.chat.retry.RetryCredentialVault
import com.zerovpn.app.chat.retry.DurableApiKeyCredentials
import com.zerovpn.app.chat.retry.candidatesFromJson
import com.zerovpn.app.chat.retry.capacityRetrySchedulerStatus
import com.zerovpn.app.chat.retry.decideCapacityRetryReconciliation
import com.zerovpn.app.chat.retry.failureDiagnosticsOrNull
import com.zerovpn.app.chat.retry.hasAuthoritativePrivateChatRetry
import com.zerovpn.app.chat.retry.sessionsFromJson
import com.zerovpn.app.chat.retry.shouldEnqueueCapacityRetry
import com.zerovpn.app.chat.retry.cooldownRemaining
import com.zerovpn.app.chat.retry.isLaunchEligible
import com.zerovpn.app.chat.retry.nextEligibleLaunchAt
import com.zerovpn.app.friends.FriendsRepository
import com.zerovpn.app.friends.HandshakeQueryResult
import com.zerovpn.app.friends.InviteHandshakeChecker
import com.zerovpn.app.friends.InvitePeerResetPhase
import com.zerovpn.app.friends.InvitePeerResetResult
import com.zerovpn.app.friends.InvitePeerResetter
import com.zerovpn.app.friends.InviteSlot
import com.zerovpn.app.friends.InviteSlotState
import com.zerovpn.app.friends.ParsedWireGuardInvite
import com.zerovpn.app.friends.SharedExitProviderType
import com.zerovpn.app.friends.SharedExitProfile
import com.zerovpn.app.friends.SharedExitSource
import com.zerovpn.app.friends.sha256
import com.zerovpn.app.oci.OciProvisioner
import com.zerovpn.app.oci.VmLaunchFailure
import com.zerovpn.app.oci.OciRegion
import com.zerovpn.app.oci.OciRegions
import com.zerovpn.app.storage.SecureSecretStore
import com.zerovpn.app.vpn.ConfiguredExit
import com.zerovpn.app.vpn.ExitLifecycleState
import com.zerovpn.app.vpn.ExitProvider
import com.zerovpn.app.vpn.OciResourceIds
import com.zerovpn.app.vpn.ProviderSwitchDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.util.UUID

sealed interface InviteClaimCheckResult {
    data object NotClaimed : InviteClaimCheckResult
    data object Claimed : InviteClaimCheckResult
    data class Error(val message: String) : InviteClaimCheckResult
}

sealed interface InviteResetResult {
    data object Reset : InviteResetResult
    data class Error(val message: String) : InviteResetResult
}

enum class OracleOperationType {
    NONE,
    PROVISION,
    DESTROY_EXIT,
    CLEANUP_PARTIAL_EXIT,
}

data class OracleOperationDiagnostics(
    val pendingOperation: OracleOperationType = OracleOperationType.NONE,
    val failedOperation: OracleOperationType = OracleOperationType.NONE,
    val targetExitId: String? = null,
    val targetRegion: String? = null,
    val targetInstanceIdPrefix: String? = null,
    val targetDisplayName: String? = null,
    val authState: String = "missing",
    val lastError: String? = null,
    // Capacity-fallback fields (private-chat provisioning only)
    val chatRequestedShape: String = "NOT TESTD",
    val chatPreferredConfig: String = "NOT TESTED",
    val chatCompactFallback: String = "NOT TESTED",
    val chatLastAttemptedConfig: String = "NOT TESTED",
    val chatLastLaunchResult: String = "NOT TESTED",
    val chatInstanceCreated: String = "NOT TESTED",
    val chatCleanupRequired: String = "NOT TESTED",
)

internal fun classifyProvisioningFailure(
    error: Throwable,
    events: List<ProvisioningEvent> = emptyList(),
    currentPhase: Phase? = null,
): Pair<Phase, String> {
    when (val launchFailure = OciProvisioner.classifyLaunchFailure(error)) {
        is VmLaunchFailure.OutOfHostCapacity -> return Phase.VM_LAUNCH to launchFailure.message
        is VmLaunchFailure.RateLimited -> return Phase.VM_LAUNCH to launchFailure.message
        is VmLaunchFailure.Other -> return Phase.VM_LAUNCH to launchFailure.message
        null -> Unit
    }

    val failedPhase = events.lastOrNull { it.status == Status.ERROR && it.phase != Phase.DONE }?.phase
        ?: currentPhase
        ?: Phase.AUTH
    val displayMessage = error.message ?: error.javaClass.simpleName
    return failedPhase to displayMessage
}

internal sealed interface ManualCapacityRetryDecision {
    data class Attempt(val sessionId: String) : ManualCapacityRetryDecision
    data class Cooldown(val remaining: Duration) : ManualCapacityRetryDecision
    data object StartSession : ManualCapacityRetryDecision
    data object AuthenticationRequired : ManualCapacityRetryDecision
    data object NotAvailable : ManualCapacityRetryDecision
}

internal fun decideManualCapacityRetry(
    session: CapacityRetrySession?,
    pendingCapacityRetryEligible: Boolean,
    now: Instant,
): ManualCapacityRetryDecision = when {
    session?.state == CapacityRetryState.PAUSED_AUTH_REQUIRED -> ManualCapacityRetryDecision.AuthenticationRequired
    session != null &&
        session.state in setOf(CapacityRetryState.WAITING_FOR_RETRY, CapacityRetryState.ACTIVE) &&
        session.isLaunchEligible(now) -> ManualCapacityRetryDecision.Attempt(session.sessionId)
    session != null && session.state in setOf(CapacityRetryState.WAITING_FOR_RETRY, CapacityRetryState.ACTIVE) ->
        ManualCapacityRetryDecision.Cooldown(session.cooldownRemaining(now))
    session == null && pendingCapacityRetryEligible -> ManualCapacityRetryDecision.StartSession
    else -> ManualCapacityRetryDecision.NotAvailable
}

class ProvisioningViewModel : ViewModel() {

    private val _events = MutableStateFlow<List<ProvisioningEvent>>(emptyList())
    val events: StateFlow<List<ProvisioningEvent>> = _events.asStateFlow()

    private val _currentPhase = MutableStateFlow<Phase?>(null)
    val currentPhase: StateFlow<Phase?> = _currentPhase.asStateFlow()

    private val _state = MutableStateFlow<ProvisioningState>(ProvisioningState.PreStart)
    val state: StateFlow<ProvisioningState> = _state.asStateFlow()

    private val _publicIp = MutableStateFlow<String?>(null)
    val publicIp: StateFlow<String?> = _publicIp.asStateFlow()

    private val _wireGuardPort = MutableStateFlow(51820)
    val wireGuardPort: StateFlow<Int> = _wireGuardPort.asStateFlow()

    private val _isDevMode = MutableStateFlow(false)
    val isDevMode: StateFlow<Boolean> = _isDevMode.asStateFlow()

    private val _privateChatRequested = MutableStateFlow(false)
    val privateChatRequested: StateFlow<Boolean> = _privateChatRequested.asStateFlow()

    private val _capacityRetryPolicyEnabled = MutableStateFlow(false)
    val capacityRetryPolicyEnabled: StateFlow<Boolean> = _capacityRetryPolicyEnabled.asStateFlow()

    private val _capacityRetrySessions = MutableStateFlow<List<CapacityRetrySession>>(emptyList())
    val capacityRetrySessions: StateFlow<List<CapacityRetrySession>> = _capacityRetrySessions.asStateFlow()

    private val _capacityRetrySchedulerStatuses =
        MutableStateFlow<Map<String, CapacityRetrySchedulerStatus>>(emptyMap())
    val capacityRetrySchedulerStatuses: StateFlow<Map<String, CapacityRetrySchedulerStatus>> =
        _capacityRetrySchedulerStatuses.asStateFlow()

    private val _capacityRetryDiagnostics = MutableStateFlow<List<CapacityRetryDiagnosticEntry>>(emptyList())
    val capacityRetryDiagnostics: StateFlow<List<CapacityRetryDiagnosticEntry>> =
        _capacityRetryDiagnostics.asStateFlow()

    private val _privateChatCandidates = MutableStateFlow<List<PrivateChatCandidate>>(emptyList())
    val privateChatCandidates: StateFlow<List<PrivateChatCandidate>> = _privateChatCandidates.asStateFlow()

    private val _oracleOnboardingState = MutableStateFlow(OracleOnboardingState.NotStarted)
    val oracleOnboardingState: StateFlow<OracleOnboardingState> = _oracleOnboardingState.asStateFlow()

    private val _selectedOracleRegion = MutableStateFlow<String?>(null)
    val selectedOracleRegion: StateFlow<String?> = _selectedOracleRegion.asStateFlow()

    val oracleRegions: List<OciRegion> = OciRegions.common

    data class SshDebugInfo(
        val publicIp: String,
        val username: String,
        val privateKeyPresent: Boolean,
    ) {
        val windowsSshCommand: String
            get() = "ssh -i C:\\ssh-keys\\zerovpn_current_vm_key $username@$publicIp"
    }

    private val _sshDebugInfo = MutableStateFlow<SshDebugInfo?>(null)
    val sshDebugInfo: StateFlow<SshDebugInfo?> = _sshDebugInfo.asStateFlow()

    private val _configuredExits = MutableStateFlow<List<ConfiguredExit>>(emptyList())
    val configuredExits: StateFlow<List<ConfiguredExit>> = _configuredExits.asStateFlow()

    private val _selectedExitId = MutableStateFlow<String?>(null)
    val selectedExitId: StateFlow<String?> = _selectedExitId.asStateFlow()

    private val _providerSwitchDiagnostics = MutableStateFlow(ProviderSwitchDiagnostics())
    val providerSwitchDiagnostics: StateFlow<ProviderSwitchDiagnostics> = _providerSwitchDiagnostics.asStateFlow()

    private val _inviteSlots = MutableStateFlow<List<InviteSlot>>(emptyList())
    val inviteSlots: StateFlow<List<InviteSlot>> = _inviteSlots.asStateFlow()

    private val _sharedExitProfiles = MutableStateFlow<List<SharedExitProfile>>(emptyList())
    val sharedExitProfiles: StateFlow<List<SharedExitProfile>> = _sharedExitProfiles.asStateFlow()

    private val _lastInviteOperationError = MutableStateFlow<String?>(null)
    val lastInviteOperationError: StateFlow<String?> = _lastInviteOperationError.asStateFlow()

    private val _oracleOperationDiagnostics = MutableStateFlow(OracleOperationDiagnostics())
    val oracleOperationDiagnostics: StateFlow<OracleOperationDiagnostics> =
        _oracleOperationDiagnostics.asStateFlow()

    private var provisioner: OciProvisioner? = null
    private var authResult: OciProvisioner.AuthResult? = null
    private var preflightResult: OciProvisioner.PreflightResult? = null
    private var resourceIds: OciProvisioner.ResourceIds? = null
    private var retryLaunchSshPublicKey: String? = null
    private var retryLaunchSubnetId: String? = null
    private var clientConfig: String? = null
    private var wireGuardClientPublicKey: String? = null
    private var wireGuardServerPublicKey: String? = null
    private var wireGuardServerPeerPublicKey: String? = null
    private var homeRegion: String? = null
    private var apiKeyUserOcid: String? = null
    private var apiKeyTenancyOcid: String? = null
    private var apiKeyTokenRegion: String? = null
    private var apiKeyTokenRegionSource: String? = null
    // Capacity-fallback tracking for Diagnostics
    internal var chatLastAttemptedConfig: String = "NOT TESTED"
    internal var chatLastLaunchResult: String = "NOT TESTED"
    internal var chatInstanceCreated: String = "NOT TESTED"
    internal var chatCleanupRequired: String = "NOT TESTED"
    private var apiKeyFingerprint: String? = null
    private var pendingProvisionExitId: String? = null
    private var pendingCapacityRetryEligible = false
    private var pendingRetryLastLaunchFinishedAtUtc: String? = null
    private var pendingRetryNextEligibleAtUtc: String? = null
    private var pendingRetryMemoryGb: Int = 6
    private var pendingRetryLastResult: String? = null
    private var pendingRetryHttpStatus: Int? = null
    private var provisioningJob: Job? = null
    private var privateChatJob: Job? = null
    private var pendingOracleOperation = PendingOracleOperation.None
    private var failedOracleOperation = PendingOracleOperation.None
    private var lastOracleOperationError: String? = null

    // State persistence
    private lateinit var prefs: SharedPreferences
    private lateinit var secretStore: SecureSecretStore
    private var friendsRepository: FriendsRepository? = null
    private var capacityRetryRepository: CapacityRetryRepository? = null
    private var provisioningLease: ProvisioningOperationLease? = null
    private var prefsLoaded = false
    private var capacityRetryWorkMonitor: CapacityRetryWorkMonitor? = null
    private var capacityRetryWorkObservationJob: Job? = null
    private var capacityRetryReconciliationJob: Job? = null
    private val capacityRetryWorkObservers =
        mutableMapOf<String, WorkInfoObserverRegistration>()
    private val capacityRetryPrefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == CapacityRetryDiagnosticLog.KEY_ENTRIES) {
                refreshCapacityRetrySessionsFromRepository()
                refreshCapacityRetryDiagnostics()
            }
        }

    private data class WorkInfoObserverRegistration(
        val liveData: LiveData<List<WorkInfo>>,
        val observer: Observer<List<WorkInfo>>,
    )

    private data class PendingOracleOperation(
        val type: OracleOperationType,
        val exitId: String? = null,
        val region: String? = null,
        val instanceId: String? = null,
        val displayName: String? = null,
    ) {
        companion object {
            val None = PendingOracleOperation(OracleOperationType.NONE)
            val Provision = PendingOracleOperation(OracleOperationType.PROVISION)
        }
    }

    fun initPrefs(context: Context) {
        if (prefsLoaded) return
        val appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("zerovpn_provisioning", Context.MODE_PRIVATE)
        secretStore = SecureSecretStore(appContext)
        friendsRepository = FriendsRepository(prefs)
        capacityRetryRepository = CapacityRetryRepository(prefs)
        provisioningLease = ProvisioningOperationLease(prefs).also { it.clearStaleLease() }
        loadPersistedState()
        loadFriendsState()
        prefsLoaded = true
        prefs.registerOnSharedPreferenceChangeListener(capacityRetryPrefsListener)
        refreshCapacityRetryDiagnostics()
        startCapacityRetryWorkObservation(appContext)
        maybeStartAutomaticCapacityRetry(appContext)
        reconcileCapacityRetryWork(appContext)
    }

    private fun loadFriendsState() {
        val repository = friendsRepository ?: return
        var slots = repository.listInviteSlots()
        slots.forEach { slot ->
            val legacyConfig = slot.encryptedClientConfig?.takeIf { it.isNotBlank() }
            if (legacyConfig != null && slot.clientConfigSecretKey.isNullOrBlank()) {
                val key = SecureSecretStore.inviteClientConfig(slot.slotId)
                secretStore.putSecret(key, legacyConfig)
                slots = repository.upsertInviteSlot(
                    slot.copy(
                        clientConfigSecretKey = key,
                        encryptedClientConfig = null,
                        encryptedClientPrivateKey = null,
                    ),
                )
            }
        }
        var profiles = repository.listSharedExits()
        profiles.forEach { profile ->
            val legacyConfig = profile.encryptedWireGuardConfig?.takeIf { it.isNotBlank() }
            if (legacyConfig != null && profile.wireGuardConfigSecretKey.isNullOrBlank()) {
                val key = SecureSecretStore.sharedWireGuardConfig(profile.id)
                secretStore.putSecret(key, legacyConfig)
                profiles = repository.addSharedExit(
                    profile.copy(
                        wireGuardConfigSecretKey = key,
                        configHash = profile.configHash ?: legacyConfig.sha256(),
                        encryptedWireGuardConfig = null,
                    ),
                )
            }
        }
        _inviteSlots.value = slots
        _sharedExitProfiles.value = profiles
    }

    private fun loadPersistedState() {
        if (!::prefs.isInitialized) return
        _isDevMode.value = prefs.getBoolean("is_dev_mode", false)
        _privateChatRequested.value = prefs.getBoolean("private_chat_requested", false)
        _capacityRetryPolicyEnabled.value =
            prefs.getBoolean(CapacityRetryRepository.KEY_POLICY_ENABLED, false)
        _oracleOnboardingState.value = runCatching {
            OracleOnboardingState.valueOf(
                prefs.getString("oracle_onboarding_state", OracleOnboardingState.NotStarted.name)
                    ?: OracleOnboardingState.NotStarted.name,
            )
        }.getOrDefault(OracleOnboardingState.NotStarted)
        homeRegion = prefs.getString("home_region", null)
        _selectedOracleRegion.value = prefs.getString("selected_oracle_region", null)
        retryLaunchSshPublicKey = prefs.getString("retry_launch_ssh_public_key", null)
        retryLaunchSubnetId = prefs.getString("retry_launch_subnet_id", null)
        apiKeyUserOcid = prefs.getString("api_key_user_ocid", null)
        apiKeyTenancyOcid = prefs.getString("api_key_tenancy_ocid", null)
        apiKeyTokenRegion = prefs.getString("api_key_token_region", null)
        apiKeyTokenRegionSource = prefs.getString("api_key_token_region_source", null)
        apiKeyFingerprint = prefs.getString("api_key_fingerprint", null)
        pendingProvisionExitId = prefs.getString("pending_provision_exit_id", null)
        pendingCapacityRetryEligible = prefs.getBoolean("capacity_retry_eligible", false)
        pendingRetryLastLaunchFinishedAtUtc = prefs.getString("capacity_retry_last_launch_finished_at_utc", null)
        pendingRetryNextEligibleAtUtc = prefs.getString("capacity_retry_next_eligible_at_utc", null)
        pendingRetryMemoryGb = prefs.getInt("capacity_retry_pending_memory_gb", 6).let { if (it == 4) 4 else 6 }
        pendingRetryLastResult = prefs.getString("capacity_retry_last_result", null)
        pendingRetryHttpStatus = prefs.getInt("capacity_retry_last_http_status", 0).takeIf { it > 0 }
        val pendingProvisionResourceIds = if (pendingCapacityRetryEligible) loadResourceIds() else null
        _lastInviteOperationError.value = prefs.getString("last_invite_operation_error", null)
        _capacityRetrySessions.value = sessionsFromJson(prefs.getString("private_chat_capacity_retry_sessions_json", null))
        if (_capacityRetrySessions.value.hasAuthoritativePrivateChatRetry()) {
            _privateChatRequested.value = true
        }
        _privateChatCandidates.value = candidatesFromJson(prefs.getString("private_chat_candidates_json", null))
        lastOracleOperationError = prefs.getString("last_oracle_operation_error", null)
        pendingOracleOperation = loadOracleOperation("pending_oracle_operation")
        failedOracleOperation = loadOracleOperation("failed_oracle_operation")
        refreshOracleOperationDiagnostics()

        val exits = loadConfiguredExits().map { exit ->
            val chat = exit.privateChat
            if (chat?.status == PrivateChatInstallStatus.INSTALLING ||
                chat?.status == PrivateChatInstallStatus.REMOVING
            ) {
                exit.copy(
                    privateChat = chat.copy(
                        status = PrivateChatInstallStatus.FAILED,
                        lastError = "The Private Chat operation was interrupted. Retry resumes from the VM stage ledger.",
                        lastUpdatedAt = System.currentTimeMillis(),
                    ),
                )
            } else {
                exit
            }
        }
        if (exits.isNotEmpty()) {
            _configuredExits.value = exits
            _selectedExitId.value = prefs.getString("selected_exit_id", null)
                ?.takeIf { id -> exits.any { it.id == id } }
                ?: exits.first().id
            val selected = exits.firstOrNull { it.id == _selectedExitId.value } ?: exits.first()
            _publicIp.value = selected.publicIp
            _wireGuardPort.value = selected.wireGuardPort
            clientConfig = selected.wireGuardConfig
            wireGuardClientPublicKey = selected.clientPublicKey
            wireGuardServerPublicKey = selected.serverPublicKey
            wireGuardServerPeerPublicKey = selected.serverPeerPublicKey
            resourceIds = if (pendingCapacityRetryEligible) {
                pendingProvisionResourceIds
            } else {
                selected.ociResourceIds?.toProvisionerResourceIds()
            }
            _state.value = if (pendingCapacityRetryEligible) {
                restoredCapacityFailureState()
            } else {
                ProvisioningState.Idle
            }
            persistState()
            return
        }

        val stateStr = prefs.getString("state", null)
        if (stateStr != null) {
            val savedIp = prefs.getString("public_ip", null)
            val savedPort = prefs.getInt("wireguard_port", 51820)
            clientConfig = prefs.getString("wireguard_client_config", null)
            wireGuardClientPublicKey = prefs.getString("wireguard_client_public_key", null)
            wireGuardServerPublicKey = prefs.getString("wireguard_server_public_key", null)
            wireGuardServerPeerPublicKey = prefs.getString("wireguard_server_peer_public_key", null)
            resourceIds = loadResourceIds()
            if (savedIp != null && stateStr == "Success") {
                _publicIp.value = savedIp
                _wireGuardPort.value = savedPort
                clientConfig?.let {
                    val exit = buildConfiguredExit(
                        exitId = prefs.getString("selected_exit_id", null) ?: newExitId(),
                        name = "Exit 1",
                        publicIp = savedIp,
                        wireGuardPort = savedPort,
                        region = homeRegion ?: LEGACY_REGION_FALLBACK,
                        wireGuardConfig = it,
                        resourceIds = resourceIds,
                        sshUsername = null,
                        sshPrivateKey = null,
                        createdAt = prefs.getLong("created_at", System.currentTimeMillis()),
                    )
                    _configuredExits.value = listOf(exit)
                    _selectedExitId.value = exit.id
                }
                _state.value = ProvisioningState.Idle
            }
            // Don't restore Running state â€” if we were mid-provision, user needs to retry
        }
        if (pendingCapacityRetryEligible) {
            _state.value = restoredCapacityFailureState()
        }
    }

    private fun restoredCapacityFailureState(): ProvisioningState.Failure =
        ProvisioningState.Failure(
            failedPhase = Phase.VM_LAUNCH,
            lastSuccessPhase = Phase.NETWORK,
            errorMessage = if (pendingRetryLastResult == "RATE_LIMITED") {
                "Oracle is temporarily rate limiting VM requests."
            } else {
                "Oracle has no A1 host capacity right now. ZeroVPN will wait before trying the ${pendingRetryMemoryGb} GB configuration."
            },
        )

    private fun persistState() {
        if (!::prefs.isInitialized) return
        prefs.edit().apply {
            listOf(
                "home_region",
                "selected_oracle_region",
                "api_key_user_ocid",
                "api_key_tenancy_ocid",
                "api_key_token_region",
                "api_key_token_region_source",
                "api_key_fingerprint",
                "pending_provision_exit_id",
                "retry_launch_ssh_public_key",
                "retry_launch_subnet_id",
                "capacity_retry_last_launch_finished_at_utc",
                "capacity_retry_next_eligible_at_utc",
                "capacity_retry_last_result",
                "capacity_retry_last_http_status",
                "public_ip",
                "wireguard_client_config",
                "wireguard_client_public_key",
                "wireguard_server_public_key",
                "wireguard_server_peer_public_key",
                "selected_exit_id",
                "resource_vcn_id",
                "resource_sl_id",
                "resource_subnet_id",
                "resource_igw_id",
                "resource_instance_id",
                "last_invite_operation_error",
                "last_oracle_operation_error",
                "pending_oracle_operation_type",
                "pending_oracle_operation_exit_id",
                "pending_oracle_operation_region",
                "pending_oracle_operation_instance_id",
                "pending_oracle_operation_display_name",
                "failed_oracle_operation_type",
                "failed_oracle_operation_exit_id",
                "failed_oracle_operation_region",
                "failed_oracle_operation_instance_id",
                "failed_oracle_operation_display_name",
            ).forEach { remove(it) }
            putString("state", _state.value::class.simpleName)
            putString("oracle_onboarding_state", _oracleOnboardingState.value.name)
            putBoolean("is_dev_mode", _isDevMode.value)
            putBoolean("private_chat_requested", _privateChatRequested.value)
            putBoolean(CapacityRetryRepository.KEY_POLICY_ENABLED, _capacityRetryPolicyEnabled.value)
            putBoolean("capacity_retry_eligible", pendingCapacityRetryEligible)
            putInt("capacity_retry_pending_memory_gb", pendingRetryMemoryGb)
            homeRegion?.let { putString("home_region", it) }
            _selectedOracleRegion.value?.let { putString("selected_oracle_region", it) }
            retryLaunchSshPublicKey?.let { putString("retry_launch_ssh_public_key", it) }
            retryLaunchSubnetId?.let { putString("retry_launch_subnet_id", it) }
            apiKeyUserOcid?.let { putString("api_key_user_ocid", it) }
            apiKeyTenancyOcid?.let { putString("api_key_tenancy_ocid", it) }
            apiKeyTokenRegion?.let { putString("api_key_token_region", it) }
            apiKeyTokenRegionSource?.let { putString("api_key_token_region_source", it) }
            apiKeyFingerprint?.let { putString("api_key_fingerprint", it) }
            pendingProvisionExitId?.let { putString("pending_provision_exit_id", it) }
            pendingRetryLastLaunchFinishedAtUtc?.let { putString("capacity_retry_last_launch_finished_at_utc", it) }
            pendingRetryNextEligibleAtUtc?.let { putString("capacity_retry_next_eligible_at_utc", it) }
            pendingRetryLastResult?.let { putString("capacity_retry_last_result", it) }
            pendingRetryHttpStatus?.let { putInt("capacity_retry_last_http_status", it) }
            _publicIp.value?.let { putString("public_ip", it) }
            putInt("wireguard_port", _wireGuardPort.value)
            wireGuardClientPublicKey?.let { putString("wireguard_client_public_key", it) }
            wireGuardServerPublicKey?.let { putString("wireguard_server_public_key", it) }
            wireGuardServerPeerPublicKey?.let { putString("wireguard_server_peer_public_key", it) }
            _selectedExitId.value?.let { putString("selected_exit_id", it) }
            _lastInviteOperationError.value?.let { putString("last_invite_operation_error", it) }
            lastOracleOperationError?.let { putString("last_oracle_operation_error", it) }
            putOracleOperation("pending_oracle_operation", pendingOracleOperation)
            putOracleOperation("failed_oracle_operation", failedOracleOperation)
            putString("configured_exits_json", serializeConfiguredExits(_configuredExits.value))
            resourceIds?.let { rids ->
                rids.vcnId?.let { putString("resource_vcn_id", it) }
                rids.slId?.let { putString("resource_sl_id", it) }
                rids.subnetId?.let { putString("resource_subnet_id", it) }
                rids.igwId?.let { putString("resource_igw_id", it) }
                rids.instanceId?.let { putString("resource_instance_id", it) }
            }
            // OCI API keys are owned per exit. Destroy deletes only the saved fingerprint
            // for the target exit after relogin; other exits' signing keys are preserved.
            // SSH debug private keys stay in memory for the provisioning session only.
        }.apply()
    }

    private fun loadConfiguredExits(): List<ConfiguredExit> {
        val raw = prefs.getString("configured_exits_json", null)?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val json = array.getJSONObject(i)
                    add(json.toConfiguredExit())
                }
            }
        }.getOrElse {
            emptyList()
        }
    }

    private fun loadResourceIds(): OciProvisioner.ResourceIds? {
        if (!::prefs.isInitialized) return null
        val rids = OciProvisioner.ResourceIds(
            vcnId = prefs.getString("resource_vcn_id", null),
            slId = prefs.getString("resource_sl_id", null),
            subnetId = prefs.getString("resource_subnet_id", null),
            igwId = prefs.getString("resource_igw_id", null),
            instanceId = prefs.getString("resource_instance_id", null),
        )
        return if (
            rids.vcnId == null &&
            rids.slId == null &&
            rids.subnetId == null &&
            rids.igwId == null &&
            rids.instanceId == null
        ) {
            null
        } else {
            rids
        }
    }

    private fun loadOracleOperation(prefix: String): PendingOracleOperation {
        if (!::prefs.isInitialized) return PendingOracleOperation.None
        val type = runCatching {
            OracleOperationType.valueOf(
                prefs.getString("${prefix}_type", OracleOperationType.NONE.name)
                    ?: OracleOperationType.NONE.name,
            )
        }.getOrDefault(OracleOperationType.NONE)
        if (type == OracleOperationType.NONE) return PendingOracleOperation.None
        return PendingOracleOperation(
            type = type,
            exitId = prefs.getString("${prefix}_exit_id", null),
            region = prefs.getString("${prefix}_region", null),
            instanceId = prefs.getString("${prefix}_instance_id", null),
            displayName = prefs.getString("${prefix}_display_name", null),
        )
    }

    private fun SharedPreferences.Editor.putOracleOperation(
        prefix: String,
        operation: PendingOracleOperation,
    ) {
        putString("${prefix}_type", operation.type.name)
        operation.exitId?.let { putString("${prefix}_exit_id", it) }
        operation.region?.let { putString("${prefix}_region", it) }
        operation.instanceId?.let { putString("${prefix}_instance_id", it) }
        operation.displayName?.let { putString("${prefix}_display_name", it) }
    }

    private fun setPendingOracleOperation(operation: PendingOracleOperation) {
        pendingOracleOperation = operation
        failedOracleOperation = PendingOracleOperation.None
        lastOracleOperationError = null
        refreshOracleOperationDiagnostics()
    }

    private fun setFailedOracleOperation(operation: PendingOracleOperation, error: String?) {
        pendingOracleOperation = PendingOracleOperation.None
        failedOracleOperation = operation
        lastOracleOperationError = error?.sanitizeInviteDiagnostic()
        refreshOracleOperationDiagnostics()
    }

    private fun clearOracleOperationState() {
        pendingOracleOperation = PendingOracleOperation.None
        failedOracleOperation = PendingOracleOperation.None
        lastOracleOperationError = null
        refreshOracleOperationDiagnostics()
    }

    private fun refreshOracleOperationDiagnostics() {
        val operation = when {
            pendingOracleOperation.type != OracleOperationType.NONE -> pendingOracleOperation
            failedOracleOperation.type != OracleOperationType.NONE -> failedOracleOperation
            else -> PendingOracleOperation.None
        }
        val chatRequested = _privateChatRequested.value
        _oracleOperationDiagnostics.value = OracleOperationDiagnostics(
            pendingOperation = pendingOracleOperation.type,
            failedOperation = failedOracleOperation.type,
            targetExitId = operation.exitId,
            targetRegion = operation.region,
            targetInstanceIdPrefix = operation.instanceId.safeOcidPrefix(),
            targetDisplayName = operation.displayName,
            authState = if (authResult == null) "missing" else "present",
            lastError = lastOracleOperationError,
            chatRequestedShape = if (chatRequested) "VM.Standard.A1.Flex" else "NOT TESTED",
            chatPreferredConfig = if (chatRequested) "1 OCPU / 6 GB" else "NOT TESTED",
            chatCompactFallback = if (chatRequested) "1 OCPU / 4 GB" else "NOT TESTED",
            chatLastAttemptedConfig = chatLastAttemptedConfig,
            chatLastLaunchResult = chatLastLaunchResult,
            chatInstanceCreated = chatInstanceCreated,
            chatCleanupRequired = chatCleanupRequired,
        )
    }

    private fun ConfiguredExit.toDestroyOperation(): PendingOracleOperation =
        PendingOracleOperation(
            type = OracleOperationType.DESTROY_EXIT,
            exitId = id,
            region = region,
            instanceId = ociResourceIds?.instanceId ?: instanceId,
            displayName = name,
        )

    fun showPreStart() {
        _state.value = ProvisioningState.PreStart
        _oracleOnboardingState.value = OracleOnboardingState.NotStarted
        persistState()
    }

    fun prepareNewProvisioningFlow() {
        if (_state.value is ProvisioningState.Running || _state.value is ProvisioningState.Destroying) {
            return
        }
        clearPendingRetryCredentials()
        _events.value = emptyList()
        _currentPhase.value = null
        _publicIp.value = null
        _wireGuardPort.value = 51820
        _sshDebugInfo.value = null
        provisioner = null
        authResult = null
        preflightResult = null
        resourceIds = null
        clientConfig = null
        wireGuardClientPublicKey = null
        wireGuardServerPublicKey = null
        wireGuardServerPeerPublicKey = null
        homeRegion = null
        apiKeyUserOcid = null
        apiKeyTenancyOcid = null
        apiKeyTokenRegion = null
        apiKeyTokenRegionSource = null
        apiKeyFingerprint = null
        retryLaunchSshPublicKey = null
        pendingProvisionExitId = null
        pendingCapacityRetryEligible = false
        clearPendingCapacityRetryMetadata()
        _privateChatRequested.value = false
        _capacityRetryPolicyEnabled.value = false
        capacityRetryRepository?.setCapacityRetryPolicyEnabled(false)
        clearOracleOperationState()
        _state.value = ProvisioningState.PreStart
        _oracleOnboardingState.value = OracleOnboardingState.NotStarted
        persistState()
    }

    fun launchOracleSignup(context: Context) {
        _oracleOnboardingState.value = OracleOnboardingState.SignupLaunched
        persistState()
        openUrl(context, ORACLE_SIGNUP_URL)
    }

    fun setPrivateChatRequested(requested: Boolean) {
        if (_state.value is ProvisioningState.Running || _state.value is ProvisioningState.Destroying) return
        if (_capacityRetrySessions.value.hasAuthoritativePrivateChatRetry()) {
            _privateChatRequested.value = true
            persistState()
            return
        }
        _privateChatRequested.value = requested
        if (!requested) {
            _capacityRetryPolicyEnabled.value = false
            capacityRetryRepository?.setCapacityRetryPolicyEnabled(false)
        }
        persistState()
    }

    fun setCapacityRetryPolicyEnabled(enabled: Boolean) {
        if (_state.value is ProvisioningState.Running || _state.value is ProvisioningState.Destroying) return
        _capacityRetryPolicyEnabled.value = enabled && _privateChatRequested.value
        capacityRetryRepository?.setCapacityRetryPolicyEnabled(_capacityRetryPolicyEnabled.value)
        persistState()
    }

    fun acknowledgeAccountCreated() {
        _oracleOnboardingState.value = OracleOnboardingState.ReadyToAuthenticate
        persistState()
    }

    fun markAuthReturned() {
        if (_oracleOnboardingState.value == OracleOnboardingState.AuthLaunched ||
            _oracleOnboardingState.value == OracleOnboardingState.WaitingForAuthReturn
        ) {
            _oracleOnboardingState.value = OracleOnboardingState.AuthReturned
            refreshOracleOperationDiagnostics()
            persistState()
        }
    }

    fun onAppResumed() {
        if (_oracleOnboardingState.value == OracleOnboardingState.SignupLaunched) {
            _oracleOnboardingState.value = OracleOnboardingState.WaitingForAccountSetup
            persistState()
        } else if (_oracleOnboardingState.value == OracleOnboardingState.AuthLaunched) {
            _oracleOnboardingState.value = OracleOnboardingState.WaitingForAuthReturn
            refreshOracleOperationDiagnostics()
            persistState()
        }
    }

    fun cancel() {
        clearPendingRetryCredentials()
        _events.value = emptyList()
        _currentPhase.value = null
        _publicIp.value = null
        _sshDebugInfo.value = null
        clientConfig = null
        wireGuardClientPublicKey = null
        wireGuardServerPublicKey = null
        wireGuardServerPeerPublicKey = null
        resourceIds = null
        authResult = null
        preflightResult = null
        pendingProvisionExitId = null
        pendingCapacityRetryEligible = false
        clearPendingCapacityRetryMetadata()
        clearOracleOperationState()
        provisioningJob?.cancel()
        _capacityRetryPolicyEnabled.value = false
        capacityRetryRepository?.setCapacityRetryPolicyEnabled(false)
        _oracleOnboardingState.value = OracleOnboardingState.NotStarted
        restoreStateFromSelectedExitOrIdle()
        persistState()
    }

    fun selectExit(exitId: String?) {
        _selectedExitId.value = exitId?.takeIf { id -> _configuredExits.value.any { it.id == id } }
            ?: _configuredExits.value.firstOrNull()?.id
        val selected = _configuredExits.value.firstOrNull { it.id == _selectedExitId.value }
        if (selected != null) {
            _publicIp.value = selected.publicIp
            _wireGuardPort.value = selected.wireGuardPort
            clientConfig = selected.wireGuardConfig
            resourceIds = selected.ociResourceIds?.toProvisionerResourceIds()
            apiKeyUserOcid = selected.apiKeyUserOcid
            apiKeyTenancyOcid = selected.apiKeyTenancyOcid
            apiKeyFingerprint = selected.apiKeyFingerprint
            _sshDebugInfo.value = selected.sshPrivateKeySecretKey?.takeIf { secretStore.hasSecret(it) }?.let {
                SshDebugInfo(
                    publicIp = selected.publicIp,
                    username = selected.sshUsername ?: "ubuntu",
                    privateKeyPresent = true,
                )
            }
        }
        refreshOracleOperationDiagnostics()
        persistState()
    }

    fun startCapacityRetry(context: Context) {
        if (!::prefs.isInitialized) initPrefs(context)
        _capacityRetryPolicyEnabled.value = true
        capacityRetryRepository?.setCapacityRetryPolicyEnabled(true)
        startCapacityRetryFromStoredCredentials(context.applicationContext)
    }

    private fun startCapacityRetryFromStoredCredentials(context: Context): CapacityRetrySession? {
        val lease = provisioningLease
        if (
            _state.value is ProvisioningState.Running ||
            provisioningJob?.isActive == true ||
            lease?.isHeldByLiveProcess() == true
        ) {
            emit(
                Phase.VM_LAUNCH,
                Status.WARNING,
                "Capacity retry will wait until foreground provisioning releases its lease.",
            )
            return null
        }
        val repository = capacityRetryRepository ?: CapacityRetryRepository(prefs).also { capacityRetryRepository = it }
        val existing = repository.activeSession()
        if (existing?.state == CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED) {
            CapacityRetryWorkScheduler(context.applicationContext).cancel(existing)
            _capacityRetrySessions.value = repository.sessions()
            emit(
                Phase.VM_LAUNCH,
                Status.ERROR,
                CapacityRetryRepository.AMBIGUOUS_RECONCILIATION_MESSAGE,
            )
            persistState()
            return existing
        }
        if (!pendingCapacityRetryEligible && existing == null) {
            emit(
                Phase.VM_LAUNCH,
                Status.ERROR,
                "Capacity retry can start only after a foreground A1 launch is deferred.",
            )
            persistState()
            return null
        }
        val session = existing ?: run {
            val provisioningId = pendingProvisionExitId
            if (provisioningId.isNullOrBlank()) {
                emit(Phase.VM_LAUNCH, Status.ERROR, "The saved provisioning session ID is missing; capacity retry was not started.")
                return null
            }
            val startResult = CapacityRetrySessionStarter(
                repository = repository,
                vault = RetryCredentialVault(secretStore),
            ).start(
                CapacityRetryStartContext(
                    provisioningId = provisioningId,
                    candidateId = provisioningId,
                    mode = CapacityRetryMode.INITIAL_PRIVATE_CHAT,
                    sourceExitId = null,
                    compartmentOcid = apiKeyTenancyOcid,
                    userOcid = apiKeyUserOcid,
                    tenancyOcid = apiKeyTenancyOcid,
                    fingerprint = apiKeyFingerprint,
                    selectedRegion = homeRegion ?: _selectedOracleRegion.value,
                    tokenRegion = apiKeyTokenRegion,
                    tokenRegionSource = apiKeyTokenRegionSource,
                    subnetId = retryLaunchSubnetId,
                    sshPublicKey = retryLaunchSshPublicKey,
                    initialLaunchAttemptFinishedAtUtc = pendingRetryLastLaunchFinishedAtUtc,
                    initialNextEligibleAttemptAtUtc = pendingRetryNextEligibleAtUtc,
                    pendingMemoryGb = pendingRetryMemoryGb,
                    initialAttemptMemoryGb = 6,
                    initialHttpStatus = pendingRetryHttpStatus,
                    initialOciErrorCode = if (pendingRetryLastResult == "OUT_OF_HOST_CAPACITY") "InternalError" else "TooManyRequests",
                    initialLastResult = pendingRetryLastResult,
                    availabilityDomain = resourceIds?.availabilityDomain,
                    ubuntuImageOcid = resourceIds?.ubuntuImageOcid,
                    vcnOcid = resourceIds?.vcnId,
                    identityHost = null,
                    iaasHost = null,
                ),
            )
            if (startResult is CapacityRetryStartResult.MissingStoredCredentials) {
                _capacityRetrySessions.value = repository.sessions()
                emit(
                    Phase.VM_LAUNCH,
                    Status.ERROR,
                    "The original API signing credentials are missing or unreadable. ZeroVPN did not create another API key.",
                )
                persistState()
                return null
            }
            (startResult as CapacityRetryStartResult.Started).session
        }
        pendingCapacityRetryEligible = false
        _capacityRetrySessions.value = repository.sessions()
        emit(
            Phase.VM_LAUNCH,
            Status.WARNING,
            if (session.lastResult == "RATE_LIMITED") {
                "Oracle is temporarily rate limiting VM requests. The saved session will wait for its next eligible slot."
            } else {
                "Capacity retry is waiting for the next 15-minute slot and will stop at ${session.deadlineUtc} UTC."
            },
        )
        if (
            (_capacityRetryPolicyEnabled.value || session.mode == CapacityRetryMode.DEFERRED_PRIVATE_CHAT_CANDIDATE) &&
            shouldEnqueueCapacityRetry(
                session = session,
                topLevelProvisioningRunning = _state.value is ProvisioningState.Running,
                provisioningJobActive = provisioningJob?.isActive == true,
                provisioningLeaseHeld = lease?.isHeldByLiveProcess() == true,
            )
        ) {
            CapacityRetryWorkScheduler(context.applicationContext).enqueue(session)
        }
        persistState()
        return session
    }

    private fun maybeStartAutomaticCapacityRetry(context: Context) {
        if (
            (_capacityRetryPolicyEnabled.value || pendingRetryLastResult == "RATE_LIMITED") &&
            pendingCapacityRetryEligible &&
            _privateChatRequested.value &&
            _state.value !is ProvisioningState.Running &&
            provisioningJob?.isActive != true
        ) {
            startCapacityRetryFromStoredCredentials(context.applicationContext)
        }
    }

    private fun startCapacityRetryWorkObservation(context: Context) {
        if (capacityRetryWorkObservationJob != null) return
        val appContext = context.applicationContext
        capacityRetryWorkMonitor = capacityRetryWorkMonitor ?: CapacityRetryWorkMonitor(appContext)
        capacityRetryWorkObservationJob = viewModelScope.launch {
            _capacityRetrySessions.collect { sessions ->
                syncCapacityRetryWorkObservers(sessions)
            }
        }
    }

    private fun syncCapacityRetryWorkObservers(sessions: List<CapacityRetrySession>) {
        val monitor = capacityRetryWorkMonitor ?: return
        val desired = sessions
            .filter {
                it.state != CapacityRetryState.NONE &&
                    it.state != CapacityRetryState.SUCCEEDED
            }
            .associateBy(CapacityRetrySession::sessionId)

        capacityRetryWorkObservers.keys
            .filterNot(desired::containsKey)
            .toList()
            .forEach { sessionId ->
                capacityRetryWorkObservers.remove(sessionId)?.let { registration ->
                    registration.liveData.removeObserver(registration.observer)
                }
            }

        desired.values.forEach { session ->
            if (capacityRetryWorkObservers.containsKey(session.sessionId)) return@forEach
            if (!_capacityRetrySchedulerStatuses.value.containsKey(session.sessionId)) {
                publishCapacityRetrySchedulerStatus(
                    session.sessionId,
                    CapacityRetrySchedulerStatus.checking(),
                )
            }
            val liveData = monitor.liveData(session.uniqueWorkName)
            val observer = Observer<List<WorkInfo>> { workInfos ->
                val previous = _capacityRetrySchedulerStatuses.value[session.sessionId]
                val observed = capacityRetrySchedulerStatus(workInfos.orEmpty()).copy(
                    reconciliationMessage = previous?.reconciliationMessage,
                )
                publishCapacityRetrySchedulerStatus(session.sessionId, observed)
                refreshCapacityRetrySessionsFromRepository()
                refreshCapacityRetryDiagnostics()
            }
            capacityRetryWorkObservers[session.sessionId] =
                WorkInfoObserverRegistration(liveData, observer)
            liveData.observeForever(observer)
        }
    }

    private fun publishCapacityRetrySchedulerStatus(
        sessionId: String,
        status: CapacityRetrySchedulerStatus,
    ) {
        _capacityRetrySchedulerStatuses.value =
            _capacityRetrySchedulerStatuses.value + (sessionId to status)
    }

    private fun refreshCapacityRetryDiagnostics() {
        if (!::prefs.isInitialized) return
        _capacityRetryDiagnostics.value = CapacityRetryDiagnosticLog(prefs).entries()
    }

    private fun refreshCapacityRetrySessionsFromRepository() {
        val repository = capacityRetryRepository ?: return
        _capacityRetrySessions.value = repository.sessions()
        if (_capacityRetrySessions.value.hasAuthoritativePrivateChatRetry()) {
            _privateChatRequested.value = true
        }
    }

    private suspend fun reconcileActiveCapacityRetrySessions(context: Context) {
        val repository = capacityRetryRepository ?: if (::prefs.isInitialized) {
            CapacityRetryRepository(prefs).also { capacityRetryRepository = it }
        } else {
            return
        }
        val appContext = context.applicationContext
        val monitor = capacityRetryWorkMonitor ?: CapacityRetryWorkMonitor(appContext).also {
            capacityRetryWorkMonitor = it
        }
        val scheduler = CapacityRetryWorkScheduler(appContext)
        val diagnosticLog = CapacityRetryDiagnosticLog.fromContext(appContext)
        val vault = RetryCredentialVault(secretStore)
        val now = Instant.now()
        val topLevelProvisioningRunning = _state.value is ProvisioningState.Running
        val provisioningJobActive = provisioningJob?.isActive == true
        val provisioningLeaseHeld = provisioningLease?.isHeldByLiveProcess() == true
        repository.sessions().forEach { session ->
            if (
                session.state in setOf(CapacityRetryState.WAITING_FOR_RETRY, CapacityRetryState.ACTIVE) &&
                runCatching { !now.isBefore(Instant.parse(session.deadlineUtc)) }.getOrDefault(true)
            ) {
                val timedOut = repository.markTimedOutIfNeeded(session.sessionId) ?: session
                scheduler.cancel(timedOut)
                vault.clearCredentials(session.sessionId)
                vault.clearApiKeyCredentials(session.sessionId)
                diagnosticLog.append(
                    session.sessionId,
                    "Scheduler reconciliation: retry deadline expired; background work cancelled",
                )
                return@forEach
            }
            if (session.state !in setOf(CapacityRetryState.WAITING_FOR_RETRY, CapacityRetryState.ACTIVE)) {
                return@forEach
            }

            val observed = try {
                capacityRetrySchedulerStatus(monitor.query(session.uniqueWorkName), now)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                diagnosticLog.append(
                    session.sessionId,
                    "Scheduler reconciliation: WorkManager query failed (" +
                        error.javaClass.simpleName + ")",
                )
                CapacityRetrySchedulerStatus(
                    state = CapacityRetrySchedulerState.CHECKING,
                    detail = "Android's scheduler state could not be read yet.",
                    observedAtUtc = now.toString(),
                    reconciliationMessage = "Scheduler query failed; existing work was not replaced.",
                )
            }
            publishCapacityRetrySchedulerStatus(session.sessionId, observed)
            if (observed.state == CapacityRetrySchedulerState.CHECKING) return@forEach

            val schedulingAllowed = shouldEnqueueCapacityRetry(
                session = session,
                topLevelProvisioningRunning = topLevelProvisioningRunning,
                provisioningJobActive = provisioningJobActive,
                provisioningLeaseHeld = provisioningLeaseHeld,
            )
            val credentialsAvailable = if (!schedulingAllowed || observed.hasScheduledWork) {
                true
            } else {
                withContext(Dispatchers.IO) {
                    vault.loadCredentials(session.sessionId) != null
                }
            }
            when (
                decideCapacityRetryReconciliation(
                    session = session,
                    schedulerState = observed.state,
                    now = now,
                    schedulingAllowed = schedulingAllowed,
                    credentialsAvailable = credentialsAvailable,
                )
            ) {
                CapacityRetryReconciliationAction.KEEP -> {
                    diagnosticLog.append(
                        session.sessionId,
                        "Scheduler reconciliation: " + observed.state.name,
                    )
                }
                CapacityRetryReconciliationAction.REENQUEUE -> {
                    val reEnqueued = scheduler.enqueueAndAwait(session)
                    val message = if (reEnqueued) {
                        "Re-enqueued background work. Android will schedule the next attempt."
                    } else {
                        "Background work needs re-enqueueing, but WorkManager did not confirm the request."
                    }
                    publishCapacityRetrySchedulerStatus(
                        session.sessionId,
                        observed.copy(reconciliationMessage = message),
                    )
                    diagnosticLog.append(session.sessionId, "Scheduler reconciliation: " + message)
                    emit(
                        Phase.VM_LAUNCH,
                        Status.WARNING,
                        message,
                    )
                }
                CapacityRetryReconciliationAction.PAUSE_AUTH_REQUIRED -> {
                    repository.updateSession(session.sessionId) {
                        it.copy(
                            state = CapacityRetryState.PAUSED_AUTH_REQUIRED,
                            lastResult = "PAUSED_AUTH_REQUIRED",
                            lastSafeErrorCategory = "auth-required",
                            requiresUserAction = true,
                            terminalReason = "The saved Oracle signing credentials are missing or unreadable.",
                        )
                    }
                    diagnosticLog.append(
                        session.sessionId,
                        "Scheduler reconciliation: credentials unavailable; session paused",
                    )
                }
                CapacityRetryReconciliationAction.NONE -> Unit
            }
        }
        _capacityRetrySessions.value = repository.sessions()
        if (_capacityRetrySessions.value.hasAuthoritativePrivateChatRetry()) {
            _privateChatRequested.value = true
        }
        refreshCapacityRetryDiagnostics()
    }

    fun reconcileCapacityRetryWork(context: Context) {
        if (!::prefs.isInitialized) {
            initPrefs(context)
            return
        }
        val appContext = context.applicationContext
        startCapacityRetryWorkObservation(appContext)
        capacityRetryReconciliationJob?.cancel()
        capacityRetryReconciliationJob = viewModelScope.launch {
            reconcileActiveCapacityRetrySessions(appContext)
            persistState()
        }
    }

    fun stopActiveCapacityRetry(context: Context) {
        if (!::prefs.isInitialized) initPrefs(context)
        capacityRetryReconciliationJob?.cancel()
        val repository = capacityRetryRepository ?: CapacityRetryRepository(prefs).also { capacityRetryRepository = it }
        val session = repository.activeSession()
        _capacityRetryPolicyEnabled.value = false
        repository.setCapacityRetryPolicyEnabled(false)
        if (session == null) {
            clearPendingRetryCredentials()
            pendingCapacityRetryEligible = false
            clearPendingCapacityRetryMetadata()
            persistState()
            return
        }
        if (session.state == CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED) {
            CapacityRetryWorkScheduler(context.applicationContext).cancel(session)
            _capacityRetrySessions.value = repository.sessions()
            emit(
                Phase.VM_LAUNCH,
                Status.ERROR,
                "The ambiguous Oracle launch must be reconciled before this session can be cancelled.",
            )
            persistState()
            return
        }
        val cancelled = repository.cancelSession(session.sessionId) ?: session
        RetryCredentialVault(secretStore).clearCredentials(session.sessionId)
        RetryCredentialVault(secretStore).clearApiKeyCredentials(session.sessionId)
        CapacityRetryWorkScheduler(context.applicationContext).cancel(cancelled)
        _capacityRetrySessions.value = repository.sessions()
        clearPendingCapacityRetryMetadata()
        emit(Phase.VM_LAUNCH, Status.WARNING, "Capacity retry session cancelled by user.")
        persistState()
    }

    fun setupVpnOnlyInstead(context: Context) {
        if (!::prefs.isInitialized) initPrefs(context)
        val reconciliationBlocker = capacityRetryRepository
            ?.activeSession()
            ?.takeIf { it.state == CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED }
        if (reconciliationBlocker != null) {
            emit(
                Phase.VM_LAUNCH,
                Status.ERROR,
                "Reconcile the ambiguous Oracle launch before starting VPN-only provisioning.",
            )
            _state.value = ProvisioningState.Failure(
                Phase.VM_LAUNCH,
                Phase.NETWORK,
                reconciliationBlocker.terminalReason
                    ?: CapacityRetryRepository.AMBIGUOUS_RECONCILIATION_MESSAGE,
            )
            persistState()
            return
        }
        stopActiveCapacityRetry(context)
        _privateChatRequested.value = false
        _capacityRetryPolicyEnabled.value = false
        pendingCapacityRetryEligible = false
        clearPendingCapacityRetryMetadata()
        clearPendingRetryCredentials()
        emit(Phase.VM_LAUNCH, Status.WARNING, "VPN-only bypass selected. Private Chat is deferred and will not be installed on this VM.")
        _state.value = ProvisioningState.PreStart
        persistState()
        startProvisioning(context)
    }

    fun addPrivateChatCandidate(context: Context, sourceExitId: String) {
        if (!::prefs.isInitialized) initPrefs(context)
        val source = _configuredExits.value.firstOrNull { it.id == sourceExitId && it.provider == ExitProvider.OCI } ?: return
        val repository = capacityRetryRepository ?: CapacityRetryRepository(prefs).also { capacityRetryRepository = it }
        val candidate = repository.createCandidate(sourceExitId)
        _privateChatCandidates.value = repository.candidates()
        updateExit(source.id) {
            it.copy(
                privateChatStatus = PrivateChatCapabilityStatus.DEFERRED,
                privateChatCandidateId = candidate.candidateId,
            )
        }
        val session = repository.createSession(
            candidateId = candidate.candidateId,
            mode = CapacityRetryMode.DEFERRED_PRIVATE_CHAT_CANDIDATE,
            sourceExitId = source.id,
            compartmentOcid = source.apiKeyTenancyOcid,
        )
        _capacityRetrySessions.value = repository.sessions()
        CapacityRetryWorkScheduler(context.applicationContext).enqueue(session)
        emit(Phase.VM_LAUNCH, Status.WARNING, "Deferred chat candidate created for ${source.name}; current VPN remains active.")
        persistState()
    }

    fun markCandidateReadyToSwitch(candidateId: String) {
        if (!::prefs.isInitialized) return
        val repository = capacityRetryRepository ?: CapacityRetryRepository(prefs).also { capacityRetryRepository = it }
        val candidate = repository.candidates().firstOrNull { it.candidateId == candidateId } ?: return
        val candidateExit = candidate.candidateExitId?.let { id -> _configuredExits.value.firstOrNull { it.id == id } } ?: return
        if (!candidateExit.isReadyPrivateChatCandidate()) {
            emit(Phase.PRIVATE_CHAT_ENCRYPTION_SELF_TEST, Status.WARNING, "Candidate is not ready to switch; required runtime checks have not all passed.")
            return
        }
        if (!acquireProvisioningLease(ProvisioningLeaseOperation.CANDIDATE_RECONCILIATION)) return
        try {
            val now = Instant.now().toString()
            repository.replaceCandidate(
                candidate.copy(
                    state = DeferredCandidateState.READY_TO_SWITCH,
                    readyToSwitchAtUtc = now,
                    updatedAtUtc = now,
                ),
            )
            _privateChatCandidates.value = repository.candidates()
            updateExit(candidateExit.id) { it.copy(privateChatStatus = PrivateChatCapabilityStatus.READY_TO_SWITCH) }
            updateExit(candidate.sourceExitId) { it.copy(privateChatStatus = PrivateChatCapabilityStatus.READY_TO_SWITCH) }
            emit(Phase.PRIVATE_CHAT_ENCRYPTION_SELF_TEST, Status.SUCCESS, "Deferred chat candidate ready to switch.")
            persistState()
        } finally {
            provisioningLease?.release()
        }
    }

    fun switchToPrivateChatCandidate(candidateId: String) {
        val repository = capacityRetryRepository ?: return
        val candidate = repository.candidates().firstOrNull { it.candidateId == candidateId } ?: return
        val source = _configuredExits.value.firstOrNull { it.id == candidate.sourceExitId } ?: return
        val candidateExit = candidate.candidateExitId?.let { id -> _configuredExits.value.firstOrNull { it.id == id } } ?: return
        if (!candidateExit.isReadyPrivateChatCandidate() || candidate.state != DeferredCandidateState.READY_TO_SWITCH) {
            emit(Phase.PRIVATE_CHAT_ENCRYPTION_SELF_TEST, Status.WARNING, "Switch blocked until candidate VPN and Private Chat checks pass.")
            return
        }
        if (!acquireProvisioningLease(ProvisioningLeaseOperation.CANDIDATE_SWITCH)) return
        try {
            emit(Phase.PRIVATE_CHAT_ENCRYPTION_SELF_TEST, Status.RUNNING, "Switch started. Old VM will be retained for rollback.")
            val previousSelected = _selectedExitId.value
            runCatching {
                _configuredExits.value = _configuredExits.value.map { exit ->
                    when (exit.id) {
                        source.id -> exit.copy(
                            privateChatStatus = PrivateChatCapabilityStatus.DEFERRED,
                            privateChatCandidateId = null,
                        )
                        candidateExit.id -> exit.copy(
                            privateChatStatus = PrivateChatCapabilityStatus.HEALTHY,
                            sourceExitId = source.id,
                            previousExitId = source.id,
                        )
                        else -> exit
                    }
                }
                _selectedExitId.value = candidateExit.id
                repository.replaceCandidate(candidate.copy(state = DeferredCandidateState.SWITCHED, updatedAtUtc = Instant.now().toString()))
                _privateChatCandidates.value = repository.candidates()
                persistState()
            }.onFailure { error ->
                _selectedExitId.value = previousSelected ?: source.id
                updateExit(candidateExit.id) {
                    it.copy(
                        privateChatStatus = PrivateChatCapabilityStatus.SWITCH_FAILED,
                        lastError = error.message?.sanitizeInviteDiagnostic() ?: "Candidate switch failed; old exit restored.",
                    )
                }
                emit(Phase.PRIVATE_CHAT_ENCRYPTION_SELF_TEST, Status.ERROR, "Switch failed and old exit restored.")
                persistState()
                return
            }
            emit(Phase.PRIVATE_CHAT_ENCRYPTION_SELF_TEST, Status.SUCCESS, "Switch succeeded. Old VM retained as rollback.")
        } finally {
            provisioningLease?.release()
        }
    }

    fun setDevMode(enabled: Boolean) {
        _isDevMode.value = enabled
        persistState()
    }

    fun selectOracleRegion(region: String?) {
        _selectedOracleRegion.value = region?.takeIf { selected ->
            oracleRegions.any { it.id == selected }
        }
        if (_selectedOracleRegion.value != null) {
            homeRegion = null
            preflightResult = null
        }
        persistState()
    }

    fun updateProviderSwitchDiagnostics(diagnostics: ProviderSwitchDiagnostics) {
        _providerSwitchDiagnostics.value = diagnostics
    }

    fun getInviteSlotsForOwnerExit(ownerExitId: String): List<InviteSlot> =
        _inviteSlots.value.filter { it.ownerExitId == ownerExitId }.sortedBy { it.slotIndex }

    fun getPendingInviteSlotsForOwnerExit(ownerExitId: String): List<InviteSlot> =
        _inviteSlots.value
            .filter { it.ownerExitId == ownerExitId && it.state == InviteSlotState.PENDING_CLAIM }
            .sortedBy { it.slotIndex }

    fun getInviteSlotClientConfig(slotId: String): String? {
        val slot = _inviteSlots.value.firstOrNull { it.slotId == slotId } ?: return null
        return slot.clientConfigSecretKey?.let { secretStore.getSecret(it) }
            ?: slot.encryptedClientConfig?.takeIf { it.isNotBlank() }
    }

    fun hasInviteSlotPrivateMaterial(slot: InviteSlot): Boolean =
        slot.clientConfigSecretKey?.let { secretStore.hasSecret(it) } == true ||
            !slot.encryptedClientConfig.isNullOrBlank() ||
            !slot.encryptedClientPrivateKey.isNullOrBlank()

    fun hasExitWireGuardConfig(exit: ConfiguredExit): Boolean =
        exit.wireGuardConfigSecretKey?.let { secretStore.hasSecret(it) } == true ||
            exit.wireGuardConfig.isNotBlank()

    fun hasExitSshPrivateKey(exit: ConfiguredExit): Boolean =
        exit.sshPrivateKeySecretKey?.let { secretStore.hasSecret(it) } == true ||
            !exit.sshPrivateKey.isNullOrBlank()

    fun hasSharedExitConfig(profile: SharedExitProfile): Boolean =
        profile.wireGuardConfigSecretKey?.let { secretStore.hasSecret(it) } == true ||
            !profile.encryptedWireGuardConfig.isNullOrBlank()

    fun upsertInviteSlot(slot: InviteSlot) {
        friendsRepository?.let { repository ->
            _inviteSlots.value = repository.upsertInviteSlot(slot)
        }
    }

    fun renameInviteSlot(slotId: String, name: String?) {
        friendsRepository?.let { repository ->
            _inviteSlots.value = repository.renameInviteSlot(slotId, name)
        }
    }

    fun updateInviteSlotState(slotId: String, state: InviteSlotState) {
        friendsRepository?.let { repository ->
            _inviteSlots.value = repository.updateInviteSlotState(slotId, state)
        }
    }

    fun markInviteSlotPending(slotId: String, qrShownAt: Long) {
        friendsRepository?.let { repository ->
            _inviteSlots.value = repository.markInviteSlotPending(slotId, qrShownAt)
        }
    }

    fun markInviteSlotClaimed(slotId: String, firstHandshakeAt: Long, lastHandshakeAt: Long) {
        _inviteSlots.value.firstOrNull { it.slotId == slotId }?.clientConfigSecretKey?.let {
            secretStore.removeSecret(it)
        }
        friendsRepository?.let { repository ->
            _inviteSlots.value = repository.markInviteSlotClaimed(slotId, firstHandshakeAt, lastHandshakeAt)
        }
    }

    fun clearBurnedPrivateMaterial(slotId: String) {
        _inviteSlots.value.firstOrNull { it.slotId == slotId }?.clientConfigSecretKey?.let {
            secretStore.removeSecret(it)
        }
        friendsRepository?.let { repository ->
            _inviteSlots.value = repository.clearBurnedPrivateMaterial(slotId)
        }
    }

    fun updateInviteSlotLastHandshake(slotId: String, lastHandshakeAt: Long) {
        friendsRepository?.let { repository ->
            _inviteSlots.value = repository.updateInviteSlotLastHandshake(slotId, lastHandshakeAt)
        }
    }

    fun checkInviteSlotClaim(
        context: Context,
        slotId: String,
        onComplete: (InviteClaimCheckResult) -> Unit,
    ) {
        val slot = _inviteSlots.value.firstOrNull { it.slotId == slotId }
        if (slot == null) {
            onComplete(InviteClaimCheckResult.Error("This invite slot was not found."))
            return
        }
        if (slot.state != InviteSlotState.PENDING_CLAIM) {
            onComplete(InviteClaimCheckResult.Error("Only pending invite slots can be checked."))
            return
        }
        val peerPublicKey = slot.peerPublicKey?.takeIf { it.isNotBlank() }
        if (peerPublicKey == null) {
            onComplete(InviteClaimCheckResult.Error("This invite slot does not have a peer public key."))
            return
        }
        val ownerExit = _configuredExits.value.firstOrNull { it.id == slot.ownerExitId }
        if (ownerExit == null) {
            onComplete(InviteClaimCheckResult.Error("The owner exit for this invite was not found."))
            return
        }
        val sshPrivateKey = ownerExit.sshPrivateKeySecretKey
            ?.let { secretStore.getSecret(it) }
            ?: ownerExit.sshPrivateKey
        if (sshPrivateKey.isNullOrBlank()) {
            onComplete(InviteClaimCheckResult.Error("Owner key is missing for this exit. Recreate the exit or use a future recovery flow."))
            return
        }
        viewModelScope.launch {
            when (val result = InviteHandshakeChecker(context.applicationContext).queryLatestHandshakes(ownerExit, sshPrivateKey)) {
                is HandshakeQueryResult.MissingSshCredentials -> {
                    onComplete(
                        InviteClaimCheckResult.Error(
                            "This app session does not have the VM SSH key needed to verify the claim. Owner verification/recovery will be added later.",
                        ),
                    )
                }
                is HandshakeQueryResult.Failed -> {
                    onComplete(
                        InviteClaimCheckResult.Error(
                            result.message.takeIf { it.isNotBlank() }
                                ?: "Could not check this invite claim. Please try again.",
                        ),
                    )
                }
                is HandshakeQueryResult.Success -> {
                    val latestHandshakeSeconds = result.latestHandshakes[peerPublicKey]
                    if (latestHandshakeSeconds == null) {
                        onComplete(InviteClaimCheckResult.Error("This invite peer was not found on the exit."))
                        return@launch
                    }
                    if (latestHandshakeSeconds <= 0L) {
                        onComplete(InviteClaimCheckResult.NotClaimed)
                        return@launch
                    }
                    val handshakeAt = latestHandshakeSeconds * 1000L
                    slot.clientConfigSecretKey?.let { secretStore.removeSecret(it) }
                    friendsRepository?.let { repository ->
                        _inviteSlots.value = repository.markInviteSlotClaimed(
                            slotId = slot.slotId,
                            firstHandshakeAt = handshakeAt,
                            lastHandshakeAt = handshakeAt,
                        )
                    }
                    onComplete(InviteClaimCheckResult.Claimed)
                }
            }
        }
    }

    fun resetInviteSlot(
        context: Context,
        slotId: String,
        onComplete: (InviteResetResult) -> Unit,
    ) {
        val slot = _inviteSlots.value.firstOrNull { it.slotId == slotId }
        if (slot == null) {
            recordInviteOperationError("reset phase=${InvitePeerResetPhase.PRECHECK_MISSING_SLOT.name} slotId=$slotId message=This invite slot was not found.")
            onComplete(InviteResetResult.Error("This invite slot was not found."))
            return
        }
        if (slot.state != InviteSlotState.PENDING_CLAIM && slot.state != InviteSlotState.CLAIMED) {
            recordInviteOperationError(
                "reset phase=${InvitePeerResetPhase.PRECHECK_MISSING_SLOT.name} " +
                    "ownerExitId=${slot.ownerExitId} slotId=${slot.slotId} slotIndex=${slot.slotIndex} " +
                    "state=${slot.state.name} message=Only pending or claimed invite slots can be reset.",
            )
            onComplete(InviteResetResult.Error("Only pending or claimed invite slots can be reset."))
            return
        }
        val ownerExit = _configuredExits.value.firstOrNull { it.id == slot.ownerExitId }
        if (ownerExit == null) {
            recordInviteOperationError(
                "reset phase=${InvitePeerResetPhase.PRECHECK_MISSING_OWNER_EXIT.name} " +
                    "ownerExitId=${slot.ownerExitId} slotId=${slot.slotId} slotIndex=${slot.slotIndex} " +
                    "message=The owner exit for this invite was not found.",
            )
            onComplete(InviteResetResult.Error("The owner exit for this invite was not found."))
            return
        }
        val sshPrivateKey = ownerExit.sshPrivateKeySecretKey
            ?.let { secretStore.getSecret(it) }
            ?: ownerExit.sshPrivateKey
        if (sshPrivateKey.isNullOrBlank()) {
            recordInviteOperationError(
                buildInviteResetErrorDetail(
                    phase = InvitePeerResetPhase.PRECHECK_MISSING_SSH_KEY,
                    ownerExit = ownerExit,
                    slot = slot,
                    message = "Owner key is missing for this exit.",
                ),
            )
            onComplete(InviteResetResult.Error("Owner key is missing for this exit. Recreate the exit or use a future recovery flow."))
            return
        }
        val finalSecretKey = SecureSecretStore.inviteClientConfig(slot.slotId)
        val tempSecretKey = "$finalSecretKey:pendingReset"
        _lastInviteOperationError.value = null
        persistState()
        viewModelScope.launch {
            when (
                val result = InvitePeerResetter(context.applicationContext).resetPeer(
                    ownerExit = ownerExit,
                    slot = slot,
                    sshPrivateKey = sshPrivateKey,
                    beforeServerMutation = { clientConfig ->
                        secretStore.putSecret(tempSecretKey, clientConfig)
                    },
                )
            ) {
                is InvitePeerResetResult.Failed -> {
                    val cleanupError = runCatching { secretStore.removeSecret(tempSecretKey) }.exceptionOrNull()
                    val serverMayHaveInvalidatedOldInvite = result.phase.mayHaveInvalidatedOldInvite()
                    if (serverMayHaveInvalidatedOldInvite) {
                        slot.clientConfigSecretKey?.let { runCatching { secretStore.removeSecret(it) } }
                        friendsRepository?.let { repository ->
                            _inviteSlots.value = repository.markInviteSlotRevoked(slot.slotId, System.currentTimeMillis())
                        }
                    }
                    val detail = buildInviteResetErrorDetail(
                        phase = result.phase,
                        ownerExit = ownerExit,
                        slot = slot,
                        message = result.message,
                        exitCode = result.exitCode,
                        commandStderr = result.commandStderr,
                        cleanupError = cleanupError?.message,
                        oldInviteInvalidated = serverMayHaveInvalidatedOldInvite,
                    )
                    recordInviteOperationError(detail)
                    onComplete(
                        InviteResetResult.Error(
                            "Could not reset this invite. Open Diagnostics for details.",
                        ),
                    )
                }
                is InvitePeerResetResult.Success -> {
                    try {
                        runCatching { secretStore.putSecret(finalSecretKey, result.clientConfig) }.getOrElse { e ->
                            val detail = buildInviteResetErrorDetail(
                                phase = InvitePeerResetPhase.FINAL_SECRET_STORE_FAILED,
                                ownerExit = ownerExit,
                                slot = slot,
                                message = e.message ?: "Secure-store write failed after server reset.",
                            )
                            recordInviteOperationError(detail)
                            secretStore.removeSecret(tempSecretKey)
                            onComplete(InviteResetResult.Error("Could not reset this invite. Open Diagnostics for details."))
                            return@launch
                        }
                        runCatching {
                            val repository = friendsRepository ?: error("Friends repository is not available.")
                            _inviteSlots.value = repository.resetInviteSlotAfterRevoke(
                                slotId = slot.slotId,
                                newPeerPublicKey = result.newPeerPublicKey,
                                tunnelIp = result.tunnelIp,
                                clientConfigSecretKey = finalSecretKey,
                                resetAt = System.currentTimeMillis(),
                            )
                        }.getOrElse { e ->
                            val detail = buildInviteResetErrorDetail(
                                phase = InvitePeerResetPhase.LOCAL_METADATA_UPDATE_FAILED,
                                ownerExit = ownerExit,
                                slot = slot,
                                message = e.message ?: "Local invite metadata update failed after server reset.",
                            )
                            recordInviteOperationError(detail)
                            secretStore.removeSecret(tempSecretKey)
                            onComplete(InviteResetResult.Error("Could not reset this invite. Open Diagnostics for details."))
                            return@launch
                        }
                        slot.clientConfigSecretKey
                            ?.takeIf { it != finalSecretKey }
                            ?.let { secretStore.removeSecret(it) }
                        val cleanupError = runCatching { secretStore.removeSecret(tempSecretKey) }.exceptionOrNull()
                        if (cleanupError != null) {
                            recordInviteOperationError(
                                buildInviteResetErrorDetail(
                                    phase = InvitePeerResetPhase.CLEANUP_TEMP_SECRET_FAILED,
                                    ownerExit = ownerExit,
                                    slot = slot,
                                    message = cleanupError.message ?: "Temporary invite reset secret cleanup failed.",
                                ),
                            )
                        } else {
                            _lastInviteOperationError.value = null
                        }
                        persistState()
                        onComplete(InviteResetResult.Reset)
                    } catch (e: Exception) {
                        val cleanupError = runCatching { secretStore.removeSecret(tempSecretKey) }.exceptionOrNull()
                        val detail = buildInviteResetErrorDetail(
                            phase = InvitePeerResetPhase.UNKNOWN,
                            ownerExit = ownerExit,
                            slot = slot,
                            message = e.message ?: "Local reset completion failed after server reset.",
                            cleanupError = cleanupError?.message,
                        )
                        recordInviteOperationError(detail)
                        onComplete(
                            InviteResetResult.Error(
                                "Could not reset this invite. Open Diagnostics for details.",
                            ),
                        )
                    }
                }
            }
        }
    }

    fun markInviteSlotRevoked(slotId: String, revokedAt: Long) {
        friendsRepository?.let { repository ->
            _inviteSlots.value = repository.markInviteSlotRevoked(slotId, revokedAt)
        }
    }

    fun addSharedExit(profile: SharedExitProfile) {
        friendsRepository?.let { repository ->
            _sharedExitProfiles.value = repository.addSharedExit(profile)
        }
    }

    fun renameSharedExit(profileId: String, name: String) {
        friendsRepository?.let { repository ->
            _sharedExitProfiles.value = repository.renameSharedExit(profileId, name)
        }
    }

    fun removeSharedExit(profileId: String) {
        friendsRepository?.let { repository ->
            _sharedExitProfiles.value = repository.removeSharedExit(profileId)
        }
    }

    fun hasImportedSharedExit(invite: ParsedWireGuardInvite): Boolean =
        _sharedExitProfiles.value.any { profile -> profile.configHash == invite.configHash } ||
            _configuredExits.value.any { exit ->
                exit.provider == ExitProvider.SHARED_WIREGUARD &&
                    exit.wireGuardConfig.trim() == invite.rawConfig.trim()
            }

    fun importSharedExit(invite: ParsedWireGuardInvite, displayName: String): ConfiguredExit? {
        if (hasImportedSharedExit(invite)) return null
        val now = System.currentTimeMillis()
        val profileId = "shared:${UUID.randomUUID()}"
        val name = displayName.trim().ifBlank { "Shared Exit" }
        val secretKey = SecureSecretStore.sharedWireGuardConfig(profileId)
        secretStore.putSecret(secretKey, invite.rawConfig)
        val profile = SharedExitProfile(
            id = profileId,
            displayName = name,
            source = SharedExitSource.IMPORTED_QR,
            providerType = SharedExitProviderType.SHARED_WIREGUARD,
            wireGuardConfigSecretKey = secretKey,
            configHash = invite.configHash,
            encryptedWireGuardConfig = null,
            endpointHost = invite.endpointHost,
            endpointIp = invite.endpointHost,
            importedAt = now,
            updatedAt = now,
        )
        val exit = ConfiguredExit(
            id = profileId,
            name = name,
            publicIp = invite.endpointHost,
            wireGuardPort = invite.endpointPort,
            region = "Shared Exit",
            wireGuardConfig = invite.rawConfig,
            wireGuardConfigSecretKey = secretKey,
            provider = ExitProvider.SHARED_WIREGUARD,
            endpointHost = invite.endpointHost,
            endpointPort = invite.endpointPort,
            lifecycleState = ExitLifecycleState.READY,
            createdAt = now,
            serverPublicKey = invite.peerPublicKey,
            serverPeerPublicKey = invite.clientPublicKey,
            clientPublicKey = invite.clientPublicKey,
            transportLabel = "Shared WireGuard exit",
            tcpSupported = true,
            udpSupported = true,
            destroyMeaning = "removeLocalProfile",
        )
        friendsRepository?.let { repository ->
            _sharedExitProfiles.value = repository.addSharedExit(profile)
        }
        _configuredExits.value = _configuredExits.value + exit
        _selectedExitId.value = exit.id
        restoreStateFromSelectedExitOrIdle()
        persistState()
        return exit
    }

    fun renameSharedExitProfile(profileId: String, name: String) {
        val trimmed = name.trim().ifBlank { "Shared Exit" }
        friendsRepository?.let { repository ->
            _sharedExitProfiles.value = repository.renameSharedExit(profileId, trimmed)
        }
        updateExit(profileId) { exit ->
            if (exit.provider == ExitProvider.SHARED_WIREGUARD) exit.copy(name = trimmed) else exit
        }
    }

    fun removeSharedExitProfile(profileId: String) {
        _sharedExitProfiles.value.firstOrNull { it.id == profileId }?.wireGuardConfigSecretKey?.let {
            secretStore.removeSecret(it)
        }
        _configuredExits.value.firstOrNull { it.id == profileId }?.wireGuardConfigSecretKey?.let {
            secretStore.removeSecret(it)
        }
        friendsRepository?.let { repository ->
            _sharedExitProfiles.value = repository.removeSharedExit(profileId)
        }
        _configuredExits.value = _configuredExits.value.filterNot {
            it.id == profileId && it.provider == ExitProvider.SHARED_WIREGUARD
        }
        if (_selectedExitId.value == profileId) {
            _selectedExitId.value = _configuredExits.value.firstOrNull()?.id
        }
        restoreStateFromSelectedExitOrIdle()
        persistState()
    }

    fun createVolunteerExit(): ConfiguredExit {
        _configuredExits.value.firstOrNull { it.provider == ExitProvider.VOLUNTEER }?.let { existing ->
            selectExit(existing.id)
            return existing
        }
        val exit = ConfiguredExit(
            id = "volunteer:${UUID.randomUUID()}",
            name = "Volunteer Exit",
            publicIp = "Unknown",
            wireGuardPort = 0,
            region = "Volunteer Network",
            wireGuardConfig = "",
            provider = ExitProvider.VOLUNTEER,
            endpointHost = "embedded-tor",
            endpointPort = 0,
            lifecycleState = ExitLifecycleState.READY,
            createdAt = System.currentTimeMillis(),
            transportLabel = "Volunteer Network",
            tcpSupported = true,
            udpSupported = false,
            dnsStatus = "Under validation",
            destroyMeaning = "removeLocalProfile",
        )
        _configuredExits.value = _configuredExits.value + exit
        _selectedExitId.value = exit.id
        _state.value = ProvisioningState.Success(
            publicIp = exit.publicIp,
            wireGuardPort = exit.wireGuardPort,
            region = exit.region,
            isDevMode = _isDevMode.value,
        )
        persistState()
        return exit
    }

    fun removeLocalExit(exitId: String) {
        _configuredExits.value.firstOrNull { it.id == exitId }?.let { cleanupOwnerExitLocalState(it) }
        _configuredExits.value = _configuredExits.value.filterNot { it.id == exitId }
        if (_selectedExitId.value == exitId) {
            _selectedExitId.value = _configuredExits.value.firstOrNull()?.id
        }
        restoreStateFromSelectedExitOrIdle()
        persistState()
    }

    fun clearTransientProvisioningSuccess() {
        if (_state.value is ProvisioningState.Success || _state.value is ProvisioningState.Destroyed) {
            restoreStateFromSelectedExitOrIdle()
            persistState()
        }
    }

    fun startProvisioning(context: Context) {
        if (!::prefs.isInitialized) initPrefs(context)
        capacityRetryRepository?.setCapacityRetryPolicyEnabled(_capacityRetryPolicyEnabled.value)
        if (_state.value is ProvisioningState.Running || provisioningJob?.isActive == true) return
        val retrySession = capacityRetryRepository?.activeSession()
        if (
            retrySession?.state in setOf(
                CapacityRetryState.WAITING_FOR_RETRY,
                CapacityRetryState.ACTIVE,
                CapacityRetryState.ACQUIRING,
            )
        ) {
            emit(Phase.VM_LAUNCH, Status.WARNING, "Stop the active capacity retry before starting another Oracle exit.")
            return
        }
        if (!acquireProvisioningLease(ProvisioningLeaseOperation.FOREGROUND_PROVISIONING)) return
        pendingProvisionExitId = pendingProvisionExitId ?: newExitId()
        pendingCapacityRetryEligible = false
        clearPendingCapacityRetryMetadata()
        setPendingOracleOperation(PendingOracleOperation.Provision)
        _oracleOnboardingState.value = OracleOnboardingState.AuthLaunched
        // Read the latest dev mode setting from SharedPreferences
        if (::prefs.isInitialized) {
            _isDevMode.value = prefs.getBoolean("is_dev_mode", false)
        }
        _events.value = emptyList()
        _sshDebugInfo.value = null
        clientConfig = null
        wireGuardClientPublicKey = null
        wireGuardServerPublicKey = null
        wireGuardServerPeerPublicKey = null
        _state.value = ProvisioningState.Running
        persistState()
        launchProvisioningJob(context.applicationContext) {
            runProvisioning(context)
        }
    }

    fun retry(context: Context) {
        if (!::prefs.isInitialized) initPrefs(context)
        val operation = when {
            failedOracleOperation.type != OracleOperationType.NONE -> failedOracleOperation
            pendingOracleOperation.type != OracleOperationType.NONE -> pendingOracleOperation
            else -> PendingOracleOperation.Provision
        }
        if (operation.type == OracleOperationType.DESTROY_EXIT) {
            destroyNode(context, operation.exitId)
            return
        }
        if (operation.type != OracleOperationType.PROVISION) {
            _state.value = ProvisioningState.Failure(
                failedPhase = Phase.DONE,
                lastSuccessPhase = null,
                errorMessage = "No Oracle operation is available to retry.",
            )
            persistState()
            return
        }
        val repository = capacityRetryRepository ?: CapacityRetryRepository(prefs).also { capacityRetryRepository = it }
        val now = Instant.now()
        var session = repository.activeSession()?.let { active ->
            repository.markTimedOutIfNeeded(active.sessionId) ?: active
        }
        if (session?.state == CapacityRetryState.TIMED_OUT) {
            _capacityRetrySessions.value = repository.sessions()
            _state.value = ProvisioningState.Failure(
                failedPhase = Phase.VM_LAUNCH,
                lastSuccessPhase = Phase.NETWORK,
                errorMessage = "The fixed 24-hour capacity retry window has expired.",
            )
            persistState()
            return
        }
        when (val decision = decideManualCapacityRetry(session, pendingCapacityRetryEligible, now)) {
            ManualCapacityRetryDecision.StartSession -> {
                session = startCapacityRetryFromStoredCredentials(context.applicationContext)
                if (session == null) {
                    showCapacityRetryAuthenticationRequired()
                    return
                }
                retryExistingCapacitySession(context, session)
            }
            is ManualCapacityRetryDecision.Attempt -> retryExistingCapacitySession(context, session!!)
            is ManualCapacityRetryDecision.Cooldown -> {
                val message = "Next VM launch is available in ${formatCooldown(decision.remaining)}."
                emit(Phase.VM_LAUNCH, Status.WARNING, message)
                _capacityRetrySessions.value = repository.sessions()
                _state.value = ProvisioningState.Failure(
                    failedPhase = Phase.VM_LAUNCH,
                    lastSuccessPhase = Phase.NETWORK,
                    errorMessage = if (session?.lastResult == "RATE_LIMITED") {
                        "Oracle is temporarily rate limiting VM requests. $message"
                    } else {
                        message
                    },
                )
                persistState()
            }
            ManualCapacityRetryDecision.AuthenticationRequired -> showCapacityRetryAuthenticationRequired()
            ManualCapacityRetryDecision.NotAvailable -> {
                _state.value = ProvisioningState.Failure(
                    failedPhase = Phase.AUTH,
                    lastSuccessPhase = null,
                    errorMessage = "The authenticated capacity retry session is unavailable. Start a new Oracle setup when you are ready to authenticate again.",
                )
                persistState()
            }
        }
    }

    private fun retryExistingCapacitySession(context: Context, session: CapacityRetrySession) {
        val repository = capacityRetryRepository ?: return
        val refreshed = repository.markTimedOutIfNeeded(session.sessionId) ?: return
        if (refreshed.state == CapacityRetryState.TIMED_OUT) {
            _capacityRetrySessions.value = repository.sessions()
            _state.value = ProvisioningState.Failure(
                failedPhase = Phase.VM_LAUNCH,
                lastSuccessPhase = Phase.NETWORK,
                errorMessage = "The fixed 24-hour capacity retry window has expired.",
            )
            persistState()
            return
        }
        val remaining = refreshed.cooldownRemaining(Instant.now())
        if (!remaining.isZero) {
            emit(Phase.VM_LAUNCH, Status.WARNING, "Next VM launch is available in ${formatCooldown(remaining)}.")
            _capacityRetrySessions.value = repository.sessions()
            persistState()
            return
        }
        if (!acquireProvisioningLease(ProvisioningLeaseOperation.FOREGROUND_MANUAL_RETRY)) return
        _currentPhase.value = Phase.VM_LAUNCH
        _state.value = ProvisioningState.Running
        emit(
            Phase.VM_LAUNCH,
            Status.RUNNING,
            "Retrying the saved ${refreshed.pendingMemoryGb} GB A1 configuration with the existing authenticated session.",
        )
        persistState()
        launchProvisioningJob(context.applicationContext) {
            attemptSingleForegroundCapacityRetry(context.applicationContext, refreshed.sessionId)
        }
    }

    internal suspend fun attemptSingleForegroundCapacityRetry(context: Context, sessionId: String? = null) {
        val repository = capacityRetryRepository ?: return
        val current = sessionId?.let(repository::session) ?: repository.activeSession() ?: return
        val started = repository.beginWorkerCycle(current.sessionId) ?: return
        if (started.state != CapacityRetryState.ACQUIRING) {
            val remaining = started.cooldownRemaining(Instant.now())
            emit(Phase.VM_LAUNCH, Status.WARNING, "Next VM launch is available in ${formatCooldown(remaining)}.")
            _capacityRetrySessions.value = repository.sessions()
            _state.value = ProvisioningState.Failure(Phase.VM_LAUNCH, Phase.NETWORK, "The launch cooldown is still active.")
            persistState()
            return
        }

        val vault = RetryCredentialVault(secretStore)
        // Load durable API-key credentials (not the legacy security token)
        val credentials = vault.loadApiKeyCredentials(started.sessionId)
        val privateKey = credentials?.let { runCatching { vault.loadPrivateKey(it.privateKeyPem) }.getOrNull() }
        if (credentials == null || privateKey == null) {
            repository.updateSession(started.sessionId) { session ->
                if (session.state != CapacityRetryState.ACQUIRING) session else session.copy(
                    state = CapacityRetryState.PAUSED_AUTH_REQUIRED,
                    lastWorkerFinishedAtUtc = Instant.now().toString(),
                    lastResult = "PAUSED_AUTH_REQUIRED",
                    lastSafeErrorCategory = "auth-required",
                    requiresUserAction = true,
                    terminalReason = "The saved Oracle API-key credentials are missing or unreadable.",
                )
            }
            _capacityRetrySessions.value = repository.sessions()
            showCapacityRetryAuthenticationRequired()
            return
        }

        val missingContext = listOfNotNull(
            "userOcid".takeIf { started.userOcid.isNullOrBlank() },
            "tenancyOcid".takeIf { started.tenancyOcid.isNullOrBlank() },
            "fingerprint".takeIf { started.fingerprint.isNullOrBlank() },
            "selectedRegion".takeIf { started.selectedRegion.isNullOrBlank() },
            "compartmentOcid".takeIf { started.compartmentOcid.isNullOrBlank() },
            "subnetId".takeIf { started.subnetId.isNullOrBlank() },
            "sshPublicKey".takeIf { started.sshPublicKey.isNullOrBlank() },
        )
        if (missingContext.isNotEmpty()) {
            val failed = repository.updateSession(started.sessionId) { session ->
                if (session.state != CapacityRetryState.ACQUIRING) session else session.copy(
                    state = CapacityRetryState.FAILED_TERMINAL,
                    lastWorkerFinishedAtUtc = Instant.now().toString(),
                    lastResult = "MISSING_LAUNCH_CONTEXT",
                    lastSafeErrorCategory = "invalid-launch-context",
                    requiresUserAction = true,
                    terminalReason = "The saved launch context is incomplete: ${missingContext.joinToString()}.",
                )
            }
            failed?.let { CapacityRetryWorkScheduler(context.applicationContext).cancel(it) }
            vault.clearCredentials(started.sessionId)
            vault.clearApiKeyCredentials(started.sessionId)
            _capacityRetrySessions.value = repository.sessions()
            _state.value = ProvisioningState.Failure(Phase.VM_LAUNCH, Phase.NETWORK, "The saved VM launch context is incomplete.")
            persistState()
            return
        }

        val retryToken = if (started.pendingMemoryGb == 4) {
            started.compactRetryToken
        } else {
            started.preferredRetryToken
        }
        val diagnosticLog = CapacityRetryDiagnosticLog.fromContext(context.applicationContext)
        val result = try {
            OciBackgroundLauncher().launchA1Instance(
                credentials = BackgroundLaunchCredentials(
                    tenancyOcid = credentials.tenancyOcid,
                    userOcid = credentials.userOcid,
                    fingerprint = credentials.fingerprint,
                    privateKey = privateKey,
                    region = credentials.region,
                    publicKeySha256 = credentials.publicKeySha256,
                ),
                params = BackgroundLaunchParams(
                    compartmentOcid = started.compartmentOcid!!,
                    region = started.selectedRegion!!,
                    subnetId = started.subnetId!!,
                    sshPublicKey = started.sshPublicKey!!,
                ),
                pendingMemoryGb = started.pendingMemoryGb,
                retryToken = retryToken,
                sessionId = started.sessionId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val diagnostics = com.zerovpn.app.chat.retry.LaunchFailureDiagnostics.capture(
                error = error,
                failingOperation = "unknown-launch-operation",
                retryToken = retryToken,
                sessionId = started.sessionId,
            )
            diagnosticLog.append(started.sessionId, "Launch result: ${diagnostics.summaryLine()}")
            diagnosticLog.appendFailure(started.sessionId, diagnostics)
            val ambiguous = repository.updateSession(started.sessionId) { session ->
                if (session.state != CapacityRetryState.ACQUIRING) session else session.copy(
                    state = CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED,
                    lastWorkerFinishedAtUtc = Instant.now().toString(),
                    lastResult = "AMBIGUOUS_EXCEPTION_RECONCILIATION_REQUIRED",
                    lastSafeErrorCategory = diagnostics.safeCategory(),
                    requiresUserAction = true,
                    terminalReason = CapacityRetryRepository.AMBIGUOUS_RECONCILIATION_MESSAGE,
                    nextEligibleAttemptAtUtc = null,
                    lastHttpStatus = null,
                    lastOciErrorCode = null,
                    lastRedactedRequestId = null,
                )
            } ?: return
            CapacityRetryWorkScheduler(context.applicationContext).cancel(ambiguous)
            _capacityRetrySessions.value = repository.sessions()
            refreshCapacityRetryDiagnostics()
            _state.value = ProvisioningState.Failure(
                Phase.VM_LAUNCH,
                Phase.NETWORK,
                CapacityRetryRepository.AMBIGUOUS_RECONCILIATION_MESSAGE,
            )
            persistState()
            return
        }
        result.failureDiagnosticsOrNull()?.let { diagnostics ->
            diagnosticLog.append(started.sessionId, "Launch result: ${diagnostics.summaryLine()}")
            diagnosticLog.appendFailure(started.sessionId, diagnostics)
        }
        val updated = repository.finishLaunchAttempt(started.sessionId, result) ?: return
        _capacityRetrySessions.value = repository.sessions()
        refreshCapacityRetryDiagnostics()
        when (updated.state) {
            CapacityRetryState.INSTANCE_ACQUIRED -> {
                CapacityRetryWorkScheduler(context.applicationContext).cancel(updated)
                emit(Phase.VM_LAUNCH, Status.SUCCESS, "Oracle VM acquired. Open ZeroVPN to continue Private Chat setup.")
                _state.value = ProvisioningState.Idle
            }
            CapacityRetryState.ACTIVE -> {
                val message = if (updated.lastResult == "RATE_LIMITED") {
                    "Oracle is temporarily rate limiting VM requests."
                } else {
                    "Oracle has no A1 host capacity right now. The next slot will try ${updated.pendingMemoryGb} GB."
                }
                emit(Phase.VM_LAUNCH, Status.WARNING, message)
                _state.value = ProvisioningState.Failure(Phase.VM_LAUNCH, Phase.NETWORK, message)
            }
            CapacityRetryState.FAILED_TERMINAL -> {
                CapacityRetryWorkScheduler(context.applicationContext).cancel(updated)
                vault.clearCredentials(updated.sessionId)
                vault.clearApiKeyCredentials(updated.sessionId)
                _state.value = ProvisioningState.Failure(
                    Phase.VM_LAUNCH,
                    Phase.NETWORK,
                    updated.terminalReason ?: "Oracle rejected the VM launch request.",
                )
            }
            CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED -> {
                CapacityRetryWorkScheduler(context.applicationContext).cancel(updated)
                _state.value = ProvisioningState.Failure(
                    Phase.VM_LAUNCH,
                    Phase.NETWORK,
                    updated.terminalReason ?: CapacityRetryRepository.AMBIGUOUS_RECONCILIATION_MESSAGE,
                )
            }
            else -> _state.value = ProvisioningState.Failure(
                Phase.VM_LAUNCH,
                Phase.NETWORK,
                updated.terminalReason ?: "The capacity retry could not continue.",
            )
        }
        persistState()
    }

    private fun showCapacityRetryAuthenticationRequired() {
        emit(
            Phase.AUTH,
            Status.ERROR,
            "The saved Oracle signing credentials are missing or invalid. Authentication is required before retry can continue.",
        )
        _state.value = ProvisioningState.Failure(
            failedPhase = Phase.AUTH,
            lastSuccessPhase = null,
            errorMessage = "Authentication is required to continue this capacity retry session.",
        )
        persistState()
    }

    private fun formatCooldown(duration: Duration): String {
        val seconds = duration.seconds.coerceAtLeast(0L)
        return "${seconds / 60}m ${seconds % 60}s"
    }

    fun continueAfterUkWarning(context: Context) {
        if (!::prefs.isInitialized) initPrefs(context)
        if (!acquireProvisioningLease(ProvisioningLeaseOperation.FOREGROUND_AUTH_CONTINUATION)) return
        _state.value = ProvisioningState.Running
        persistState()
        launchProvisioningJob(context.applicationContext) {
            continueProvisioningAfterWarning(context)
        }
    }

    private fun acquireProvisioningLease(operation: ProvisioningLeaseOperation): Boolean {
        val acquired = provisioningLease?.tryAcquire(operation) == true
        if (!acquired) {
            emit(
                Phase.VM_LAUNCH,
                Status.WARNING,
                "Another provisioning or capacity operation is active. Wait for it to finish before retrying.",
            )
        }
        return acquired
    }

    private fun launchProvisioningJob(context: Context, block: suspend () -> Unit) {
        val job = viewModelScope.launch {
            try {
                block()
            } finally {
                provisioningLease?.release()
            }
        }
        provisioningJob = job
        job.invokeOnCompletion { cause ->
            if (cause == null) {
                viewModelScope.launch {
                    maybeStartAutomaticCapacityRetry(context.applicationContext)
                }
            }
        }
    }

    private fun cancelCapacityRetryAfterForegroundSuccess(context: Context, provisioningId: String) {
        val repository = capacityRetryRepository ?: return
        val session = repository.sessions().firstOrNull {
            it.candidateId == provisioningId && !it.isTerminal()
        } ?: return
        val succeeded = repository.updateSession(session.sessionId) {
            it.copy(
                state = CapacityRetryState.SUCCEEDED,
                lastResult = "FOREGROUND_PROVISIONING_SUCCEEDED",
                requiresUserAction = false,
                terminalReason = null,
            )
        } ?: session
        CapacityRetryWorkScheduler(context.applicationContext).cancel(succeeded)
        RetryCredentialVault(secretStore).clearCredentials(session.sessionId)
        RetryCredentialVault(secretStore).clearApiKeyCredentials(session.sessionId)
        _capacityRetrySessions.value = repository.sessions()
    }

    private fun clearPendingRetryCredentials(provisioningId: String? = pendingProvisionExitId) {
        if (!provisioningId.isNullOrBlank() && ::secretStore.isInitialized) {
            RetryCredentialVault(secretStore).clearProvisioningCredentials(provisioningId)
            RetryCredentialVault(secretStore).clearProvisioningApiKeyCredentials(provisioningId)
        }
        if (provisioningId == pendingProvisionExitId) {
            retryLaunchSubnetId = null
            retryLaunchSshPublicKey = null
        }
    }

    private fun clearPendingCapacityRetryMetadata() {
        pendingRetryLastLaunchFinishedAtUtc = null
        pendingRetryNextEligibleAtUtc = null
        pendingRetryMemoryGb = 6
        pendingRetryLastResult = null
        pendingRetryHttpStatus = null
    }

    fun retryPrivateChat(context: Context, exitId: String) {
        if (privateChatJob?.isActive == true) return
        val exit = _configuredExits.value.firstOrNull { it.id == exitId }
        if (exit?.provider != ExitProvider.OCI) return
        _events.value = emptyList()
        updateExit(exitId) {
            it.copy(
                lifecycleState = ExitLifecycleState.READY,
                privateChat = (it.privateChat ?: PrivateChatNodeState.installing()).copy(
                    status = PrivateChatInstallStatus.INSTALLING,
                    lastError = null,
                    lastUpdatedAt = System.currentTimeMillis(),
                ),
            )
        }
        (_state.value as? ProvisioningState.Success)?.let { success ->
            _state.value = success.copy(
                privateChatStatus = PrivateChatInstallStatus.INSTALLING,
                privateChatError = null,
            )
        }
        privateChatJob = viewModelScope.launch {
            val finalState = installPrivateChatForExit(context, exitId)
            (_state.value as? ProvisioningState.Success)?.let { success ->
                _state.value = success.copy(
                    privateChatStatus = finalState.status,
                    privateChatError = finalState.lastError,
                )
            }
        }
    }

    fun refreshPrivateChatHealth(context: Context, exitId: String) {
        if (privateChatJob?.isActive == true) return
        val exit = _configuredExits.value.firstOrNull { it.id == exitId } ?: return
        val sshPrivateKey = exit.sshPrivateKeySecretKey
            ?.let { secretStore.getSecret(it) }
            ?.takeIf { it.isNotBlank() }
        if (sshPrivateKey == null) {
            updateExit(exitId) { current ->
                current.copy(
                    privateChat = current.privateChat?.copy(
                        status = PrivateChatInstallStatus.FAILED,
                        lastError = "The saved Oracle SSH key is missing; health could not be refreshed.",
                        lastUpdatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            return
        }
        privateChatJob = viewModelScope.launch {
            try {
                val manifest = PrivateChatNodeProvisioner(context).refreshHealth(exit, sshPrivateKey)
                val credentialsKey = exit.privateChat?.ownerCredentialsSecretKey
                    ?: SecureSecretStore.privateChatOwnerCredentials(exitId)
                val refreshed = manifest.toNodeState(credentialsKey).copy(
                    ownerLoginVerifiedAt = exit.privateChat?.ownerLoginVerifiedAt,
                )
                updateExit(exitId) {
                    it.copy(
                        privateChat = if (manifest.healthStatus == "unhealthy") {
                            refreshed.copy(
                                status = PrivateChatInstallStatus.FAILED,
                                lastError = "Private Chat health checks report an unhealthy node.",
                            )
                        } else {
                            refreshed
                        },
                    )
                }
            } catch (error: PrivateChatProvisioningException) {
                updateExit(exitId) { current ->
                    current.copy(
                        privateChat = current.privateChat?.copy(
                            status = PrivateChatInstallStatus.FAILED,
                            currentStage = error.failedStage,
                            lastError = error.message?.take(600) ?: "Private Chat health refresh failed.",
                            lastUpdatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            } catch (_: Exception) {
                val message = "Private Chat health refresh failed without changing WireGuard."
                updateExit(exitId) { current ->
                    current.copy(
                        privateChat = current.privateChat?.copy(
                            status = PrivateChatInstallStatus.FAILED,
                            currentStage = "PRIVATE_CHAT_HEALTH",
                            lastError = message,
                            lastUpdatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    fun verifyPrivateChatOwnerLogin(exitId: String) {
        if (privateChatJob?.isActive == true) return
        val exit = _configuredExits.value.firstOrNull { it.id == exitId } ?: return
        val chat = exit.privateChat ?: return
        val credentialsKey = chat.ownerCredentialsSecretKey
            ?: SecureSecretStore.privateChatOwnerCredentials(exitId)
        val credentials = secretStore.getSecret(credentialsKey)
        if (credentials.isNullOrBlank()) {
            updateExit(exitId) { current ->
                current.copy(
                    privateChat = current.privateChat?.copy(
                        lastError = "The saved owner Matrix credentials are missing.",
                        lastUpdatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            return
        }
        privateChatJob = viewModelScope.launch {
            when (val result = PrivateChatOwnerVerifier().verify(chat, credentials)) {
                PrivateChatOwnerVerificationResult.Verified -> updateExit(exitId) { current ->
                    current.copy(
                        privateChat = current.privateChat?.copy(
                            ownerLoginVerifiedAt = System.currentTimeMillis(),
                            lastError = null,
                            lastUpdatedAt = System.currentTimeMillis(),
                        ),
                    )
                }

                is PrivateChatOwnerVerificationResult.Failed -> updateExit(exitId) { current ->
                    current.copy(
                        privateChat = current.privateChat?.copy(
                            lastError = result.message,
                            lastUpdatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    fun removePrivateChat(context: Context, exitId: String) {
        if (privateChatJob?.isActive == true) return
        val exit = _configuredExits.value.firstOrNull { it.id == exitId } ?: return
        val sshPrivateKey = exit.sshPrivateKeySecretKey
            ?.let { secretStore.getSecret(it) }
            ?.takeIf { it.isNotBlank() }
        if (sshPrivateKey == null) {
            updateExit(exitId) { current ->
                current.copy(
                    privateChat = current.privateChat?.copy(
                        status = PrivateChatInstallStatus.FAILED,
                        lastError = "The saved Oracle SSH key is missing; chat removal was not started.",
                        lastUpdatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            return
        }
        updateExit(exitId) { current ->
            current.copy(
                lifecycleState = ExitLifecycleState.READY,
                privateChat = current.privateChat?.copy(
                    status = PrivateChatInstallStatus.REMOVING,
                    lastError = null,
                    lastUpdatedAt = System.currentTimeMillis(),
                ),
            )
        }
        (_state.value as? ProvisioningState.Success)?.let { success ->
            _state.value = success.copy(
                privateChatStatus = PrivateChatInstallStatus.REMOVING,
                privateChatError = null,
            )
        }
        privateChatJob = viewModelScope.launch {
            try {
                PrivateChatNodeProvisioner(context).remove(
                    exit = exit,
                    sshPrivateKey = sshPrivateKey,
                    onEvent = { remoteEvent -> recordPrivateChatEvent(exitId, remoteEvent) },
                )
                exit.privateChat?.ownerCredentialsSecretKey?.let { secretStore.removeSecret(it) }
                secretStore.removeSecret(SecureSecretStore.privateChatOwnerCredentials(exitId))
                updateExit(exitId) { current ->
                    current.copy(
                        lifecycleState = ExitLifecycleState.READY,
                        lastError = null,
                        privateChat = null,
                    )
                }
                (_state.value as? ProvisioningState.Success)?.let { success ->
                    _state.value = success.copy(privateChatStatus = null, privateChatError = null)
                }
            } catch (error: PrivateChatProvisioningException) {
                updateExit(exitId) { current ->
                    current.copy(
                        lifecycleState = ExitLifecycleState.READY,
                        privateChat = current.privateChat?.copy(
                            status = PrivateChatInstallStatus.FAILED,
                            currentStage = error.failedStage,
                            lastError = error.message?.take(600) ?: "Private Chat removal failed; WireGuard was retained.",
                            lastUpdatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                (_state.value as? ProvisioningState.Success)?.let { success ->
                    _state.value = success.copy(
                        privateChatStatus = PrivateChatInstallStatus.FAILED,
                        privateChatError = error.message?.take(600),
                    )
                }
            } catch (_: Exception) {
                val message = "Private Chat removal could not finish local cleanup; WireGuard was retained."
                updateExit(exitId) { current ->
                    current.copy(
                        lifecycleState = ExitLifecycleState.READY,
                        privateChat = current.privateChat?.copy(
                            status = PrivateChatInstallStatus.FAILED,
                            currentStage = "PRIVATE_CHAT_COMPLETE",
                            lastError = message,
                            lastUpdatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                (_state.value as? ProvisioningState.Success)?.let { success ->
                    _state.value = success.copy(
                        privateChatStatus = PrivateChatInstallStatus.FAILED,
                        privateChatError = message,
                    )
                }
            }
        }
    }

    fun destroyNode(context: Context, exitId: String? = _selectedExitId.value) {
        if (_state.value is ProvisioningState.Running || _state.value is ProvisioningState.Destroying) return
        val targetExit = exitId?.let { id -> _configuredExits.value.firstOrNull { it.id == id } }
        val destroyOperation = targetExit?.toDestroyOperation() ?: PendingOracleOperation(
            type = OracleOperationType.DESTROY_EXIT,
            exitId = exitId,
        )
        setPendingOracleOperation(destroyOperation)
        if (targetExit == null) {
            val message = "Selected Oracle exit was not found. Destroy was not started."
            setFailedOracleOperation(destroyOperation, message)
            _state.value = ProvisioningState.Failure(
                failedPhase = Phase.DONE,
                lastSuccessPhase = null,
                errorMessage = message,
            )
            persistState()
            return
        }
        _state.value = ProvisioningState.Destroying
        updateExit(targetExit.id) { it.copy(lifecycleState = ExitLifecycleState.DESTROYING, lastError = null) }
        viewModelScope.launch {
            val currentRids = targetExit.ociResourceIds?.toProvisionerResourceIds()
            val currentRegion = targetExit.region
            val currentApiKeyUserOcid = targetExit.apiKeyUserOcid
            val currentApiKeyFingerprint = targetExit.apiKeyFingerprint

            if (currentRids == null) {
                val message = "Local resource IDs are missing. Delete the node resources manually in the Oracle Console."
                setFailedOracleOperation(destroyOperation, message)
                targetExit?.let {
                    updateExit(it.id) { exit ->
                        exit.copy(
                            lifecycleState = ExitLifecycleState.FAILED,
                            lastError = message,
                        )
                    }
                }
                _state.value = ProvisioningState.Failure(
                    failedPhase = Phase.DONE,
                    lastSuccessPhase = null,
                    errorMessage = message,
                )
                persistState()
                return@launch
            }
            val prov = provisioner ?: OciProvisioner(context, currentRegion, _isDevMode.value).also { provisioner = it }
            val eventJob = viewModelScope.launch {
                prov.events.collect { event ->
                    _events.value = _events.value + classifyEvent(event)
                    if (event.phase != Phase.DONE) {
                        _currentPhase.value = event.phase
                    }
                }
            }

            try {
                emit(
                    Phase.DONE,
                    Status.RUNNING,
                    "Phase region trace: cleanupRegion=$currentRegion storedExitRegion=${targetExit?.region ?: "none"}",
                )
                val currentAuth = authResult ?: run {
                    _currentPhase.value = Phase.AUTH
                    emit(Phase.AUTH, Status.RUNNING, "Sign in to Oracle to destroy this exit.")
                    _oracleOnboardingState.value = OracleOnboardingState.AuthLaunched
                    persistState()
                    prov.authenticate().also { authResult = it }
                }
                refreshOracleOperationDiagnostics()

                _currentPhase.value = Phase.DONE
                emit(Phase.DONE, Status.RUNNING, "Destroying node resources...")
                prov.destroy(
                    rids = currentRids,
                    auth = currentAuth,
                    homeRegion = currentRegion,
                    apiKeyUserOcid = currentApiKeyUserOcid,
                    apiKeyFingerprint = currentApiKeyFingerprint,
                )
                emit(Phase.DONE, Status.SUCCESS, "Node destroyed. Resources released.")
                _state.value = ProvisioningState.Destroyed
                _events.value = emptyList()
                _currentPhase.value = null
                _publicIp.value = null
                targetExit?.let { removed ->
                    cleanupOwnerExitLocalState(removed)
                    _configuredExits.value = _configuredExits.value.filterNot { it.id == removed.id }
                    if (_selectedExitId.value == removed.id) {
                        _selectedExitId.value = _configuredExits.value.firstOrNull()?.id
                    }
                }
                _sshDebugInfo.value = null
                resourceIds = null
                clientConfig = null
                wireGuardClientPublicKey = null
                wireGuardServerPublicKey = null
                wireGuardServerPeerPublicKey = null
                authResult = null
                clearOracleOperationState()
                apiKeyUserOcid = null
                apiKeyTenancyOcid = null
                apiKeyFingerprint = null
                if (::prefs.isInitialized) {
                    persistState()
                }
            } catch (e: Exception) {
                val destroyError = if (_currentPhase.value == Phase.AUTH) {
                    "Destroy cancelled. Oracle sign-in was not completed."
                } else {
                    e.message ?: "Destroy failed."
                }
                setFailedOracleOperation(destroyOperation, destroyError)
                targetExit?.let {
                    updateExit(it.id) { exit ->
                        exit.copy(
                            lifecycleState = ExitLifecycleState.FAILED,
                            lastError = destroyError,
                        )
                    }
                }
                _state.value = ProvisioningState.Failure(
                    failedPhase = _currentPhase.value ?: Phase.DONE,
                    lastSuccessPhase = null,
                    errorMessage = destroyError,
                )
                if (_currentPhase.value == Phase.AUTH) {
                    _oracleOnboardingState.value = OracleOnboardingState.AuthFailed
                }
                persistState()
            } finally {
                eventJob?.cancel()
            }
        }
    }

    fun cleanup(context: Context) {
        if (!::prefs.isInitialized) initPrefs(context)
        if (!acquireProvisioningLease(ProvisioningLeaseOperation.CLEANUP)) return
        stopActiveCapacityRetry(context)
        clearPendingRetryCredentials()
        pendingCapacityRetryEligible = false
        clearPendingCapacityRetryMetadata()
        provisioningJob = viewModelScope.launch {
            try {
            val currentRids = resourceIds
            val currentAuth = authResult
            val currentRegion = homeRegion ?: _selectedOracleRegion.value

            if (currentRids != null && currentAuth != null && currentRegion != null) {
                emit(
                    Phase.DONE,
                    Status.RUNNING,
                    "Phase region trace: cleanupRegion=$currentRegion storedExitRegion=${_configuredExits.value.firstOrNull { it.id == _selectedExitId.value }?.region ?: "none"}",
                )
                emit(Phase.DONE, Status.RUNNING, "Cleaning up partial resources...")
                try {
                    provisioner?.destroy(
                        rids = currentRids,
                        auth = currentAuth,
                        homeRegion = currentRegion,
                        apiKeyUserOcid = apiKeyUserOcid,
                        apiKeyFingerprint = apiKeyFingerprint,
                    )
                    emit(Phase.DONE, Status.SUCCESS, "Cleanup complete.")
                } catch (e: Exception) {
                    emit(Phase.DONE, Status.WARNING, "Cleanup partial: ${e.message}")
                }
            } else if (currentRids != null && currentAuth != null) {
                emit(Phase.DONE, Status.WARNING, "Cleanup skipped: Oracle region is missing.")
            }
            _state.value = ProvisioningState.Idle
            _events.value = emptyList()
            _currentPhase.value = null
            _publicIp.value = null
            _sshDebugInfo.value = null
            resourceIds = null
            clientConfig = null
            wireGuardClientPublicKey = null
            wireGuardServerPublicKey = null
            wireGuardServerPeerPublicKey = null
            pendingProvisionExitId = null
            clearOracleOperationState()
            restoreStateFromSelectedExitOrIdle()
            persistState()
            } finally {
                provisioningLease?.release()
            }
        }
    }

    private suspend fun runProvisioning(context: Context) {
        var eventJob: Job? = null
        try {
            if (pendingOracleOperation.type != OracleOperationType.PROVISION) {
                val message = "Provisioning was not the pending Oracle operation."
                setFailedOracleOperation(
                    PendingOracleOperation(OracleOperationType.PROVISION),
                    message,
                )
                _state.value = ProvisioningState.Failure(
                    failedPhase = Phase.AUTH,
                    lastSuccessPhase = null,
                    errorMessage = message,
                )
                persistState()
                return
            }
            // Reset capacity-fallback diagnostics for a fresh provisioning run
            if (_privateChatRequested.value) {
                chatLastAttemptedConfig = "1 OCPU / 6 GB"
                chatLastLaunchResult = "NOT TESTED"
                chatInstanceCreated = "NOT TESTED"
                chatCleanupRequired = "NOT TESTED"
                refreshOracleOperationDiagnostics()
            }
            val uiSelectedRegion = _selectedOracleRegion.value
            val persistedRegion = homeRegion
            val authBootstrapRegion = uiSelectedRegion ?: persistedRegion ?: AUTH_BOOTSTRAP_REGION
            provisioner = OciProvisioner(context, authBootstrapRegion, _isDevMode.value)

            // Collect events from provisioner
            val prov = provisioner!!
            eventJob = viewModelScope.launch {
                prov.events.collect { event ->
                    appendProvisionerEvent(event)
                }
            }

            // Phase 1: Auth
            _currentPhase.value = Phase.AUTH
            emit(Phase.AUTH, Status.RUNNING, "Developer mode: ${_isDevMode.value}")
            emit(Phase.AUTH, Status.RUNNING, "Developer mode affects diagnostics only: true")
            emit(
                Phase.AUTH,
                Status.RUNNING,
                "Region trace: uiSelectedRegionId=${uiSelectedRegion ?: "none"} persistedRegionId=${persistedRegion ?: "none"} " +
                    "manualRegionId=${uiSelectedRegion ?: "none"} authBootstrapRegionId=$authBootstrapRegion",
            )
            authResult = prov.authenticate()
            refreshOracleOperationDiagnostics()
            _oracleOnboardingState.value = OracleOnboardingState.ReadyToProvision
            persistState()

            // Phase 2: Preflight
            _currentPhase.value = Phase.API_KEY
            val preferredRegion = uiSelectedRegion ?: persistedRegion
            val preferredSource = when {
                uiSelectedRegion != null -> "user-selected manual region"
                persistedRegion != null -> "persisted"
                else -> "none"
            }
            emit(
                Phase.API_KEY,
                Status.RUNNING,
                "Region trace: uiSelectedRegionId=${uiSelectedRegion ?: "none"} persistedRegionId=${persistedRegion ?: "none"} " +
                    "tokenRegionId=${authResult?.tokenRegion ?: "none"} manualRegionId=${uiSelectedRegion ?: "none"} " +
                    "discoveryCandidateRegionId=${preferredRegion ?: authResult?.selectedRegion ?: "none"}",
            )
            preflightResult = prov.preflight(authResult!!, preferredRegion, preferredSource)

            if (!preflightResult!!.success) {
                eventJob?.cancel()
                setFailedOracleOperation(PendingOracleOperation.Provision, preflightResult!!.error)
                _state.value = ProvisioningState.Failure(
                    failedPhase = Phase.API_KEY,
                    lastSuccessPhase = Phase.AUTH,
                    errorMessage = preflightResult!!.error,
                )
                persistState()
                return
            }

            // UK region warning
            if (preflightResult!!.isUkRegion) {
                eventJob?.cancel()
                homeRegion = preflightResult!!.homeRegion
                _selectedOracleRegion.value = preflightResult!!.homeRegion
                _state.value = ProvisioningState.UkWarning(preflightResult!!.homeRegion)
                persistState()
                return
            }

            // Continue to provisioning
            doProvision(context, prov)

        } catch (e: Exception) {
            val (failedPhase, errorMessage) = classifyProvisioningFailure(
                error = e,
                events = _events.value,
                currentPhase = _currentPhase.value,
            )
            // Update capacity-fallback diagnostics tracking
            val launchFailure = OciProvisioner.classifyLaunchFailure(e)
            if (launchFailure != null) {
                chatLastLaunchResult = when (launchFailure) {
                    is VmLaunchFailure.OutOfHostCapacity -> "FAIL"
                    is VmLaunchFailure.RateLimited -> "RATE_LIMITED"
                    is VmLaunchFailure.Other -> "FAIL"
                }
                chatInstanceCreated = "No"
                chatCleanupRequired = if (resourceIds?.instanceId != null) "WARNING" else "No"
            } else if (failedPhase == Phase.AUTH || failedPhase == Phase.API_KEY) {
                chatLastLaunchResult = "NOT TESTED"
                chatInstanceCreated = "No"
                chatCleanupRequired = "No"
            }
            refreshOracleOperationDiagnostics()
            setFailedOracleOperation(PendingOracleOperation.Provision, errorMessage)
            val lastSuccess = when (failedPhase) {
                Phase.AUTH -> null
                Phase.API_KEY -> Phase.AUTH
                Phase.NETWORK -> Phase.API_KEY
                Phase.VM_LAUNCH -> Phase.NETWORK
                Phase.WAIT_SSH -> Phase.VM_LAUNCH
                Phase.WIREGUARD -> Phase.WAIT_SSH
                else -> if (failedPhase.isPrivateChat) Phase.WIREGUARD else null
            }
            _state.value = ProvisioningState.Failure(
                failedPhase = failedPhase,
                lastSuccessPhase = lastSuccess,
                errorMessage = errorMessage,
            )
            if (failedPhase == Phase.AUTH) {
                _oracleOnboardingState.value = OracleOnboardingState.AuthFailed
            }
            persistState()
        } finally {
            eventJob?.cancel()
        }
    }

    private suspend fun continueProvisioningAfterWarning(context: Context) {
        var eventJob: Job? = null
        try {
            setPendingOracleOperation(PendingOracleOperation.Provision)
            val warningRegion = preflightResult?.homeRegion ?: homeRegion ?: _selectedOracleRegion.value
                ?: throw IllegalStateException("Oracle region is not available.")
            val prov = provisioner ?: OciProvisioner(context, warningRegion, _isDevMode.value).also { provisioner = it }
            val auth = authResult ?: return

            eventJob = viewModelScope.launch {
                prov.events.collect { event ->
                    appendProvisionerEvent(event)
                }
            }

            doProvision(context, prov)
        } catch (e: Exception) {
            val (failedPhase, errorMessage) = classifyProvisioningFailure(
                error = e,
                events = _events.value,
                currentPhase = _currentPhase.value,
            )
            val launchFailure = OciProvisioner.classifyLaunchFailure(e)
            if (launchFailure != null) {
                chatLastLaunchResult = "FAIL"
                chatInstanceCreated = "No"
                chatCleanupRequired = if (resourceIds?.instanceId != null) "WARNING" else "No"
            }
            refreshOracleOperationDiagnostics()
            setFailedOracleOperation(PendingOracleOperation.Provision, errorMessage)
            _state.value = ProvisioningState.Failure(
                failedPhase = failedPhase,
                lastSuccessPhase = null,
                errorMessage = errorMessage,
            )
            persistState()
        } finally {
            eventJob?.cancel()
        }
    }

    private suspend fun doProvision(context: Context, prov: OciProvisioner) {
        val privateChatRequested = _privateChatRequested.value
        val provisioningId = pendingProvisionExitId ?: newExitId().also {
            pendingProvisionExitId = it
        }
        try {
            val auth = authResult!!
            val preflight = preflightResult!!
            homeRegion = preflight.homeRegion
            _selectedOracleRegion.value = preflight.homeRegion
            apiKeyUserOcid = auth.userOcid
            apiKeyTenancyOcid = auth.tenancyOcid
            apiKeyTokenRegion = auth.tokenRegion
            apiKeyTokenRegionSource = auth.tokenRegionSource
            apiKeyFingerprint = auth.fingerprint
            persistState()

            val (rids, result) = prov.provision(
                auth = auth,
                preflight = preflight,
                privateChatRequested = privateChatRequested,
                onApiKeyUploaded = { uploadedFingerprint ->
                    apiKeyFingerprint = uploadedFingerprint
                    if (privateChatRequested) {
                        val publicKeySha256 = com.zerovpn.app.oci.OciCredentialIdentity.sha256Digest(
                            com.zerovpn.app.oci.OciCredentialIdentity.publicKeyFrom(auth.privateKey)
                        )
                        val stored = RetryCredentialVault(secretStore).storeProvisioningApiKeyCredentials(
                            provisioningId = provisioningId,
                            creds = DurableApiKeyCredentials(
                                tenancyOcid = auth.tenancyOcid,
                                userOcid = auth.userOcid,
                                fingerprint = uploadedFingerprint,
                                privateKeyPem = RetryCredentialVault.privateKeyToPkcs8Pem(auth.privateKey),
                                region = preflight.homeRegion,
                                publicKeySha256 = publicKeySha256,
                            ),
                        )
                        check(stored) {
                            "ZeroVPN could not securely retain the durable API-key credentials. " +
                                "Provisioning was stopped before VM launch."
                        }
                    }
                    persistState()
                },
                onLaunchContextReady = { readyResourceIds, sshPublicKey ->
                    resourceIds = readyResourceIds
                    retryLaunchSubnetId = readyResourceIds.subnetId
                    retryLaunchSshPublicKey = sshPublicKey
                    // Persist durable launch context for the retry worker
                    // (AD and image are discovered during provisioning; persist them so the
                    // worker doesn't need to repeat Identity/image lookups every 15 minutes)
                    capacityRetryRepository?.let { repo ->
                        repo.activeSession()?.let { session ->
                            repo.updateSession(session.sessionId) { s ->
                                s.copy(
                                    availabilityDomain = s.availabilityDomain ?: readyResourceIds.availabilityDomain,
                                    ubuntuImageOcid = s.ubuntuImageOcid ?: readyResourceIds.ubuntuImageOcid,
                                    vcnOcid = s.vcnOcid ?: readyResourceIds.vcnId,
                                )
                            }
                            _capacityRetrySessions.value = repo.sessions()
                        }
                    }
                    persistState()
                },
            )
            resourceIds = rids
            clientConfig = result.clientConfig
            wireGuardClientPublicKey = result.clientPublicKey
            wireGuardServerPublicKey = result.serverPublicKey
            wireGuardServerPeerPublicKey = result.serverPeerPublicKey
            _publicIp.value = result.publicIp
            _wireGuardPort.value = result.wireGuardPort
            _sshDebugInfo.value = SshDebugInfo(
                publicIp = result.publicIp,
                username = result.sshUsername,
                privateKeyPresent = true,
            )
            val configuredExit = buildConfiguredExit(
                exitId = provisioningId,
                name = nextExitName(),
                publicIp = result.publicIp,
                wireGuardPort = result.wireGuardPort,
                region = preflight.homeRegion,
                wireGuardConfig = result.clientConfig,
                resourceIds = rids,
                sshUsername = result.sshUsername,
                sshPrivateKey = result.sshPrivateKey,
                createdAt = System.currentTimeMillis(),
                privateChat = if (privateChatRequested) PrivateChatNodeState.installing() else null,
            )
            _configuredExits.value = _configuredExits.value + configuredExit
            _selectedExitId.value = configuredExit.id
            pendingCapacityRetryEligible = false
            clearPendingCapacityRetryMetadata()
            clearPendingRetryCredentials(provisioningId)
            cancelCapacityRetryAfterForegroundSuccess(context.applicationContext, provisioningId)
            pendingProvisionExitId = null
            createInviteSlotsForProvisionedExit(
                ownerExitId = configuredExit.id,
                inviteProfiles = result.inviteProfiles,
            )

            // WireGuard is a committed, independently usable exit before the optional
            // workload starts. From this point, chat errors must never enter OCI cleanup.
            _oracleOnboardingState.value = OracleOnboardingState.NotStarted
            clearOracleOperationState()
            persistState()

            val privateChatState = if (privateChatRequested) {
                _currentPhase.value = Phase.PRIVATE_CHAT_PRECHECK
                emit(
                    Phase.PRIVATE_CHAT_PRECHECK,
                    Status.RUNNING,
                    "Working WireGuard exit saved. Starting the optional Private Chat workload.",
                )
                installPrivateChatForExit(context, configuredExit.id)
            } else {
                null
            }
            _currentPhase.value = Phase.DONE
            _state.value = ProvisioningState.Success(
                publicIp = result.publicIp,
                wireGuardPort = result.wireGuardPort,
                region = preflight.homeRegion,
                isDevMode = _isDevMode.value,
                privateChatStatus = privateChatState?.status,
                privateChatError = privateChatState?.lastError,
            )
            persistState()
        } catch (e: Exception) {
            val (failedPhase, errorMessage) = classifyProvisioningFailure(
                error = e,
                events = _events.value,
                currentPhase = _currentPhase.value,
            )
            val launchFailure = OciProvisioner.classifyLaunchFailure(e)
            val exactCapacityFailure = privateChatRequested && launchFailure is VmLaunchFailure.OutOfHostCapacity
            val rateLimited = privateChatRequested && launchFailure is VmLaunchFailure.RateLimited
            val retryableLaunchFailure = exactCapacityFailure || rateLimited
            pendingCapacityRetryEligible = retryableLaunchFailure
            if (retryableLaunchFailure) {
                val finishedAt = Instant.now()
                val retryAfterSeconds = (launchFailure as? VmLaunchFailure.RateLimited)?.retryAfterSeconds
                pendingRetryLastLaunchFinishedAtUtc = finishedAt.toString()
                pendingRetryNextEligibleAtUtc = nextEligibleLaunchAt(finishedAt, retryAfterSeconds).toString()
                pendingRetryMemoryGb = if (exactCapacityFailure) 4 else 6
                pendingRetryLastResult = if (rateLimited) "RATE_LIMITED" else "OUT_OF_HOST_CAPACITY"
                pendingRetryHttpStatus = if (rateLimited) 429 else 500
            } else {
                clearPendingCapacityRetryMetadata()
                clearPendingRetryCredentials(provisioningId)
            }
            if (launchFailure != null) {
                chatLastLaunchResult = if (rateLimited) "RATE_LIMITED" else "FAIL"
                chatInstanceCreated = "No"
                chatCleanupRequired = if (resourceIds?.instanceId != null) "WARNING" else "No"
            } else if (failedPhase == Phase.AUTH || failedPhase == Phase.API_KEY) {
                chatLastLaunchResult = "NOT TESTED"
                chatInstanceCreated = "No"
                chatCleanupRequired = "No"
            }
            refreshOracleOperationDiagnostics()
            setFailedOracleOperation(PendingOracleOperation.Provision, errorMessage)
            _state.value = ProvisioningState.Failure(
                failedPhase = failedPhase,
                lastSuccessPhase = if (failedPhase == Phase.VM_LAUNCH) Phase.NETWORK else null,
                errorMessage = if (rateLimited) {
                    "Oracle is temporarily rate limiting VM requests."
                } else {
                    errorMessage
                },
            )
            if (failedPhase == Phase.AUTH) {
                _oracleOnboardingState.value = OracleOnboardingState.AuthFailed
            }
            persistState()
        }
    }

    private suspend fun installPrivateChatForExit(context: Context, exitId: String): PrivateChatNodeState {
        val exit = _configuredExits.value.firstOrNull { it.id == exitId }
            ?: return PrivateChatNodeState(
                status = PrivateChatInstallStatus.FAILED,
                currentStage = "PRIVATE_CHAT_PRECHECK",
                lastError = privateChatFailureMessage(
                    "The working VPN exit could not be found for Private Chat installation.",
                ),
            )
        val sshPrivateKey = exit.sshPrivateKeySecretKey
            ?.let { secretStore.getSecret(it) }
            ?.takeIf { it.isNotBlank() }
        if (sshPrivateKey == null) {
            val failed = PrivateChatNodeState(
                status = PrivateChatInstallStatus.FAILED,
                currentStage = "PRIVATE_CHAT_PRECHECK",
                lastError = privateChatFailureMessage(
                    "The saved Oracle SSH key is missing. The working VPN profile was retained.",
                ),
            )
            updateExit(exitId) { it.copy(privateChat = failed) }
            return failed
        }
        updateExit(exitId) {
            it.copy(
                lifecycleState = ExitLifecycleState.READY,
                privateChat = (it.privateChat ?: PrivateChatNodeState.installing()).copy(
                    status = PrivateChatInstallStatus.INSTALLING,
                    lastError = null,
                    lastUpdatedAt = System.currentTimeMillis(),
                ),
            )
        }
        return try {
            val result = PrivateChatNodeProvisioner(context).install(
                exit = exit,
                sshPrivateKey = sshPrivateKey,
                onEvent = { remoteEvent -> recordPrivateChatEvent(exitId, remoteEvent) },
            )
            val credentialSecretKey = SecureSecretStore.privateChatOwnerCredentials(exitId)
            secretStore.putSecret(credentialSecretKey, result.ownerCredentials.toSecretJson())
            recordPrivateChatInstallEvidence(result.manifest)
            val healthy = result.manifest.toNodeState(credentialSecretKey)
            updateExit(exitId) {
                it.copy(
                    lifecycleState = ExitLifecycleState.READY,
                    lastError = null,
                    privateChat = healthy,
                )
            }
            healthy
        } catch (error: PrivateChatProvisioningException) {
            val failed = (_configuredExits.value.firstOrNull { it.id == exitId }?.privateChat
                ?: PrivateChatNodeState.installing()).copy(
                status = PrivateChatInstallStatus.FAILED,
                currentStage = error.failedStage,
                lastError = privateChatFailureMessage(error.message),
                lastUpdatedAt = System.currentTimeMillis(),
            )
            updateExit(exitId) {
                it.copy(
                    lifecycleState = ExitLifecycleState.READY,
                    lastError = null,
                    privateChat = failed,
                )
            }
            failed
        } catch (_: Exception) {
            val failed = (_configuredExits.value.firstOrNull { it.id == exitId }?.privateChat
                ?: PrivateChatNodeState.installing()).copy(
                status = PrivateChatInstallStatus.FAILED,
                currentStage = "PRIVATE_CHAT_COMPLETE",
                lastError = privateChatFailureMessage(
                    "Private Chat could not finish importing its validated node state. The VPN was retained.",
                ),
                lastUpdatedAt = System.currentTimeMillis(),
            )
            updateExit(exitId) {
                it.copy(
                    lifecycleState = ExitLifecycleState.READY,
                    lastError = null,
                    privateChat = failed,
                )
            }
            failed
        }
    }

    private fun recordPrivateChatEvent(exitId: String, remoteEvent: PrivateChatRemoteEvent) {
        val phase = Phase.entries.firstOrNull { it.name == remoteEvent.stage }
            ?: Phase.PRIVATE_CHAT_PRECHECK
        val status = when (remoteEvent.status.lowercase()) {
            "success" -> Status.SUCCESS
            "warning" -> Status.WARNING
            "error" -> Status.ERROR
            else -> Status.RUNNING
        }
        val safeMessage = redactPrivateChatDiagnostic(remoteEvent.message)
        val duration = remoteEvent.durationMillis?.let(::formatPrivateChatDuration)
        val displayMessage = when (status) {
            Status.RUNNING -> "${phase.label} started."
            Status.SUCCESS -> "${phase.label} completed${duration?.let { " in $it" }.orEmpty()}."
            Status.WARNING -> "${phase.label} warning: $safeMessage"
            Status.ERROR -> "${phase.label} failed${duration?.let { " after $it" }.orEmpty()}: " +
                privateChatFailureMessage(safeMessage)
        }
        val technicalDetail = if (_isDevMode.value) {
            buildString {
                append("stage=").append(remoteEvent.stage)
                append(" status=").append(remoteEvent.status.lowercase())
                append(" duration=").append(duration ?: "not-reported")
                append(" detail=").append(safeMessage)
            }
        } else {
            null
        }
        _currentPhase.value = phase
        _events.value = _events.value + ProvisioningEvent(
            timestamp = System.currentTimeMillis(),
            phase = phase,
            status = status,
            message = displayMessage,
            technicalDetail = technicalDetail,
        )
        updateExit(exitId) { exit ->
            val chat = exit.privateChat ?: PrivateChatNodeState.installing()
            val existingStage = chat.stageStates[remoteEvent.stage] ?: PrivateChatStageState()
            val nextStage = when (status) {
                Status.RUNNING -> existingStage.copy(
                    status = PrivateChatStageStatus.RUNNING,
                    attempts = existingStage.attempts + 1,
                    lastError = null,
                )
                Status.SUCCESS -> existingStage.copy(
                    status = PrivateChatStageStatus.COMPLETE,
                    lastError = null,
                )
                Status.WARNING -> existingStage
                Status.ERROR -> existingStage.copy(
                    status = PrivateChatStageStatus.FAILED,
                    lastError = safeMessage,
                )
            }
            exit.copy(
                privateChat = chat.copy(
                    currentStage = remoteEvent.stage,
                    lastError = if (status == Status.ERROR) privateChatFailureMessage(safeMessage) else chat.lastError,
                    stageStates = chat.stageStates + (remoteEvent.stage to nextStage),
                    lastSelfTestStatus = when {
                        remoteEvent.stage != "PRIVATE_CHAT_ENCRYPTION_SELF_TEST" -> chat.lastSelfTestStatus
                        status == Status.SUCCESS -> PrivateChatSelfTestStatus.PASS
                        status == Status.ERROR -> PrivateChatSelfTestStatus.FAIL
                        else -> chat.lastSelfTestStatus
                    },
                    lastUpdatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun recordPrivateChatInstallEvidence(manifest: PrivateChatNodeManifest) {
        val checks = manifest.healthChecks
        val postgresql = combineHealthChecks(
            checks.postgresqlProcessOk,
            checks.postgresqlConnectionOk,
        )
        val healthStatus = if (manifest.healthStatus == "healthy") Status.SUCCESS else Status.WARNING
        _events.value = _events.value + ProvisioningEvent(
            timestamp = System.currentTimeMillis(),
            phase = Phase.PRIVATE_CHAT_HEALTH,
            status = healthStatus,
            message = "Private Chat health checks: PostgreSQL=$postgresql, " +
                "Synapse=${combineHealthChecks(checks.synapseProcessOk, checks.synapseLoopbackListenerOk)}, " +
                "TLS endpoint=${healthWord(checks.tlsEndpointOk)}, " +
                "Matrix /versions=${healthWord(checks.matrixVersionsOk)}, " +
                "owner account=${if (checks.ownerAccountExists == true) "exists" else healthWord(checks.ownerAccountExists)}, " +
                "firewall=${healthWord(checks.privateFirewallPolicyActive)}, " +
                "self-test=${manifest.lastSelfTestStatus.name.lowercase().replace('_', '-')}.",
            technicalDetail = if (_isDevMode.value) {
                "checkedAt=${manifest.healthCheckedAt ?: "not-reported"} " +
                    "firewallService=${healthWord(checks.firewallServiceActive)} " +
                    "chatOnlyPolicyChains=${healthWord(checks.chatOnlyPolicyChainsReady)} " +
                    "chatOnlyPeerRulesActive=${checks.chatOnlyPeerRulesActive ?: false}"
            } else {
                null
            },
        )
        _events.value = _events.value + ProvisioningEvent(
            timestamp = System.currentTimeMillis(),
            phase = Phase.PRIVATE_CHAT_COMPLETE,
            status = Status.SUCCESS,
            message = "Private Chat node manifest validated.",
            technicalDetail = if (_isDevMode.value) {
                "server_name=${manifest.serverName} private_url=${manifest.matrixPrivateUrl} " +
                    "tls_fingerprint=${manifest.tlsSpkiSha256} versions=" +
                    manifest.componentVersions.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }
            } else {
                null
            },
        )
    }

    private fun privateChatFailureMessage(message: String?): String {
        val safe = redactPrivateChatDiagnostic(message ?: "Private Chat installation failed.")
        val guidance = "Retry Private Chat to resume from the saved VM stage; WireGuard remains available."
        if (safe.contains(guidance)) return safe.take(600)
        return "${safe.take(600 - guidance.length - 1)} $guidance"
    }

    private fun formatPrivateChatDuration(milliseconds: Long): String =
        if (milliseconds < 1_000L) {
            "$milliseconds ms"
        } else {
            val wholeSeconds = milliseconds / 1_000L
            val tenths = (milliseconds % 1_000L) / 100L
            "$wholeSeconds.$tenths s"
        }

    private fun combineHealthChecks(first: Boolean?, second: Boolean?): String = when {
        first == false || second == false -> "fail"
        first == true && second == true -> "pass"
        else -> "unknown"
    }

    private fun healthWord(value: Boolean?): String = when (value) {
        true -> "pass"
        false -> "fail"
        null -> "unknown"
    }

    private fun PrivateChatNodeManifest.toNodeState(credentialsSecretKey: String): PrivateChatNodeState =
        PrivateChatNodeState(
            status = PrivateChatInstallStatus.HEALTHY,
            currentStage = "PRIVATE_CHAT_COMPLETE",
            nodeId = nodeId,
            serverName = serverName,
            matrixPrivateUrl = matrixPrivateUrl,
            tlsSpkiSha256 = tlsSpkiSha256,
            ownerMatrixUserId = ownerMatrixUserId,
            ownerCredentialsSecretKey = credentialsSecretKey,
            installedAt = installedAt,
            healthStatus = healthStatus,
            healthCheckedAt = healthCheckedAt,
            healthChecks = healthChecks,
            stageStates = stageStates,
            lastSelfTestStatus = lastSelfTestStatus,
            lastSelfTestCheckedAt = lastSelfTestCheckedAt,
            componentVersions = componentVersions,
            lastUpdatedAt = System.currentTimeMillis(),
        )

    private fun createInviteSlotsForProvisionedExit(
        ownerExitId: String,
        inviteProfiles: List<OciProvisioner.InvitePeerProvisionResult>,
    ) {
        val repository = friendsRepository ?: return
        if (inviteProfiles.isEmpty()) return
        val now = System.currentTimeMillis()
        var slots = _inviteSlots.value
        inviteProfiles.sortedBy { it.slotIndex }.forEach { profile ->
            val slotId = "$ownerExitId:friend-${profile.slotIndex}"
            val secretKey = SecureSecretStore.inviteClientConfig(slotId)
            secretStore.putSecret(secretKey, profile.clientConfig)
            slots = repository.upsertInviteSlot(
                InviteSlot(
                    slotId = slotId,
                    ownerExitId = ownerExitId,
                    slotIndex = profile.slotIndex,
                    displayName = null,
                    state = InviteSlotState.UNUSED,
                    tunnelIp = profile.tunnelIp,
                    peerPublicKey = profile.clientPublicKey,
                    clientConfigSecretKey = secretKey,
                    encryptedClientConfig = null,
                    encryptedClientPrivateKey = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        _inviteSlots.value = slots
    }

    private fun openUrl(context: Context, url: String) {
        val uri = Uri.parse(url)
        runCatching {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            customTabsIntent.launchUrl(context, uri)
        }.getOrElse {
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun emit(phase: Phase, status: Status, message: String) {
        val event = ProvisioningEvent(
            timestamp = System.currentTimeMillis(),
            phase = phase,
            status = status,
            message = message,
            developerOnly = isDeveloperDiagnostic(message, status),
        )
        _events.value = _events.value + event
    }

    private fun classifyEvent(event: ProvisioningEvent): ProvisioningEvent =
        if (event.developerOnly || isDeveloperDiagnostic(event.message, event.status)) {
            event.copy(developerOnly = true)
        } else {
            event
        }

    private fun appendProvisionerEvent(event: ProvisioningEvent) {
        val classified = classifyEvent(event)
        val alreadyRecorded = _events.value.any { existing ->
            existing.timestamp == classified.timestamp &&
                existing.phase == classified.phase &&
                existing.status == classified.status &&
                existing.message == classified.message
        }
        if (!alreadyRecorded) {
            _events.value = _events.value + classified
        }
        if (classified.phase != Phase.DONE) {
            _currentPhase.value = classified.phase
        }
    }

    private fun isDeveloperDiagnostic(message: String, status: Status): Boolean {
        if (status == Status.ERROR) return false
        val text = message.lowercase()
        return text.contains("[wire tap]") ||
            text.contains("region trace:") ||
            text.contains("phase region trace:") ||
            text.contains("auth bootstrap region:") ||
            text.contains("token region source:") ||
            text.contains("region discovery candidates:") ||
            text.contains("trying candidate region:") ||
            text.contains("identity host:") ||
            text.contains("identity url:") ||
            text.contains("identity endpoint used:") ||
            text.contains("iaas host:") ||
            text.contains("signer region:") ||
            text.contains("realm/domain suffix:") ||
            text.contains("subscribed regions:") ||
            text.contains("dns attempted") ||
            text.contains("dns preflight") ||
            text.contains("unknownhostexception") ||
            text.contains("regionsubscriptions") ||
            text.contains("developer mode:") ||
            text.contains("developer mode affects diagnostics only") ||
            text.contains("api keys on account:") ||
            text.contains("post signing string") ||
            text.contains("authorization header generated") ||
            text.matches(Regex("^\\s*\\[\\d+] .*")) ||
            text.contains("upload response:") ||
            text.contains("availability domain selected:") ||
            text.contains("ssh attempt") ||
            text.contains("ssh not ready:") ||
            text.contains("ssh command") ||
            text.contains("sessionconnected") ||
            text.contains("commandstarted") ||
            text.contains("reconnecting ssh") ||
            text.contains("preinstall diagnostic") ||
            text.contains("apt-cache") ||
            text.contains("apt source") ||
            text.contains("package policy") ||
            text.contains("stdout") ||
            text.contains("stderr") ||
            text.contains("iptables") ||
            text.contains("wg-quick") ||
            text.contains("setup script")
    }

    private fun buildConfiguredExit(
        exitId: String,
        name: String,
        publicIp: String,
        wireGuardPort: Int,
        region: String,
        wireGuardConfig: String,
        resourceIds: OciProvisioner.ResourceIds?,
        sshUsername: String?,
        sshPrivateKey: String?,
        createdAt: Long,
        privateChat: PrivateChatNodeState? = null,
    ): ConfiguredExit {
        val wireGuardSecretKey = SecureSecretStore.oracleOwnerWireGuardConfig(exitId)
        if (wireGuardConfig.isNotBlank()) {
            secretStore.putSecret(wireGuardSecretKey, wireGuardConfig)
        }
        val sshSecretKey = sshPrivateKey?.takeIf { it.isNotBlank() }?.let {
            SecureSecretStore.oracleSshPrivateKey(exitId).also { key -> secretStore.putSecret(key, it) }
        }
        return ConfiguredExit(
            id = exitId,
            name = name,
            publicIp = publicIp,
            wireGuardPort = wireGuardPort,
            region = region,
            wireGuardConfig = wireGuardConfig,
            wireGuardConfigSecretKey = wireGuardSecretKey,
            provider = ExitProvider.OCI,
            endpointHost = publicIp,
            endpointPort = wireGuardPort,
            instanceId = resourceIds?.instanceId,
            sshUsername = sshUsername,
            sshPrivateKeySecretKey = sshSecretKey,
            sshPrivateKey = sshPrivateKey,
            apiKeyUserOcid = apiKeyUserOcid,
            apiKeyTenancyOcid = apiKeyTenancyOcid,
            apiKeyFingerprint = apiKeyFingerprint,
            lifecycleState = ExitLifecycleState.READY,
            createdAt = createdAt,
            ociResourceIds = resourceIds?.toConfiguredResourceIds(),
            serverPublicKey = wireGuardServerPublicKey ?: parseWireGuardValue(wireGuardConfig, "Peer", "PublicKey"),
            serverPeerPublicKey = wireGuardServerPeerPublicKey ?: wireGuardClientPublicKey,
            clientPublicKey = wireGuardClientPublicKey,
            privateChat = privateChat,
        )
    }

    private fun parseWireGuardValue(config: String, sectionName: String, key: String): String? {
        val start = config.indexOf("[$sectionName]")
        if (start < 0) return null
        val next = config.indexOf("\n[", start + sectionName.length + 2)
        val section = if (next >= 0) config.substring(start, next) else config.substring(start)
        return section.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(key, ignoreCase = true) && it.contains("=") }
            ?.substringAfter("=")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun restoreStateFromSelectedExitOrIdle() {
        val selected = _selectedExitId.value?.let { id -> _configuredExits.value.firstOrNull { it.id == id } }
            ?: _configuredExits.value.firstOrNull()
        if (selected == null) {
            _selectedExitId.value = null
            _state.value = ProvisioningState.Idle
            return
        }
        _selectedExitId.value = selected.id
        _publicIp.value = selected.publicIp
        _wireGuardPort.value = selected.wireGuardPort
        _state.value = ProvisioningState.Idle
    }

    private fun updateExit(exitId: String, transform: (ConfiguredExit) -> ConfiguredExit) {
        _configuredExits.value = _configuredExits.value.map { exit ->
            if (exit.id == exitId) transform(exit) else exit
        }
        persistState()
    }

    private fun cleanupOwnerExitLocalState(exit: ConfiguredExit) {
        exit.wireGuardConfigSecretKey?.let { secretStore.removeSecret(it) }
        exit.sshPrivateKeySecretKey?.let { secretStore.removeSecret(it) }
        exit.privateChat?.ownerCredentialsSecretKey?.let { secretStore.removeSecret(it) }
        secretStore.removeSecret(SecureSecretStore.privateChatOwnerCredentials(exit.id))
        _inviteSlots.value
            .filter { it.ownerExitId == exit.id }
            .mapNotNull { it.clientConfigSecretKey }
            .forEach { secretStore.removeSecret(it) }
        friendsRepository?.let { repository ->
            _inviteSlots.value = repository.removeInviteSlotsForOwnerExit(exit.id)
        }
    }

    private fun recordInviteOperationError(detail: String) {
        _lastInviteOperationError.value = detail
        Log.w(TAG, detail)
        persistState()
    }

    private fun buildInviteResetErrorDetail(
        phase: InvitePeerResetPhase,
        ownerExit: ConfiguredExit,
        slot: InviteSlot,
        message: String,
        exitCode: Int? = null,
        commandStderr: String = "",
        cleanupError: String? = null,
        oldInviteInvalidated: Boolean = false,
    ): String = buildString {
        append("reset phase=").append(phase.name)
        append(" ownerExitId=").append(ownerExit.id)
        append(" slotId=").append(slot.slotId)
        append(" slotIndex=").append(slot.slotIndex)
        append(" tunnelIp=").append(slot.tunnelIp ?: "N/A")
        append(" oldPeerPrefix=").append(slot.peerPublicKey.safePublicKeyPrefix())
        append(" sshHost=").append(ownerExit.publicIp.ifBlank { "N/A" })
        append(" sshUser=").append(ownerExit.sshUsername ?: "N/A")
        exitCode?.let { append(" exitCode=").append(it) }
        append(" oldInviteInvalidated=").append(if (oldInviteInvalidated) "yes" else "no")
        append(" message=").append(message.sanitizeInviteDiagnostic())
        commandStderr.takeIf { it.isNotBlank() }?.let {
            append(" stderr=").append(it.sanitizeInviteDiagnostic())
        }
        cleanupError?.takeIf { it.isNotBlank() }?.let {
            append(" cleanupError=").append(it.sanitizeInviteDiagnostic())
        }
    }

    private fun InvitePeerResetPhase.mayHaveInvalidatedOldInvite(): Boolean =
        when (this) {
            InvitePeerResetPhase.RESTART_WG_FAILED,
            InvitePeerResetPhase.VERIFY_NEW_PEER_FAILED,
            InvitePeerResetPhase.VERIFY_LATEST_HANDSHAKES_FAILED
            -> true
            else -> false
        }

    private fun String?.safePublicKeyPrefix(): String =
        this?.takeIf { it.isNotBlank() }?.let { value ->
            if (value.length <= 10) "$value..." else "${value.take(10)}..."
        } ?: "N/A"

    private fun String?.safeOcidPrefix(): String? =
        this?.takeIf { it.isNotBlank() }?.let { value ->
            if (value.length <= 18) "$value..." else "${value.take(18)}..."
        }

    private fun String.sanitizeInviteDiagnostic(): String =
        lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" | ")
            .take(700)

    private fun nextExitName(): String {
        val used = _configuredExits.value.mapNotNull { exit ->
            Regex("""^Exit (\d+)$""").matchEntire(exit.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        return "Exit ${((used.maxOrNull() ?: 0) + 1)}"
    }

    private fun newExitId(): String = "oci:${UUID.randomUUID()}"

    private fun OciProvisioner.ResourceIds.toConfiguredResourceIds(): OciResourceIds = OciResourceIds(
        vcnId = vcnId,
        securityListId = slId,
        subnetId = subnetId,
        internetGatewayId = igwId,
        instanceId = instanceId,
    )

    private fun OciResourceIds.toProvisionerResourceIds(): OciProvisioner.ResourceIds = OciProvisioner.ResourceIds(
        vcnId = vcnId,
        slId = securityListId,
        subnetId = subnetId,
        igwId = internetGatewayId,
        instanceId = instanceId,
    )

    private fun serializeConfiguredExits(exits: List<ConfiguredExit>): String {
        val array = JSONArray()
        exits.sortedBy { it.createdAt }.forEach { exit ->
            array.put(exit.toJson())
        }
        return array.toString()
    }

    private fun ConfiguredExit.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("provider", provider.name)
        .put("publicIp", publicIp)
        .put("wireGuardPort", wireGuardPort)
        .put("region", region)
        .put("wireGuardConfig", JSONObject.NULL)
        .put("wireGuardConfigSecretKey", wireGuardConfigSecretKey)
        .put("endpointHost", endpointHost)
        .put("endpointPort", endpointPort)
        .put("compartmentId", compartmentId)
        .put("instanceId", instanceId)
        .put("sshUsername", sshUsername)
        .put("sshPrivateKeySecretKey", sshPrivateKeySecretKey)
        .put("sshPrivateKey", JSONObject.NULL)
        .put("apiKeyUserOcid", apiKeyUserOcid)
        .put("apiKeyTenancyOcid", apiKeyTenancyOcid)
        .put("apiKeyFingerprint", apiKeyFingerprint)
        .put("lifecycleState", lifecycleState.name)
        .put("createdAt", createdAt)
        .put("lastConnectedAt", lastConnectedAt)
        .put("lastError", lastError)
        .put("serverPublicKey", serverPublicKey)
        .put("serverPeerPublicKey", serverPeerPublicKey)
        .put("clientPublicKey", clientPublicKey)
        .put("transportLabel", transportLabel)
        .put("tcpSupported", tcpSupported)
        .put("udpSupported", udpSupported)
        .put("dnsStatus", dnsStatus)
        .put("destroyMeaning", destroyMeaning)
        .put("privateChatStatus", privateChatStatus.name)
        .put("privateChatCandidateId", privateChatCandidateId)
        .put("sourceExitId", sourceExitId)
        .put("previousExitId", previousExitId)
        .put("privateChat", privateChat?.toJson())
        .put("ociResourceIds", ociResourceIds?.toJson())

    private fun OciResourceIds.toJson(): JSONObject = JSONObject()
        .put("vcnId", vcnId)
        .put("securityListId", securityListId)
        .put("subnetId", subnetId)
        .put("internetGatewayId", internetGatewayId)
        .put("instanceId", instanceId)

    private fun PrivateChatNodeState.toJson(): JSONObject {
        val versions = JSONObject()
        componentVersions.toSortedMap().forEach { (name, version) -> versions.put(name, version) }
        val stages = JSONObject()
        stageStates.toSortedMap().forEach { (name, stage) ->
            stages.put(
                name,
                JSONObject()
                    .put("status", stage.status.name)
                    .put("attempts", stage.attempts)
                    .put("startedAt", stage.startedAt)
                    .put("completedAt", stage.completedAt)
                    .put("lastError", stage.lastError)
                    .put("probeSatisfied", stage.probeSatisfied),
            )
        }
        return JSONObject()
            .put("status", status.name)
            .put("currentStage", currentStage)
            .put("lastError", lastError)
            .put("nodeId", nodeId)
            .put("serverName", serverName)
            .put("matrixPrivateUrl", matrixPrivateUrl)
            .put("tlsSpkiSha256", tlsSpkiSha256)
            .put("ownerMatrixUserId", ownerMatrixUserId)
            .put("ownerCredentialsSecretKey", ownerCredentialsSecretKey)
            .put("installedAt", installedAt)
            .put("healthStatus", healthStatus)
            .put("healthCheckedAt", healthCheckedAt)
            .put("healthChecks", healthChecks.toJson())
            .put("stageStates", stages)
            .put("lastSelfTestStatus", lastSelfTestStatus.name)
            .put("lastSelfTestCheckedAt", lastSelfTestCheckedAt)
            .put("componentVersions", versions)
            .put("ownerLoginVerifiedAt", ownerLoginVerifiedAt)
            .put("lastUpdatedAt", lastUpdatedAt)
    }

    private fun PrivateChatHealthChecks.toJson(): JSONObject = JSONObject()
        .put("postgresqlProcessOk", postgresqlProcessOk)
        .put("postgresqlConnectionOk", postgresqlConnectionOk)
        .put("synapseProcessOk", synapseProcessOk)
        .put("synapseLoopbackListenerOk", synapseLoopbackListenerOk)
        .put("tlsEndpointOk", tlsEndpointOk)
        .put("matrixVersionsOk", matrixVersionsOk)
        .put("ownerAccountExists", ownerAccountExists)
        .put("firewallServiceActive", firewallServiceActive)
        .put("privateFirewallPolicyActive", privateFirewallPolicyActive)
        .put("chatOnlyPolicyChainsReady", chatOnlyPolicyChainsReady)
        .put("chatOnlyPeerRulesActive", chatOnlyPeerRulesActive)

    private fun JSONObject.toConfiguredExit(): ConfiguredExit {
        val exitId = getString("id")
        val provider = runCatching {
            ExitProvider.valueOf(optString("provider", ExitProvider.OCI.name))
        }.getOrDefault(ExitProvider.OCI)
        val legacyWireGuardConfig = optNullableString("wireGuardConfig")
        val storedWireGuardSecretKey = optNullableString("wireGuardConfigSecretKey")
        val wireGuardSecretKey = storedWireGuardSecretKey
            ?: legacyWireGuardConfig?.takeIf { it.isNotBlank() }?.let {
                if (provider == ExitProvider.SHARED_WIREGUARD) {
                    SecureSecretStore.sharedWireGuardConfig(exitId)
                } else {
                    SecureSecretStore.oracleOwnerWireGuardConfig(exitId)
                }
            }
        if (legacyWireGuardConfig != null && storedWireGuardSecretKey == null && wireGuardSecretKey != null) {
            secretStore.putSecret(wireGuardSecretKey, legacyWireGuardConfig)
        }
        val legacySshPrivateKey = optNullableString("sshPrivateKey")
        val storedSshSecretKey = optNullableString("sshPrivateKeySecretKey")
        val sshSecretKey = storedSshSecretKey
            ?: legacySshPrivateKey?.takeIf { it.isNotBlank() }?.let {
                SecureSecretStore.oracleSshPrivateKey(exitId)
            }
        if (legacySshPrivateKey != null && storedSshSecretKey == null && sshSecretKey != null) {
            secretStore.putSecret(sshSecretKey, legacySshPrivateKey)
        }
        val resourceJson = optJSONObject("ociResourceIds")
        val privateChatJson = optJSONObject("privateChat")
        return ConfiguredExit(
            id = exitId,
            name = optString("name").takeIf { it.isNotBlank() } ?: "Exit 1",
            provider = provider,
            publicIp = getString("publicIp"),
            wireGuardPort = optInt("wireGuardPort", 51820),
            region = optString("region", LEGACY_REGION_FALLBACK),
            wireGuardConfig = wireGuardSecretKey?.let { secretStore.getSecret(it) }.orEmpty(),
            wireGuardConfigSecretKey = wireGuardSecretKey,
            endpointHost = optString("endpointHost").takeIf { it.isNotBlank() } ?: getString("publicIp"),
            endpointPort = optInt("endpointPort", optInt("wireGuardPort", 51820)),
            compartmentId = optNullableString("compartmentId"),
            instanceId = optNullableString("instanceId"),
            sshUsername = optNullableString("sshUsername"),
            sshPrivateKeySecretKey = sshSecretKey,
            sshPrivateKey = sshSecretKey?.let { secretStore.getSecret(it) },
            apiKeyUserOcid = optNullableString("apiKeyUserOcid"),
            apiKeyTenancyOcid = optNullableString("apiKeyTenancyOcid"),
            apiKeyFingerprint = optNullableString("apiKeyFingerprint"),
            lifecycleState = runCatching {
                ExitLifecycleState.valueOf(optString("lifecycleState", ExitLifecycleState.READY.name))
            }.getOrDefault(ExitLifecycleState.READY),
            createdAt = optLong("createdAt", System.currentTimeMillis()),
            lastConnectedAt = optLongOrNull("lastConnectedAt"),
            lastError = optNullableString("lastError"),
            ociResourceIds = resourceJson?.let {
                OciResourceIds(
                    vcnId = it.optNullableString("vcnId"),
                    securityListId = it.optNullableString("securityListId"),
                    subnetId = it.optNullableString("subnetId"),
                    internetGatewayId = it.optNullableString("internetGatewayId"),
                    instanceId = it.optNullableString("instanceId"),
                )
            },
            serverPublicKey = optNullableString("serverPublicKey"),
            serverPeerPublicKey = optNullableString("serverPeerPublicKey"),
            clientPublicKey = optNullableString("clientPublicKey"),
            transportLabel = optNullableString("transportLabel"),
            tcpSupported = optBooleanOrNull("tcpSupported"),
            udpSupported = optBooleanOrNull("udpSupported"),
            dnsStatus = optNullableString("dnsStatus"),
            destroyMeaning = optNullableString("destroyMeaning"),
            privateChatStatus = runCatching {
                PrivateChatCapabilityStatus.valueOf(optString("privateChatStatus", PrivateChatCapabilityStatus.NOT_REQUESTED.name))
            }.getOrDefault(PrivateChatCapabilityStatus.NOT_REQUESTED),
            privateChatCandidateId = optNullableString("privateChatCandidateId"),
            sourceExitId = optNullableString("sourceExitId"),
            previousExitId = optNullableString("previousExitId"),
            privateChat = privateChatJson?.toPrivateChatNodeState(),
        )
    }

    private fun JSONObject.toPrivateChatNodeState(): PrivateChatNodeState {
        val versionsJson = optJSONObject("componentVersions")
        val versions = buildMap {
            versionsJson?.keys()?.forEach { name ->
                versionsJson.optString(name).takeIf { it.isNotBlank() }?.let { put(name, it) }
            }
        }
        val healthJson = optJSONObject("healthChecks")
        val healthChecks = PrivateChatHealthChecks(
            postgresqlProcessOk = healthJson?.optBooleanOrNull("postgresqlProcessOk"),
            postgresqlConnectionOk = healthJson?.optBooleanOrNull("postgresqlConnectionOk"),
            synapseProcessOk = healthJson?.optBooleanOrNull("synapseProcessOk"),
            synapseLoopbackListenerOk = healthJson?.optBooleanOrNull("synapseLoopbackListenerOk"),
            tlsEndpointOk = healthJson?.optBooleanOrNull("tlsEndpointOk"),
            matrixVersionsOk = healthJson?.optBooleanOrNull("matrixVersionsOk"),
            ownerAccountExists = healthJson?.optBooleanOrNull("ownerAccountExists"),
            firewallServiceActive = healthJson?.optBooleanOrNull("firewallServiceActive"),
            privateFirewallPolicyActive = healthJson?.optBooleanOrNull("privateFirewallPolicyActive"),
            chatOnlyPolicyChainsReady = healthJson?.optBooleanOrNull("chatOnlyPolicyChainsReady"),
            chatOnlyPeerRulesActive = healthJson?.optBooleanOrNull("chatOnlyPeerRulesActive"),
        )
        val stagesJson = optJSONObject("stageStates")
        val stages = buildMap {
            PRIVATE_CHAT_STAGE_ORDER.forEach { name ->
                val stage = stagesJson?.optJSONObject(name) ?: return@forEach
                put(
                    name,
                    PrivateChatStageState(
                        status = runCatching {
                            PrivateChatStageStatus.valueOf(
                                stage.optString("status", PrivateChatStageStatus.PENDING.name),
                            )
                        }.getOrDefault(PrivateChatStageStatus.PENDING),
                        attempts = stage.optInt("attempts", 0).coerceAtLeast(0),
                        startedAt = stage.optNullableString("startedAt"),
                        completedAt = stage.optNullableString("completedAt"),
                        lastError = stage.optNullableString("lastError")
                            ?.let(::redactPrivateChatDiagnostic),
                        probeSatisfied = stage.optBoolean("probeSatisfied", false),
                    ),
                )
            }
        }
        return PrivateChatNodeState(
            status = runCatching {
                PrivateChatInstallStatus.valueOf(optString("status", PrivateChatInstallStatus.FAILED.name))
            }.getOrDefault(PrivateChatInstallStatus.FAILED),
            currentStage = optNullableString("currentStage")?.takeIf { it in PRIVATE_CHAT_STAGE_ORDER },
            lastError = optNullableString("lastError")?.let(::redactPrivateChatDiagnostic),
            nodeId = optNullableString("nodeId"),
            serverName = optNullableString("serverName"),
            matrixPrivateUrl = optNullableString("matrixPrivateUrl"),
            tlsSpkiSha256 = optNullableString("tlsSpkiSha256"),
            ownerMatrixUserId = optNullableString("ownerMatrixUserId"),
            ownerCredentialsSecretKey = optNullableString("ownerCredentialsSecretKey"),
            installedAt = optNullableString("installedAt"),
            healthStatus = optNullableString("healthStatus"),
            healthCheckedAt = optNullableString("healthCheckedAt"),
            healthChecks = healthChecks,
            stageStates = stages,
            lastSelfTestStatus = runCatching {
                PrivateChatSelfTestStatus.valueOf(
                    optString("lastSelfTestStatus", PrivateChatSelfTestStatus.NOT_RUN.name),
                )
            }.getOrDefault(PrivateChatSelfTestStatus.NOT_RUN),
            lastSelfTestCheckedAt = optNullableString("lastSelfTestCheckedAt"),
            componentVersions = versions,
            ownerLoginVerifiedAt = optLongOrNull("ownerLoginVerifiedAt"),
            lastUpdatedAt = optLong("lastUpdatedAt", System.currentTimeMillis()),
        )
    }

    private fun ConfiguredExit.isReadyPrivateChatCandidate(): Boolean {
        val chat = privateChat ?: return false
        return wireGuardConfig.isNotBlank() &&
            publicIp.isNotBlank() &&
            chat.status == PrivateChatInstallStatus.HEALTHY &&
            chat.healthChecks.postgresqlProcessOk == true &&
            chat.healthChecks.postgresqlConnectionOk == true &&
            chat.healthChecks.synapseProcessOk == true &&
            chat.healthChecks.synapseLoopbackListenerOk == true &&
            chat.healthChecks.tlsEndpointOk == true &&
            chat.healthChecks.matrixVersionsOk == true &&
            chat.healthChecks.ownerAccountExists == true &&
            chat.healthChecks.firewallServiceActive == true &&
            chat.healthChecks.privateFirewallPolicyActive == true &&
            chat.healthChecks.chatOnlyPolicyChainsReady == true &&
            chat.lastSelfTestStatus == PrivateChatSelfTestStatus.PASS &&
            !chat.tlsSpkiSha256.isNullOrBlank()
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (has(name) && !isNull(name)) optLong(name) else null

    private fun JSONObject.optBooleanOrNull(name: String): Boolean? =
        if (has(name) && !isNull(name)) optBoolean(name) else null

    override fun onCleared() {
        capacityRetryReconciliationJob?.cancel()
        capacityRetryWorkObservationJob?.cancel()
        capacityRetryWorkObservers.values.forEach { registration ->
            registration.liveData.removeObserver(registration.observer)
        }
        capacityRetryWorkObservers.clear()
        if (::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(capacityRetryPrefsListener)
        }
        super.onCleared()
    }

    companion object {
        const val ORACLE_SIGNUP_URL = "https://signup.oraclecloud.com/"
        private const val TAG = "ZeroVpnProvisioning"
        // Only for old persisted exits created before region was stored per exit.
        private const val LEGACY_REGION_FALLBACK = "uk-london-1"
        private const val AUTH_BOOTSTRAP_REGION = "us-ashburn-1"
    }
}
