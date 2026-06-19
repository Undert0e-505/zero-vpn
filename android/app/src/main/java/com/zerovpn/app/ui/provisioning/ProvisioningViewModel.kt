package com.zerovpn.app.ui.provisioning

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerovpn.app.oci.OciProvisioner
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

    private var provisioner: OciProvisioner? = null
    private var authResult: OciProvisioner.AuthResult? = null
    private var preflightResult: OciProvisioner.PreflightResult? = null
    private var resourceIds: OciProvisioner.ResourceIds? = null
    private var clientConfig: String? = null
    private var homeRegion: String? = null

    // State persistence
    private lateinit var prefs: SharedPreferences

    fun initPrefs(context: Context) {
        prefs = context.getSharedPreferences("zerovpn_provisioning", Context.MODE_PRIVATE)
        loadPersistedState()
    }

    private fun loadPersistedState() {
        if (!::prefs.isInitialized) return
        val stateStr = prefs.getString("state", null)
        if (stateStr != null) {
            _isDevMode.value = prefs.getBoolean("is_dev_mode", true)
            homeRegion = prefs.getString("home_region", null)
            // Don't restore Running state — if we were mid-provision, user needs to retry
        }
    }

    private fun persistState() {
        if (!::prefs.isInitialized) return
        prefs.edit().apply {
            putString("state", _state.value::class.simpleName)
            putBoolean("is_dev_mode", _isDevMode.value)
            homeRegion?.let { putString("home_region", it) }
            _publicIp.value?.let { putString("public_ip", it) }
            putInt("wireguard_port", _wireGuardPort.value)
            // No secrets stored
        }.apply()
    }

    fun showPreStart() {
        _state.value = ProvisioningState.PreStart
    }

    fun cancel() {
        _state.value = ProvisioningState.Idle
        _events.value = emptyList()
        _currentPhase.value = null
        _publicIp.value = null
    }

    fun startProvisioning(context: Context) {
        if (_state.value is ProvisioningState.Running) return
        _events.value = emptyList()
        _state.value = ProvisioningState.Running
        viewModelScope.launch {
            runProvisioning(context)
        }
    }

    fun retry(context: Context) {
        _events.value = emptyList()
        _currentPhase.value = null
        _publicIp.value = null
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
        val currentRids = resourceIds ?: return
        val currentAuth = authResult ?: return
        val currentRegion = homeRegion ?: "uk-london-1"

        _state.value = ProvisioningState.Destroying
        viewModelScope.launch {
            provisioner?.let { prov ->
                emit(Phase.DONE, Status.RUNNING, "Destroying node resources...")
                try {
                    prov.destroy(currentRids, currentAuth, currentRegion)
                    emit(Phase.DONE, Status.SUCCESS, "Node destroyed. Resources released.")
                } catch (e: Exception) {
                    emit(Phase.DONE, Status.WARNING, "Destroy partial: ${e.message}")
                }
            }
            _state.value = ProvisioningState.Destroyed
            _events.value = emptyList()
            _currentPhase.value = null
            _publicIp.value = null
            resourceIds = null
            persistState()
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
                    provisioner?.destroy(currentRids, currentAuth, currentRegion)
                    emit(Phase.DONE, Status.SUCCESS, "Cleanup complete.")
                } catch (e: Exception) {
                    emit(Phase.DONE, Status.WARNING, "Cleanup partial: ${e.message}")
                }
            }
            _state.value = ProvisioningState.Idle
            _events.value = emptyList()
            _currentPhase.value = null
            _publicIp.value = null
            resourceIds = null
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

            val (rids, result) = prov.provision(auth, preflight)
            resourceIds = rids
            clientConfig = result.clientConfig
            _publicIp.value = result.publicIp
            _wireGuardPort.value = result.wireGuardPort

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
}