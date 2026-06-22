package com.zerovpn.app.volunteer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class VolunteerNetworkController(application: Application) : AndroidViewModel(application) {
    private val embeddedTorController = EmbeddedTorController(application)
    private val socksProbe = TorSocksProbe()
    private var runJob: Job? = null
    private var bootstrapAttempts = 0
    private var lastSuccessfulBootstrapAt: Long? = null

    private val _state = MutableStateFlow<VolunteerNetworkState>(VolunteerNetworkState.Idle)
    val state: StateFlow<VolunteerNetworkState> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow(VolunteerNetworkDiagnostics())
    val diagnostics: StateFlow<VolunteerNetworkDiagnostics> = _diagnostics.asStateFlow()

    fun startTest() {
        val currentState = _state.value
        if (!currentState.canStart) return

        runJob?.cancel()
        runJob = viewModelScope.launch {
            val snapshot = embeddedTorController.snapshot()
            val coldStart = !snapshot.stateDirectoryExists
            val timeoutMs = if (coldStart) {
                EmbeddedTorController.COLD_BOOTSTRAP_TIMEOUT_MS
            } else {
                EmbeddedTorController.WARM_BOOTSTRAP_TIMEOUT_MS
            }
            bootstrapAttempts += 1
            val attempt = bootstrapAttempts

            _state.value = VolunteerNetworkState.StartingTor
            _diagnostics.value = VolunteerNetworkDiagnostics(
                startedAt = System.currentTimeMillis(),
                bootstrapAttempt = attempt,
                coldStart = coldStart,
                timeoutMs = timeoutMs,
                lastSuccessfulBootstrapAt = lastSuccessfulBootstrapAt,
                torStateDirectoryExisted = snapshot.stateDirectoryExists,
                torCacheDirectoryExisted = snapshot.cacheDirectoryExists,
                torServiceBound = snapshot.torServiceBound,
                torRunning = snapshot.torRunning.toString(),
                torServiceStatus = snapshot.latestStatus,
                lastTestUrl = TorSocksProbe.DEFAULT_TEST_URL,
            )

            try {
                val startResult = embeddedTorController.start(timeoutMs) { update ->
                    _state.value = VolunteerNetworkState.BootstrappingTor(update.progress)
                    _diagnostics.value = _diagnostics.value.copy(
                        bootstrapProgress = update.progress,
                        bootstrapPhase = update.phase,
                        bootstrapSummary = update.summary,
                        lastBootstrapMessage = update.message,
                    )
                }
                lastSuccessfulBootstrapAt = startResult.bootstrapCompletedAt
                _state.value = VolunteerNetworkState.SocksReady
                _diagnostics.value = _diagnostics.value.copy(
                    socksPort = startResult.socksPort,
                    socksActive = true,
                    bootstrapProgress = startResult.bootstrapUpdate.progress,
                    bootstrapPhase = startResult.bootstrapUpdate.phase,
                    bootstrapSummary = startResult.bootstrapUpdate.summary,
                    lastBootstrapMessage = startResult.bootstrapUpdate.message,
                    bootstrapStartedAt = startResult.bootstrapStartedAt,
                    bootstrapCompletedAt = startResult.bootstrapCompletedAt,
                    bootstrapDurationMs = startResult.bootstrapDurationMs,
                    coldStart = startResult.coldStart,
                    timeoutMs = startResult.timeoutMs,
                    torStateDirectoryExisted = startResult.stateDirectoryExisted,
                    torCacheDirectoryExisted = startResult.cacheDirectoryExisted,
                    lastSuccessfulBootstrapAt = lastSuccessfulBootstrapAt,
                    torServiceBound = true,
                    torRunning = "true",
                    torServiceStatus = "ON",
                    lastError = null,
                )

                _state.value = VolunteerNetworkState.TestingSocks
                val result = socksProbe.run(
                    socksHost = _diagnostics.value.socksHost,
                    socksPort = startResult.socksPort,
                )
                _diagnostics.value = _diagnostics.value.copy(
                    bootstrapProgress = 100,
                    bootstrapPhase = "done",
                    bootstrapSummary = "Done",
                    lastBootstrapMessage = "Bootstrapped 100%: Done",
                    lastTestUrl = result.url,
                    lastTestStatus = result.status,
                    lastExitIp = result.exitIp,
                    lastError = null,
                )
                _state.value = VolunteerNetworkState.Ready
            } catch (e: TimeoutCancellationException) {
                val stopped = embeddedTorController.stop()
                val message = "Embedded Tor bootstrap timed out after ${timeoutMs}ms " +
                    "(${if (coldStart) "cold" else "warm"} start, attempt $attempt)."
                _diagnostics.value = _diagnostics.value.copy(
                    socksActive = false,
                    stopRequestedAt = stopped.stopRequestedAt,
                    stoppedAt = stopped.stoppedAt,
                    stopDurationMs = stopped.stopDurationMs,
                    torServiceBound = stopped.torServiceBound,
                    torRunning = stopped.torRunningText(),
                    torServiceStatus = stopped.statusText(),
                    effectiveStopped = stopped.effectiveStopped,
                    lastError = message,
                )
                _state.value = VolunteerNetworkState.Failed(
                    message = message,
                    throwableClass = e.javaClass.simpleName,
                )
            } catch (e: CancellationException) {
                embeddedTorController.stop()
                throw e
            } catch (e: Exception) {
                val stopped = embeddedTorController.stop()
                _diagnostics.value = _diagnostics.value.copy(
                    socksActive = false,
                    stopRequestedAt = stopped.stopRequestedAt,
                    stoppedAt = stopped.stoppedAt,
                    stopDurationMs = stopped.stopDurationMs,
                    torServiceBound = stopped.torServiceBound,
                    torRunning = stopped.torRunningText(),
                    torServiceStatus = stopped.statusText(),
                    effectiveStopped = stopped.effectiveStopped,
                    lastError = e.message ?: e.javaClass.simpleName,
                )
                _state.value = VolunteerNetworkState.Failed(
                    message = e.message ?: "Embedded Volunteer Network test failed.",
                    throwableClass = e.javaClass.simpleName,
                )
            }
        }
    }

    fun stopTest() {
        val currentState = _state.value
        if (!currentState.canStop) return

        val socksPort = _diagnostics.value.socksPort
        runJob?.cancel()
        runJob = viewModelScope.launch {
            _state.value = VolunteerNetworkState.Stopping
            val stopResult = embeddedTorController.stop()
            val afterStopProbe = socksPort?.let { port ->
                runCatching {
                    socksProbe.run(
                        socksHost = _diagnostics.value.socksHost,
                        socksPort = port,
                    )
                    "Unexpected success"
                }.getOrElse { e ->
                    "Failed as expected: ${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                }
            } ?: "Skipped: no SOCKS port"
            val effectiveStopped = stopResult.effectiveStopped(afterStopProbe)
            _diagnostics.value = _diagnostics.value.copy(
                socksActive = false,
                stopRequestedAt = stopResult.stopRequestedAt,
                stoppedAt = stopResult.stoppedAt,
                stopDurationMs = stopResult.stopDurationMs,
                torServiceBound = stopResult.torServiceBound,
                torRunning = stopResult.torRunningText(effectiveStopped),
                torServiceStatus = stopResult.statusText(effectiveStopped),
                effectiveStopped = effectiveStopped,
                socksProbeAfterStop = afterStopProbe,
            )
            _state.value = VolunteerNetworkState.Stopped
        }
    }

    fun clearTorState() {
        val currentState = _state.value
        if (currentState is VolunteerNetworkState.StartingTor ||
            currentState is VolunteerNetworkState.BootstrappingTor ||
            currentState is VolunteerNetworkState.TestingSocks ||
            currentState is VolunteerNetworkState.Stopping
        ) {
            return
        }

        runJob?.cancel()
        runJob = viewModelScope.launch {
            _state.value = VolunteerNetworkState.Stopping
            val stopped = embeddedTorController.stop()
            val cleared = embeddedTorController.clearState()
            val clearStatus = "Clear Tor state: stateExisted=${cleared.stateDirectoryExisted}, " +
                "cacheExisted=${cleared.cacheDirectoryExisted}, " +
                "stateDeleted=${cleared.stateDirectoryDeleted}, " +
                "cacheDeleted=${cleared.cacheDirectoryDeleted}"
            _diagnostics.value = VolunteerNetworkDiagnostics(
                bootstrapAttempt = bootstrapAttempts,
                lastSuccessfulBootstrapAt = lastSuccessfulBootstrapAt,
                stopRequestedAt = stopped.stopRequestedAt,
                stoppedAt = stopped.stoppedAt,
                stopDurationMs = stopped.stopDurationMs,
                torServiceBound = stopped.torServiceBound,
                torRunning = stopped.torRunningText(true),
                torServiceStatus = stopped.statusText(true),
                effectiveStopped = true,
                torStateDirectoryExisted = cleared.stateDirectoryExisted,
                torCacheDirectoryExisted = cleared.cacheDirectoryExisted,
                lastError = clearStatus,
            )
            _state.value = VolunteerNetworkState.Idle
        }
    }

    override fun onCleared() {
        super.onCleared()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            embeddedTorController.stop()
        }
    }

    private val VolunteerNetworkState.canStart: Boolean
        get() = this is VolunteerNetworkState.Idle ||
            this is VolunteerNetworkState.Stopped ||
            this is VolunteerNetworkState.Failed

    private val VolunteerNetworkState.canStop: Boolean
        get() = this !is VolunteerNetworkState.Idle &&
            this !is VolunteerNetworkState.Stopped &&
            this !is VolunteerNetworkState.Stopping

    private val EmbeddedTorController.StopResult.effectiveStopped: Boolean
        get() = !torServiceBound && !torRunning

    private fun EmbeddedTorController.StopResult.effectiveStopped(probeResult: String): Boolean =
        !torServiceBound && probeResult.startsWith("Failed as expected")

    private fun EmbeddedTorController.StopResult.torRunningText(
        effectiveStopped: Boolean = this.effectiveStopped,
    ): String = when {
        effectiveStopped -> "false"
        torRunning && latestStatus == "STOPPING" -> "unknown/stopping"
        torRunning -> "true"
        else -> "false"
    }

    private fun EmbeddedTorController.StopResult.statusText(
        effectiveStopped: Boolean = this.effectiveStopped,
    ): String? = when {
        effectiveStopped && latestStatus == "STOPPING" -> "STOPPING (effective stop)"
        else -> latestStatus
    }
}
