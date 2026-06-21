package com.zerovpn.app.vpn

enum class ExitProvider {
    OCI,
}

enum class ExitLifecycleState {
    PROVISIONING,
    READY,
    DESTROYING,
    FAILED,
}

data class OciResourceIds(
    val vcnId: String? = null,
    val securityListId: String? = null,
    val subnetId: String? = null,
    val internetGatewayId: String? = null,
    val instanceId: String? = null,
)

data class ConfiguredExit(
    val id: String,
    val name: String,
    val publicIp: String,
    val wireGuardPort: Int,
    val region: String,
    val wireGuardConfig: String,
    val provider: ExitProvider = ExitProvider.OCI,
    val endpointHost: String = publicIp,
    val endpointPort: Int = wireGuardPort,
    val compartmentId: String? = null,
    val instanceId: String? = null,
    val sshUsername: String? = null,
    val sshPrivateKey: String? = null,
    val apiKeyUserOcid: String? = null,
    val apiKeyTenancyOcid: String? = null,
    val apiKeyFingerprint: String? = null,
    val lifecycleState: ExitLifecycleState = ExitLifecycleState.READY,
    val createdAt: Long = System.currentTimeMillis(),
    val lastConnectedAt: Long? = null,
    val lastError: String? = null,
    val ociResourceIds: OciResourceIds? = null,
    val serverPublicKey: String? = null,
    val serverPeerPublicKey: String? = null,
    val clientPublicKey: String? = null,
)
