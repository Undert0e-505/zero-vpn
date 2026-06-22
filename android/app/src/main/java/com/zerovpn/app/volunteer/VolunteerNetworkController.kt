package com.zerovpn.app.volunteer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class VolunteerNetworkController(application: Application) : AndroidViewModel(application) {
    private val embeddedTorController = EmbeddedTorController(application)
    private val socksProbe = TorSocksProbe()
    private var runJob: Job? = null

    private val _state = MutableStateFlow<VolunteerNetworkState>(VolunteerNetworkState.Idle)
    val state: StateFlow<VolunteerNetworkState> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow(VolunteerNetworkDiagnostics())
    val diagnostics: StateFlow<VolunteerNetworkDiagnostics> = _diagnostics.asStateFlow()

    fun startTest() {
        val currentState = _state.value
        if (
            currentState is VolunteerNetworkState.StartingTor ||
            currentState is VolunteerNetworkState.BootstrappingTor ||
            currentState is VolunteerNetworkState.SocksReady ||
            currentState is VolunteerNetworkState.TestingSocks ||
            currentState is VolunteerNetworkState.Ready ||
            currentState is VolunteerNetworkState.Stopping
        ) {
            return
        }

        runJob?.cancel()
        runJob = viewModelScope.launch {
            _state.value = VolunteerNetworkState.StartingTor
            _diagnostics.value = VolunteerNetworkDiagnostics(
                startedAt = System.currentTimeMillis(),
                lastTestUrl = TorSocksProbe.DEFAULT_TEST_URL,
            )

            try {
                val socksPort = embeddedTorController.start { progress ->
                    _state.value = VolunteerNetworkState.BootstrappingTor(progress)
                    _diagnostics.value = _diagnostics.value.copy(bootstrapProgress = progress)
                }
                _state.value = VolunteerNetworkState.SocksReady
                _diagnostics.value = _diagnostics.value.copy(socksPort = socksPort)

                _state.value = VolunteerNetworkState.TestingSocks
                val result = socksProbe.run(
                    socksHost = _diagnostics.value.socksHost,
                    socksPort = socksPort,
                )
                _diagnostics.value = _diagnostics.value.copy(
                    lastTestUrl = result.url,
                    lastTestStatus = result.status,
                    lastExitIp = result.exitIp,
                    lastError = null,
                )
                _state.value = VolunteerNetworkState.Ready
            } catch (e: CancellationException) {
                embeddedTorController.stop()
                throw e
            } catch (e: Exception) {
                _diagnostics.value = _diagnostics.value.copy(
                    lastError = e.message ?: e.javaClass.simpleName,
                    stoppedAt = System.currentTimeMillis(),
                )
                _state.value = VolunteerNetworkState.Failed(
                    message = e.message ?: "Embedded Volunteer Network test failed.",
                    throwableClass = e.javaClass.simpleName,
                )
                embeddedTorController.stop()
            }
        }
    }

    fun stopTest() {
        val currentState = _state.value
        if (currentState is VolunteerNetworkState.Idle || currentState is VolunteerNetworkState.Stopped) {
            return
        }
        runJob?.cancel()
        runJob = viewModelScope.launch {
            _state.value = VolunteerNetworkState.Stopping
            embeddedTorController.stop()
            _diagnostics.value = _diagnostics.value.copy(stoppedAt = System.currentTimeMillis())
            _state.value = VolunteerNetworkState.Stopped
        }
    }

    override fun onCleared() {
        super.onCleared()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            embeddedTorController.stop()
        }
    }
}
