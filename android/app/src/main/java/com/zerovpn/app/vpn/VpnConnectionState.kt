package com.zerovpn.app.vpn

sealed class VpnConnectionState {
    data object Disconnected : VpnConnectionState()
    data class PermissionRequired(val exitId: String) : VpnConnectionState()
    data class Connecting(val exitId: String) : VpnConnectionState()
    data class Connected(val exitId: String) : VpnConnectionState()
    data class ActiveUnknown(val tunnelName: String) : VpnConnectionState()
    data class Disconnecting(val exitId: String?) : VpnConnectionState()
    data class Failed(val message: String) : VpnConnectionState()
}

data class VpnDiagnostics(
    val backendState: String = "Disconnected",
    val selectedExitId: String? = null,
    val activeExitId: String? = null,
    val persistedActiveExitId: String? = null,
    val persistedTunnelName: String? = null,
    val persistedConnectedAt: Long? = null,
    val tunnelName: String? = null,
    val reconciliationSource: String? = null,
    val goBackendRunningTunnelNames: String? = null,
    val androidVpnDetected: Boolean = false,
    val androidVpnInterfaceName: String? = null,
    val androidVpnDnsServers: String? = null,
    val androidVpnRoutes: String? = null,
    val androidVpnLinkAddresses: String? = null,
    val configParseStatus: String? = null,
    val runtimeConfigRedacted: String? = null,
    val selectedExitPublicIp: String? = null,
    val tunnelAddress: String? = null,
    val endpoint: String? = null,
    val dns: String? = null,
    val allowedIps: String? = null,
    val persistentKeepalive: String? = null,
    val androidClientPublicKey: String? = null,
    val androidPeerServerPublicKey: String? = null,
    val selectedNodeServerPublicKey: String? = null,
    val provisionedServerPeerPublicKey: String? = null,
    val keyValidation: String? = null,
    val lastError: String? = null,
)
