package com.zerovpn.app.ui.provisioning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
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

    fun showPreStart() {
        _state.value = ProvisioningState.PreStart
    }

    fun cancel() {
        _state.value = ProvisioningState.Idle
        _events.value = emptyList()
        _currentPhase.value = null
        _publicIp.value = null
    }

    fun startProvisioning() {
        if (_state.value is ProvisioningState.Running) return
        _events.value = emptyList()
        _state.value = ProvisioningState.Running
        viewModelScope.launch {
            simulatePipeline()
        }
    }

    fun retry() {
        _events.value = emptyList()
        _currentPhase.value = null
        _publicIp.value = null
        _state.value = ProvisioningState.Running
        viewModelScope.launch {
            simulatePipeline()
        }
    }

    fun destroyNode() {
        viewModelScope.launch {
            emit(Phase.DONE, Status.RUNNING, "Destroying node resources...")
            delay(2000)
            emit(Phase.DONE, Status.SUCCESS, "Node destroyed. Resources released.")
            _state.value = ProvisioningState.Idle
            _events.value = emptyList()
            _currentPhase.value = null
            _publicIp.value = null
        }
    }

    fun cleanup() {
        viewModelScope.launch {
            emit(Phase.DONE, Status.RUNNING, "Cleaning up partial resources...")
            delay(1500)
            emit(Phase.DONE, Status.WARNING, "Cleanup attempted. Some resources may remain.")
            _state.value = ProvisioningState.Idle
            _events.value = emptyList()
            _currentPhase.value = null
            _publicIp.value = null
        }
    }

    private suspend fun simulatePipeline() {
        // Phase 1: Browser auth
        _currentPhase.value = Phase.AUTH
        emit(Phase.AUTH, Status.RUNNING, "Opening Oracle login...")
        delay(2000)
        emit(Phase.AUTH, Status.RUNNING, "Waiting for authentication...")
        delay(3000)
        emit(Phase.AUTH, Status.SUCCESS, "Authenticated")
        delay(500)

        // Phase 2: API key setup
        _currentPhase.value = Phase.API_KEY
        emit(Phase.API_KEY, Status.RUNNING, "Uploading API key...")
        delay(1500)
        emit(Phase.API_KEY, Status.RUNNING, "Waiting for propagation...")
        delay(2000)
        emit(Phase.API_KEY, Status.SUCCESS, "API key uploaded")
        delay(500)

        // Phase 3: Network creation
        _currentPhase.value = Phase.NETWORK
        emit(Phase.NETWORK, Status.RUNNING, "Creating VCN...")
        delay(1500)
        emit(Phase.NETWORK, Status.RUNNING, "Creating security list...")
        delay(1000)
        emit(Phase.NETWORK, Status.RUNNING, "Creating subnet...")
        delay(1000)
        emit(Phase.NETWORK, Status.RUNNING, "Creating internet gateway...")
        delay(1000)
        emit(Phase.NETWORK, Status.RUNNING, "Configuring route table...")
        delay(800)
        emit(Phase.NETWORK, Status.SUCCESS, "Network ready")
        delay(500)

        // Phase 4: VM launch
        _currentPhase.value = Phase.VM_LAUNCH
        emit(Phase.VM_LAUNCH, Status.RUNNING, "Launching instance (VM.Standard.E2.1.Micro)...")
        delay(3000)
        emit(Phase.VM_LAUNCH, Status.RUNNING, "Waiting for instance to be running...")
        delay(4000)
        emit(Phase.VM_LAUNCH, Status.RUNNING, "Allocating public IP...")
        delay(2000)
        val simulatedIp = "141.147.106.118"
        _publicIp.value = simulatedIp
        emit(Phase.VM_LAUNCH, Status.SUCCESS, "Instance running, public IP: $simulatedIp")
        delay(500)

        // Phase 5: SSH connection
        _currentPhase.value = Phase.WAIT_SSH
        emit(Phase.WAIT_SSH, Status.RUNNING, "Waiting for SSH (port 22)...")
        delay(3000)
        emit(Phase.WAIT_SSH, Status.RUNNING, "Attempting SSH connection...")
        delay(2000)
        emit(Phase.WAIT_SSH, Status.SUCCESS, "SSH connected")
        delay(500)

        // Phase 6: WireGuard setup
        _currentPhase.value = Phase.WIREGUARD
        emit(Phase.WIREGUARD, Status.RUNNING, "Installing WireGuard...")
        delay(2500)
        emit(Phase.WIREGUARD, Status.RUNNING, "Generating server keys...")
        delay(1500)
        emit(Phase.WIREGUARD, Status.RUNNING, "Configuring WireGuard interface...")
        delay(1000)
        emit(Phase.WIREGUARD, Status.RUNNING, "Generating client config...")
        delay(1000)
        emit(Phase.WIREGUARD, Status.SUCCESS, "WireGuard configured")
        delay(500)

        // Phase 7: Done
        _currentPhase.value = Phase.DONE
        emit(Phase.DONE, Status.SUCCESS, "Exit created: $simulatedIp:${_wireGuardPort.value}")
        _state.value = ProvisioningState.Success(simulatedIp, _wireGuardPort.value)
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