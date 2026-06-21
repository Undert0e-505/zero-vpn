package com.zerovpn.app.ui.provisioning

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerovpn.app.oci.OciProvisioner
import com.zerovpn.app.vpn.ConfiguredExit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    private val _isDevMode = MutableStateFlow(true)
    val isDevMode: StateFlow<Boolean> = _isDevMode.asStateFlow()

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

    // State persistence
    private lateinit var prefs: SharedPreferences
    private var prefsLoaded = false

    fun initPrefs(context: Context) {
        if (prefsLoaded) return
        prefs = context.getSharedPreferences("zerovpn_provisioning", Context.MODE_PRIVATE)
        loadPersistedState()
        prefsLoaded = true
    }

    private fun loadPersistedState() {
        if (!::prefs.isInitialized) return
        _isDevMode.value = prefs.getBoolean("is_dev_mode", true)
        val stateStr = prefs.getString("state", null)
        if (stateStr != null) {
            homeRegion = prefs.getString("home_region", null)
            apiKeyUserOcid = prefs.getString("api_key_user_ocid", null)
            apiKeyTenancyOcid = prefs.getString("api_key_tenancy_ocid", null)
            apiKeyFingerprint = prefs.getString("api_key_fingerprint", null)
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
                        publicIp = savedIp,
                        wireGuardPort = savedPort,
                        region = homeRegion ?: "uk-london-1",
                        wireGuardConfig = it,
                    )
                    _configuredExits.value = listOf(exit)
                    _selectedExitId.value = prefs.getString("selected_exit_id", exit.id) ?: exit.id
                }
                _state.value = ProvisioningState.Success(
                    publicIp = savedIp,
                    wireGuardPort = savedPort,
                    region = homeRegion ?: "uk-london-1",
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
            putBoolean("is_dev_mode", _isDevMode.value)
            homeRegion?.let { putString("home_region", it) }
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
            resourceIds?.let { rids ->
                rids.vcnId?.let { putString("resource_vcn_id", it) }
                rids.slId?.let { putString("resource_sl_id", it) }
                rids.subnetId?.let { putString("resource_subnet_id", it) }
                rids.igwId?.let { putString("resource_igw_id", it) }
                rids.instanceId?.let { putString("resource_instance_id", it) }
            }
            // No secrets stored
        }.apply()
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
    }

    fun prepareNewProvisioningFlow() {
        if (_state.value is ProvisioningState.Success || _state.value is ProvisioningState.Running || _state.value is ProvisioningState.Destroying) {
            return
        }
        _events.value = emptyList()
        _currentPhase.value = null
        _publicIp.value = null
        _wireGuardPort.value = 51820
        _configuredExits.value = emptyList()
        _selectedExitId.value = null
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
        _state.value = ProvisioningState.PreStart
    }

    fun cancel() {
        _state.value = ProvisioningState.Idle
        _events.value = emptyList()
        _currentPhase.value = null
        _publicIp.value = null
        _configuredExits.value = emptyList()
        _selectedExitId.value = null
        _sshDebugInfo.value = null
        clientConfig = null
        wireGuardClientPublicKey = null
        wireGuardServerPublicKey = null
        wireGuardServerPeerPublicKey = null
        persistState()
    }

    fun selectExit(exitId: String?) {
        _selectedExitId.value = exitId?.takeIf { id -> _configuredExits.value.any { it.id == id } }
        persistState()
    }

    fun setDevMode(enabled: Boolean) {
        _isDevMode.value = enabled
        persistState()
    }

    fun startProvisioning(context: Context) {
        if (_state.value is ProvisioningState.Running) return
        // Read the latest dev mode setting from SharedPreferences
        if (::prefs.isInitialized) {
            _isDevMode.value = prefs.getBoolean("is_dev_mode", true)
        }
        _events.value = emptyList()
        _sshDebugInfo.value = null
        clientConfig = null
        wireGuardClientPublicKey = null
        wireGuardServerPublicKey = null
        wireGuardServerPeerPublicKey = null
        _state.value = ProvisioningState.Running
        viewModelScope.launch {
            runProvisioning(context)
        }
    }

    fun retry(context: Context) {
        if (::prefs.isInitialized) {
            _isDevMode.value = prefs.getBoolean("is_dev_mode", true)
        }
        _events.value = emptyList()
        _currentPhase.value = null
        _publicIp.value = null
        _sshDebugInfo.value = null
        clientConfig = null
        wireGuardClientPublicKey = null
        wireGuardServerPublicKey = null
        wireGuardServerPeerPublicKey = null
        _state.value = ProvisioningState.Running
        viewModelScope.launch {
            runProvisioning(context)
        }
    }

    fun continueAfterUkWarning(context: Context) {
        _state.value = ProvisioningState.Running
        viewModelScope.launch {
            continueProvisioningAfterWarning(context)
        }
    }

    fun destroyNode(context: Context) {
        _state.value = ProvisioningState.Destroying
        viewModelScope.launch {
            val currentRids = resourceIds ?: loadResourceIds()
            val currentRegion = homeRegion ?: "uk-london-1"

            if (currentRids == null) {
                _state.value = ProvisioningState.Failure(
                    failedPhase = Phase.DONE,
                    lastSuccessPhase = null,
                    errorMessage = "Local resource IDs are missing. Delete the node resources manually in the Oracle Console.",
                )
                return@launch
            }

            val prov = provisioner ?: OciProvisioner(context, currentRegion, _isDevMode.value).also { provisioner = it }
            val eventJob = viewModelScope.launch {
                prov.events.collect { event ->
                    _events.value = _events.value + event
                    if (event.phase != Phase.DONE) {
                        _currentPhase.value = event.phase
                    }
                }
            }

            try {
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
                    apiKeyUserOcid = apiKeyUserOcid,
                    apiKeyFingerprint = apiKeyFingerprint,
                )
                emit(Phase.DONE, Status.SUCCESS, "Node destroyed. Resources released.")
                _state.value = ProvisioningState.Destroyed
                _events.value = emptyList()
                _currentPhase.value = null
                _publicIp.value = null
                _configuredExits.value = emptyList()
                _selectedExitId.value = null
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
                    prefs.edit().clear().apply()
                }
            } catch (e: Exception) {
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
            val currentRegion = homeRegion ?: "uk-london-1"

            if (currentRids != null && currentAuth != null) {
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
            }
            _state.value = ProvisioningState.Idle
            _events.value = emptyList()
            _currentPhase.value = null
            _publicIp.value = null
            _configuredExits.value = emptyList()
            _selectedExitId.value = null
            _sshDebugInfo.value = null
            resourceIds = null
            clientConfig = null
            wireGuardClientPublicKey = null
            wireGuardServerPublicKey = null
            wireGuardServerPeerPublicKey = null
            persistState()
        }
    }

    private suspend fun runProvisioning(context: Context) {
        try {
            provisioner = OciProvisioner(context, "uk-london-1", _isDevMode.value)

            // Collect events from provisioner
            val prov = provisioner!!
            val eventJob = viewModelScope.launch {
                prov.events.collect { event ->
                    _events.value = _events.value + event
                    if (event.phase != Phase.DONE) {
                        _currentPhase.value = event.phase
                    }
                }
            }

            // Phase 1: Auth
            _currentPhase.value = Phase.AUTH
            authResult = prov.authenticate()

            // Phase 2: Preflight
            _currentPhase.value = Phase.API_KEY
            preflightResult = prov.preflight(authResult!!)

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
            if (preflightResult!!.isUkRegion && _isDevMode.value) {
                eventJob.cancel()
                homeRegion = preflightResult!!.homeRegion
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
            persistState()
        }
    }

    private suspend fun continueProvisioningAfterWarning(context: Context) {
        try {
            val prov = provisioner ?: OciProvisioner(context, "uk-london-1", _isDevMode.value).also { provisioner = it }
            val auth = authResult ?: return

            val eventJob = viewModelScope.launch {
                prov.events.collect { event ->
                    _events.value = _events.value + event
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
                publicIp = result.publicIp,
                wireGuardPort = result.wireGuardPort,
                region = homeRegion ?: "uk-london-1",
                wireGuardConfig = result.clientConfig,
            )
            _configuredExits.value = listOf(configuredExit)
            _selectedExitId.value = configuredExit.id

            _currentPhase.value = Phase.DONE
            _state.value = ProvisioningState.Success(
                publicIp = result.publicIp,
                wireGuardPort = result.wireGuardPort,
                region = homeRegion ?: "uk-london-1",
                isDevMode = _isDevMode.value,
            )
            persistState()
        } catch (e: Exception) {
            _state.value = ProvisioningState.Failure(
                failedPhase = _currentPhase.value ?: Phase.AUTH,
                lastSuccessPhase = null,
                errorMessage = e.message,
            )
            persistState()
        }
    }

    private fun emit(phase: Phase, status: Status, message: String) {
        val event = ProvisioningEvent(
            timestamp = System.currentTimeMillis(),
            phase = phase,
            status = status,
            message = message,
        )
        _events.value = _events.value + event
    }

    private fun buildConfiguredExit(
        publicIp: String,
        wireGuardPort: Int,
        region: String,
        wireGuardConfig: String,
    ): ConfiguredExit = ConfiguredExit(
        id = "oracle:$publicIp:$wireGuardPort",
        name = "Oracle Cloud Exit",
        publicIp = publicIp,
        wireGuardPort = wireGuardPort,
        region = region,
        wireGuardConfig = wireGuardConfig,
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
}
