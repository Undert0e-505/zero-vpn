package com.zerovpn.app.ui.provisioning

/**
 * A single event in the provisioning pipeline log.
 * No secrets are stored in or displayed from this model.
 */
data class ProvisioningEvent(
    val timestamp: Long,           // epoch millis
    val phase: Phase,              // enum: AUTH, API_KEY, NETWORK, VM_LAUNCH, WAIT_SSH, WIREGUARD, DONE
    val status: Status,            // enum: RUNNING, SUCCESS, WARNING, ERROR
    val message: String,           // user-safe message (no secrets)
    val technicalDetail: String? = null,  // redacted technical detail (no secrets)
)

enum class Phase(val number: Int, val label: String) {
    AUTH(1, "Browser auth"),
    API_KEY(2, "API key setup"),
    NETWORK(3, "Network creation"),
    VM_LAUNCH(4, "VM launch"),
    WAIT_SSH(5, "SSH connection"),
    WIREGUARD(6, "WireGuard setup"),
    DONE(7, "Complete");
}

enum class Status { RUNNING, SUCCESS, WARNING, ERROR }

/**
 * Top-level state for the provisioning flow.
 */
sealed class ProvisioningState {
    data object Idle : ProvisioningState()
    data object PreStart : ProvisioningState()
    data object Running : ProvisioningState()
    data class UkWarning(val homeRegion: String) : ProvisioningState()
    data class Success(
        val publicIp: String,
        val wireGuardPort: Int,
        val region: String,
        val isDevMode: Boolean,
    ) : ProvisioningState()
    data class Failure(
        val failedPhase: Phase,
        val lastSuccessPhase: Phase?,
        val errorMessage: String?,
    ) : ProvisioningState()
    data object Destroying : ProvisioningState()
    data object Destroyed : ProvisioningState()
}