package com.zerovpn.app.friends

enum class InviteSlotState {
    UNUSED,
    PENDING_CLAIM,
    CLAIMED,
    REVOKED,
}

data class InviteSlot(
    val slotId: String,
    val ownerExitId: String,
    val slotIndex: Int,
    val displayName: String? = null,
    val state: InviteSlotState = InviteSlotState.UNUSED,
    val tunnelIp: String? = null,
    val peerPublicKey: String? = null,
    val clientConfigSecretKey: String? = null,
    val encryptedClientConfig: String? = null,
    val encryptedClientPrivateKey: String? = null,
    val qrShownAt: Long? = null,
    val firstHandshakeAt: Long? = null,
    val lastHandshakeAt: Long? = null,
    val revokedAt: Long? = null,
    val resetAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

enum class SharedExitSource {
    IMPORTED_QR,
    IMPORTED_CONFIG,
}

enum class SharedExitProviderType {
    SHARED_WIREGUARD,
}

data class SharedExitProfile(
    val id: String,
    val displayName: String,
    val source: SharedExitSource = SharedExitSource.IMPORTED_QR,
    val providerType: SharedExitProviderType = SharedExitProviderType.SHARED_WIREGUARD,
    val wireGuardConfigSecretKey: String? = null,
    val configHash: String? = null,
    val encryptedWireGuardConfig: String? = null,
    val endpointHost: String? = null,
    val endpointIp: String? = null,
    val importedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = importedAt,
    val lastConnectedAt: Long? = null,
    val renamedAt: Long? = null,
)
