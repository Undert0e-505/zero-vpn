package com.zerovpn.app.vpn

sealed class ExitIpStatus {
    data object Idle : ExitIpStatus()
    data object Loading : ExitIpStatus()
    data object Available : ExitIpStatus()
    data object Failed : ExitIpStatus()
    data object NotConnected : ExitIpStatus()
}

sealed class DnsLeakStatus {
    data object Idle : DnsLeakStatus()
    data object Loading : DnsLeakStatus()
    data object Passed : DnsLeakStatus()
    data object Warning : DnsLeakStatus()
    data object Failed : DnsLeakStatus()
    data object Unknown : DnsLeakStatus()
    data object NotConnected : DnsLeakStatus()
}

sealed class LastHandshakeStatus {
    data object Available : LastHandshakeStatus()
    data object Unavailable : LastHandshakeStatus()
    data object NotConnected : LastHandshakeStatus()
}

data class UserDiagnosticsState(
    val exitIpStatus: ExitIpStatus = ExitIpStatus.Idle,
    val exitIp: String? = null,
    val exitCountry: String? = null,
    val exitProviderOrAsn: String? = null,
    val dnsLeakStatus: DnsLeakStatus = DnsLeakStatus.Idle,
    val dnsResolverSummary: String? = null,
    val dnsLeakDetail: String? = null,
    val lastHandshakeStatus: LastHandshakeStatus = LastHandshakeStatus.Unavailable,
    val lastHandshakeText: String? = null,
    val lastUpdated: Long? = null,
    val userVisibleError: String? = null,
)
