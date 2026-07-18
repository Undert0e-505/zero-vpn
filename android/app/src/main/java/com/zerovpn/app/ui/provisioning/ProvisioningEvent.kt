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
    val developerOnly: Boolean = false,
)

enum class Phase(val number: Int, val label: String, val isPrivateChat: Boolean = false) {
    AUTH(1, "Browser auth"),
    API_KEY(2, "API key setup"),
    NETWORK(3, "Network creation"),
    VM_LAUNCH(4, "VM launch"),
    WAIT_SSH(5, "SSH connection"),
    WIREGUARD(6, "WireGuard setup"),
    PRIVATE_CHAT_PRECHECK(7, "Private Chat precheck", true),
    PRIVATE_CHAT_PACKAGES(8, "Private Chat packages", true),
    PRIVATE_CHAT_POSTGRES(9, "Private Chat PostgreSQL", true),
    PRIVATE_CHAT_SYNAPSE(10, "Private Chat Synapse", true),
    PRIVATE_CHAT_TLS(11, "Private Chat TLS", true),
    PRIVATE_CHAT_FIREWALL(12, "Private Chat firewall", true),
    PRIVATE_CHAT_OWNER_ACCOUNT(13, "Private Chat owner account", true),
    PRIVATE_CHAT_HEALTH(14, "Private Chat health", true),
    PRIVATE_CHAT_ENCRYPTION_SELF_TEST(15, "Private Chat encrypted self-test", true),
    PRIVATE_CHAT_COMPLETE(16, "Private Chat complete", true),
    DONE(17, "Complete");
}

enum class Status { RUNNING, SUCCESS, WARNING, ERROR }

enum class OracleOnboardingState {
    NotStarted,
    SignupLaunched,
    WaitingForAccountSetup,
    ReadyToAuthenticate,
    AuthLaunched,
    WaitingForAuthReturn,
    AuthReturned,
    AuthFailed,
    ReadyToProvision,
}

/**
 * Top-level state for the provisioning flow.
 */
sealed class ProvisioningState {
    data object Idle : ProvisioningState()
    data object PreStart : ProvisioningState()
    data object RegionSelectionRequired : ProvisioningState()
    data object Running : ProvisioningState()
    data class UkWarning(val homeRegion: String) : ProvisioningState()
    data class Success(
        val publicIp: String,
        val wireGuardPort: Int,
        val region: String,
        val isDevMode: Boolean,
        val privateChatStatus: com.zerovpn.app.chat.node.PrivateChatInstallStatus? = null,
        val privateChatError: String? = null,
    ) : ProvisioningState()
    data class Failure(
        val failedPhase: Phase,
        val lastSuccessPhase: Phase?,
        val errorMessage: String?,
    ) : ProvisioningState()
    data object Destroying : ProvisioningState()
    data object Destroyed : ProvisioningState()
}
