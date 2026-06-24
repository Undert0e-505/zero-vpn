package com.zerovpn.app.ui.provisioning

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.browser.customtabs.CustomTabsIntent
import com.zerovpn.app.friends.FriendsRepository
import com.zerovpn.app.friends.InviteSlot
import com.zerovpn.app.friends.InviteSlotState
import com.zerovpn.app.friends.SharedExitProfile
import com.zerovpn.app.oci.OciProvisioner
import com.zerovpn.app.oci.OciRegion
import com.zerovpn.app.oci.OciRegions
import com.zerovpn.app.vpn.ConfiguredExit
import com.zerovpn.app.vpn.ExitLifecycleState
import com.zerovpn.app.vpn.ExitProvider
import com.zerovpn.app.vpn.OciResourceIds
import com.zerovpn.app.vpn.ProviderSwitchDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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

    private val _oracleOnboardingState = MutableStateFlow(OracleOnboardingState.NotStarted)
    val oracleOnboardingState: StateFlow<OracleOnboardingState> = _oracleOnboardingState.asStateFlow()

    private val _selectedOracleRegion = MutableStateFlow<String?>(null)
    val selectedOracleRegion: StateFlow<String?> = _selectedOracleRegion.asStateFlow()

    val oracleRegions: List<OciRegion> = OciRegions.common

    data class SshDebugInfo(
        val publicIp: String,
        val username: String,
        val privateKey: String,
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

    private var provisioner: OciProvisioner? = null
    private var authResult: OciProvisioner.AuthResult? = null
    private var preflightResult: OciProvisioner.PreflightResult? = null
    private var resourceIds: OciProvisioner.ResourceIds? = null
    private var clientConfig: String? = null
    private var wireGuardClientPublicKey: String? = null
    private var wireGuardServerPublicKey: String? = null
    private var wireGuardServerPeerPublicKey: String? = null
    private var homeRegion: String? = null
    private var apiKeyUserOcid: String? = null
    private var apiKeyTenancyOcid: String? = null
    private var apiKeyFingerprint: String? = null
    private var pendingProvisionExitId: String? = null
    private var provisioningJob: Job? = null

    // State persistence
    private lateinit var prefs: SharedPreferences
    private var friendsRepository: FriendsRepository? = null
    private var prefsLoaded = false

    fun initPrefs(context: Context) {
        if (prefsLoaded) return
        prefs = context.getSharedPreferences("zerovpn_provisioning", Context.MODE_PRIVATE)
        friendsRepository = FriendsRepository(prefs)
        loadPersistedState()
        loadFriendsState()
        prefsLoaded = true
    }

    private fun loadFriendsState() {
        val repository = friendsRepository ?: return
        _inviteSlots.value = repository.listInviteSlots()
        _sharedExitProfiles.value = repository.listSharedExits()
    }

    private fun loadPersistedState() {
        if (!::prefs.isInitialized) return
        _isDevMode.value = prefs.getBoolean("is_dev_mode", false)
        _oracleOnboardingState.value = runCatching {
            OracleOnboardingState.valueOf(
                prefs.getString("oracle_onboarding_state", OracleOnboardingState.NotStarted.name)
                    ?: OracleOnboardingState.NotStarted.name,
            )
        }.getOrDefault(OracleOnboardingState.NotStarted)
        homeRegion = prefs.getString("home_region", null)
        _selectedOracleRegion.value = prefs.getString("selected_oracle_region", null)
            ?: homeRegion
        apiKeyUserOcid = prefs.getString("api_key_user_ocid", null)
        apiKeyTenancyOcid = prefs.getString("api_key_tenancy_ocid", null)
        apiKeyFingerprint = prefs.getString("api_key_fingerprint", null)

        val exits = loadConfiguredExits()
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
            resourceIds = selected.ociResourceIds?.toProvisionerResourceIds()
            _state.value = ProvisioningState.Success(
                publicIp = selected.publicIp,
                wireGuardPort = selected.wireGuardPort,
                region = selected.region,
                isDevMode = _isDevMode.value,
            )
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
                _state.value = ProvisioningState.Success(
                    publicIp = savedIp,
                    wireGuardPort = savedPort,
                    region = homeRegion ?: LEGACY_REGION_FALLBACK,
                    isDevMode = _isDevMode.value,
                )
            }
            // Don't restore Running state — if we were mid-provision, user needs to retry
        }
    }

    private fun persistState() {
        if (!::prefs.isInitialized) return
        prefs.edit().apply {
            listOf(
                "home_region",
                "selected_oracle_region",
                "api_key_user_ocid",
                "api_key_tenancy_ocid",
                "api_key_fingerprint",
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
            ).forEach { remove(it) }
            putString("state", _state.value::class.simpleName)
            putString("oracle_onboarding_state", _oracleOnboardingState.value.name)
            putBoolean("is_dev_mode", _isDevMode.value)
            homeRegion?.let { putString("home_region", it) }
            _selectedOracleRegion.value?.let { putString("selected_oracle_region", it) }
            apiKeyUserOcid?.let { putString("api_key_user_ocid", it) }
            apiKeyTenancyOcid?.let { putString("api_key_tenancy_ocid", it) }
            apiKeyFingerprint?.let { putString("api_key_fingerprint", it) }
            _publicIp.value?.let { putString("public_ip", it) }
            putInt("wireguard_port", _wireGuardPort.value)
            clientConfig?.let { putString("wireguard_client_config", it) }
            wireGuardClientPublicKey?.let { putString("wireguard_client_public_key", it) }
            wireGuardServerPublicKey?.let { putString("wireguard_server_public_key", it) }
            wireGuardServerPeerPublicKey?.let { putString("wireguard_server_peer_public_key", it) }
            _selectedExitId.value?.let { putString("selected_exit_id", it) }
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

    fun showPreStart() {
        _state.value = ProvisioningState.PreStart
        _oracleOnboardingState.value = OracleOnboardingState.NotStarted
        persistState()
    }

    fun prepareNewProvisioningFlow() {
        if (_state.value is ProvisioningState.Running || _state.value is ProvisioningState.Destroying) {
            return
        }
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
        apiKeyFingerprint = null
        pendingProvisionExitId = null
        _state.value = ProvisioningState.PreStart
        _oracleOnboardingState.value = OracleOnboardingState.NotStarted
        persistState()
    }

    fun launchOracleSignup(context: Context) {
        _oracleOnboardingState.value = OracleOnboardingState.SignupLaunched
        persistState()
        openUrl(context, ORACLE_SIGNUP_URL)
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
            persistState()
        }
    }

    fun onAppResumed() {
        if (_oracleOnboardingState.value == OracleOnboardingState.SignupLaunched) {
            _oracleOnboardingState.value = OracleOnboardingState.WaitingForAccountSetup
            persistState()
        } else if (_oracleOnboardingState.value == OracleOnboardingState.AuthLaunched) {
            _oracleOnboardingState.value = OracleOnboardingState.WaitingForAuthReturn
            persistState()
        }
    }

    fun cancel() {
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
        provisioningJob?.cancel()
        provisioningJob = null
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
            _sshDebugInfo.value = selected.sshPrivateKey?.let {
                SshDebugInfo(
                    publicIp = selected.publicIp,
                    username = selected.sshUsername ?: "ubuntu",
                    privateKey = it,
                )
            }
        }
        persistState()
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
        friendsRepository?.let { repository ->
            _inviteSlots.value = repository.markInviteSlotClaimed(slotId, firstHandshakeAt, lastHandshakeAt)
        }
    }

    fun clearBurnedPrivateMaterial(slotId: String) {
        friendsRepository?.let { repository ->
            _inviteSlots.value = repository.clearBurnedPrivateMaterial(slotId)
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
        _configuredExits.value = _configuredExits.value.filterNot { it.id == exitId }
        if (_selectedExitId.value == exitId) {
            _selectedExitId.value = _configuredExits.value.firstOrNull()?.id
        }
        restoreStateFromSelectedExitOrIdle()
        persistState()
    }

    fun startProvisioning(context: Context) {
        if (_state.value is ProvisioningState.Running) return
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
        provisioningJob?.cancel()
        provisioningJob = viewModelScope.launch {
            runProvisioning(context)
        }
    }

    fun retry(context: Context) {
        if (::prefs.isInitialized) {
            _isDevMode.value = prefs.getBoolean("is_dev_mode", false)
        }
        _events.value = emptyList()
        _currentPhase.value = null
        _publicIp.value = null
        _sshDebugInfo.value = null
        clientConfig = null
        wireGuardClientPublicKey = null
        wireGuardServerPublicKey = null
        wireGuardServerPeerPublicKey = null
        _oracleOnboardingState.value = OracleOnboardingState.AuthLaunched
        _state.value = ProvisioningState.Running
        persistState()
        provisioningJob?.cancel()
        provisioningJob = viewModelScope.launch {
            runProvisioning(context)
        }
    }

    fun continueAfterUkWarning(context: Context) {
        _state.value = ProvisioningState.Running
        viewModelScope.launch {
            continueProvisioningAfterWarning(context)
        }
    }

    fun destroyNode(context: Context, exitId: String? = _selectedExitId.value) {
        val targetExit = exitId?.let { id -> _configuredExits.value.firstOrNull { it.id == id } }
        _state.value = ProvisioningState.Destroying
        if (targetExit != null) {
            updateExit(targetExit.id) { it.copy(lifecycleState = ExitLifecycleState.DESTROYING, lastError = null) }
        }
        viewModelScope.launch {
            val currentRids = targetExit?.ociResourceIds?.toProvisionerResourceIds() ?: resourceIds ?: loadResourceIds()
            val currentRegion = targetExit?.region ?: homeRegion
            val currentApiKeyUserOcid = targetExit?.apiKeyUserOcid ?: apiKeyUserOcid
            val currentApiKeyFingerprint = targetExit?.apiKeyFingerprint ?: apiKeyFingerprint

            if (currentRids == null) {
                targetExit?.let {
                    updateExit(it.id) { exit ->
                        exit.copy(
                            lifecycleState = ExitLifecycleState.FAILED,
                            lastError = "Local resource IDs are missing. Delete the node resources manually in the Oracle Console.",
                        )
                    }
                }
                _state.value = ProvisioningState.Failure(
                    failedPhase = Phase.DONE,
                    lastSuccessPhase = null,
                    errorMessage = "Local resource IDs are missing. Delete the node resources manually in the Oracle Console.",
                )
                return@launch
            }
            if (currentRegion == null) {
                val message = "Oracle region is missing for this node. Delete the node resources manually in the Oracle Console."
                targetExit?.let {
                    updateExit(it.id) { exit ->
                        exit.copy(lifecycleState = ExitLifecycleState.FAILED, lastError = message)
                    }
                }
                _state.value = ProvisioningState.Failure(
                    failedPhase = Phase.DONE,
                    lastSuccessPhase = null,
                    errorMessage = message,
                )
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
                    emit(Phase.AUTH, Status.RUNNING, "Authentication required before destroy...")
                    prov.authenticate().also { authResult = it }
                }

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
                apiKeyUserOcid = null
                apiKeyTenancyOcid = null
                apiKeyFingerprint = null
                if (::prefs.isInitialized) {
                    persistState()
                }
            } catch (e: Exception) {
                targetExit?.let {
                    updateExit(it.id) { exit ->
                        exit.copy(
                            lifecycleState = ExitLifecycleState.FAILED,
                            lastError = e.message,
                        )
                    }
                }
                _state.value = ProvisioningState.Failure(
                    failedPhase = _currentPhase.value ?: Phase.DONE,
                    lastSuccessPhase = null,
                    errorMessage = e.message,
                )
            } finally {
                eventJob.cancel()
            }
        }
    }

    fun cleanup(context: Context) {
        viewModelScope.launch {
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
            restoreStateFromSelectedExitOrIdle()
            persistState()
        }
    }

    private suspend fun runProvisioning(context: Context) {
        try {
            val uiSelectedRegion = _selectedOracleRegion.value
            val persistedRegion = homeRegion
            val authBootstrapRegion = uiSelectedRegion ?: persistedRegion ?: AUTH_BOOTSTRAP_REGION
            provisioner = OciProvisioner(context, authBootstrapRegion, _isDevMode.value)

            // Collect events from provisioner
            val prov = provisioner!!
            val eventJob = viewModelScope.launch {
                prov.events.collect { event ->
                    _events.value = _events.value + classifyEvent(event)
                    if (event.phase != Phase.DONE) {
                        _currentPhase.value = event.phase
                    }
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
                eventJob.cancel()
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
                eventJob.cancel()
                homeRegion = preflightResult!!.homeRegion
                _selectedOracleRegion.value = preflightResult!!.homeRegion
                _state.value = ProvisioningState.UkWarning(preflightResult!!.homeRegion)
                persistState()
                return
            }

            // Continue to provisioning
            doProvision(context, prov)

        } catch (e: Exception) {
            val lastSuccess = when (_currentPhase.value) {
                Phase.AUTH -> null
                Phase.API_KEY -> Phase.AUTH
                Phase.NETWORK -> Phase.API_KEY
                Phase.VM_LAUNCH -> Phase.NETWORK
                Phase.WAIT_SSH -> Phase.VM_LAUNCH
                Phase.WIREGUARD -> Phase.WAIT_SSH
                else -> null
            }
            _state.value = ProvisioningState.Failure(
                failedPhase = _currentPhase.value ?: Phase.AUTH,
                lastSuccessPhase = lastSuccess,
                errorMessage = e.message,
            )
            if (_currentPhase.value == Phase.AUTH) {
                _oracleOnboardingState.value = OracleOnboardingState.AuthFailed
            }
            persistState()
        }
    }

    private suspend fun continueProvisioningAfterWarning(context: Context) {
        try {
            val warningRegion = preflightResult?.homeRegion ?: homeRegion ?: _selectedOracleRegion.value
                ?: throw IllegalStateException("Oracle region is not available.")
            val prov = provisioner ?: OciProvisioner(context, warningRegion, _isDevMode.value).also { provisioner = it }
            val auth = authResult ?: return

            val eventJob = viewModelScope.launch {
                prov.events.collect { event ->
                    _events.value = _events.value + classifyEvent(event)
                    if (event.phase != Phase.DONE) {
                        _currentPhase.value = event.phase
                    }
                }
            }

            doProvision(context, prov)
        } catch (e: Exception) {
            _state.value = ProvisioningState.Failure(
                failedPhase = _currentPhase.value ?: Phase.AUTH,
                lastSuccessPhase = null,
                errorMessage = e.message,
            )
            persistState()
        }
    }

    private suspend fun doProvision(context: Context, prov: OciProvisioner) {
        try {
            val auth = authResult!!
            val preflight = preflightResult!!
            homeRegion = preflight.homeRegion
            _selectedOracleRegion.value = preflight.homeRegion
            apiKeyUserOcid = auth.userOcid
            apiKeyTenancyOcid = auth.tenancyOcid
            apiKeyFingerprint = auth.fingerprint
            persistState()

            val (rids, result) = prov.provision(auth, preflight)
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
                privateKey = result.sshPrivateKey,
            )
            val configuredExit = buildConfiguredExit(
                exitId = pendingProvisionExitId ?: newExitId(),
                name = nextExitName(),
                publicIp = result.publicIp,
                wireGuardPort = result.wireGuardPort,
                region = preflight.homeRegion,
                wireGuardConfig = result.clientConfig,
                resourceIds = rids,
                sshUsername = result.sshUsername,
                sshPrivateKey = result.sshPrivateKey,
                createdAt = System.currentTimeMillis(),
            )
            _configuredExits.value = _configuredExits.value + configuredExit
            _selectedExitId.value = configuredExit.id

            _currentPhase.value = Phase.DONE
            _state.value = ProvisioningState.Success(
                publicIp = result.publicIp,
                wireGuardPort = result.wireGuardPort,
                region = preflight.homeRegion,
                isDevMode = _isDevMode.value,
            )
            _oracleOnboardingState.value = OracleOnboardingState.NotStarted
            persistState()
        } catch (e: Exception) {
            _state.value = ProvisioningState.Failure(
                failedPhase = _currentPhase.value ?: Phase.AUTH,
                lastSuccessPhase = null,
                errorMessage = e.message,
            )
            if (_currentPhase.value == Phase.AUTH) {
                _oracleOnboardingState.value = OracleOnboardingState.AuthFailed
            }
            persistState()
        }
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
    ): ConfiguredExit = ConfiguredExit(
        id = exitId,
        name = name,
        publicIp = publicIp,
        wireGuardPort = wireGuardPort,
        region = region,
        wireGuardConfig = wireGuardConfig,
        provider = ExitProvider.OCI,
        endpointHost = publicIp,
        endpointPort = wireGuardPort,
        instanceId = resourceIds?.instanceId,
        sshUsername = sshUsername,
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
    )

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
        _state.value = ProvisioningState.Success(
            publicIp = selected.publicIp,
            wireGuardPort = selected.wireGuardPort,
            region = selected.region,
            isDevMode = _isDevMode.value,
        )
    }

    private fun updateExit(exitId: String, transform: (ConfiguredExit) -> ConfiguredExit) {
        _configuredExits.value = _configuredExits.value.map { exit ->
            if (exit.id == exitId) transform(exit) else exit
        }
        persistState()
    }

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
        .put("wireGuardConfig", wireGuardConfig)
        .put("endpointHost", endpointHost)
        .put("endpointPort", endpointPort)
        .put("compartmentId", compartmentId)
        .put("instanceId", instanceId)
        .put("sshUsername", sshUsername)
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
        .put("ociResourceIds", ociResourceIds?.toJson())

    private fun OciResourceIds.toJson(): JSONObject = JSONObject()
        .put("vcnId", vcnId)
        .put("securityListId", securityListId)
        .put("subnetId", subnetId)
        .put("internetGatewayId", internetGatewayId)
        .put("instanceId", instanceId)

    private fun JSONObject.toConfiguredExit(): ConfiguredExit {
        val resourceJson = optJSONObject("ociResourceIds")
        return ConfiguredExit(
            id = getString("id"),
            name = optString("name").takeIf { it.isNotBlank() } ?: "Exit 1",
            provider = runCatching { ExitProvider.valueOf(optString("provider", ExitProvider.OCI.name)) }.getOrDefault(ExitProvider.OCI),
            publicIp = getString("publicIp"),
            wireGuardPort = optInt("wireGuardPort", 51820),
            region = optString("region", LEGACY_REGION_FALLBACK),
            wireGuardConfig = getString("wireGuardConfig"),
            endpointHost = optString("endpointHost").takeIf { it.isNotBlank() } ?: getString("publicIp"),
            endpointPort = optInt("endpointPort", optInt("wireGuardPort", 51820)),
            compartmentId = optNullableString("compartmentId"),
            instanceId = optNullableString("instanceId"),
            sshUsername = optNullableString("sshUsername"),
            sshPrivateKey = optNullableString("sshPrivateKey"),
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
        )
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (has(name) && !isNull(name)) optLong(name) else null

    private fun JSONObject.optBooleanOrNull(name: String): Boolean? =
        if (has(name) && !isNull(name)) optBoolean(name) else null

    companion object {
        const val ORACLE_SIGNUP_URL = "https://signup.oraclecloud.com/"
        // Only for old persisted exits created before region was stored per exit.
        private const val LEGACY_REGION_FALLBACK = "uk-london-1"
        private const val AUTH_BOOTSTRAP_REGION = "us-ashburn-1"
    }
}
