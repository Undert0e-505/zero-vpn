package com.zerovpn.app.volunteer.vpn

import java.text.DateFormat
import java.util.Date

data class VolunteerVpnDiagnostics(
    val volunteerVpnState: String = "Idle",
    val androidVpnPermission: String = "unknown",
    val vpnServiceStarted: Boolean = false,
    val tunCreated: Boolean = false,
    val tunFdOpen: Boolean = false,
    val tunAddress: String = "10.111.0.2/32",
    val routes: String = "0.0.0.0/0",
    val dnsServer: String = "198.18.0.2",
    val dnsMode: String = "hev-map-dns-through-socks",
    val dnsLeakRisk: String = "unknown",
    val udpMode: String = "unsupported-not-validated-tor-socks",
    val zeroVpnAppIncludedInVolunteerVpn: Boolean = false,
    val zeroVpnAppExcludedFromVolunteerVpn: Boolean = true,
    val appExclusionError: String? = null,
    val hevNativeEnabled: Boolean = false,
    val hevLibraryLoaded: Boolean = false,
    val hevStartResult: String? = null,
    val hevRunning: Boolean = false,
    val socksTarget: String = "127.0.0.1:9050",
    val torReady: Boolean = false,
    val androidVpnActiveDetected: Boolean = false,
    val hevConfigPath: String? = null,
    val hevConfigExists: Boolean? = null,
    val hevConfigSizeBytes: Long? = null,
    val hevConfigLastModified: Long? = null,
    val hevConfigContents: String? = null,
    val torSocksBaselineStatus: String? = null,
    val torSocksBaselineIsTor: Boolean? = null,
    val torSocksBaselineExitIp: String? = null,
    val inAppVpnValidationStatus: String? = null,
    val browserValidationInstruction: String = "Open browser to https://check.torproject.org/; if it says Tor, TUN -> HEV -> Tor works for other apps. In-app checks do not prove this path because ZeroVPN is excluded from the VPN.",
    val vpnDnsValidationStatus: String? = null,
    val vpnTcpValidationStatus: String? = null,
    val vpnTorValidationStatus: String? = null,
    val lastValidationUrl: String? = null,
    val lastValidationStatus: String? = null,
    val lastValidationIsTor: Boolean? = null,
    val lastValidationExitIp: String? = null,
    val txPackets: Long? = null,
    val txBytes: Long? = null,
    val rxPackets: Long? = null,
    val rxBytes: Long? = null,
    val lastError: String? = null,
    val startedAt: Long? = null,
    val stoppedAt: Long? = null,
    val stopDurationMs: Long? = null,
) {
    fun toDebugText(): String = buildString {
        appendLine("volunteerVpnState=$volunteerVpnState")
        appendLine("androidVpnPermission=$androidVpnPermission")
        appendLine("vpnServiceStarted=$vpnServiceStarted")
        appendLine("tunCreated=$tunCreated")
        appendLine("tunFdOpen=$tunFdOpen")
        appendLine("tunAddress=$tunAddress")
        appendLine("routes=$routes")
        appendLine("dnsServer=$dnsServer")
        appendLine("dnsMode=$dnsMode")
        appendLine("dnsLeakRisk=$dnsLeakRisk")
        appendLine("udpMode=$udpMode")
        appendLine("zeroVpnAppIncludedInVolunteerVpn=$zeroVpnAppIncludedInVolunteerVpn")
        appendLine("zeroVpnAppExcludedFromVolunteerVpn=$zeroVpnAppExcludedFromVolunteerVpn")
        appendLine("appExclusionError=${appExclusionError ?: "N/A"}")
        appendLine("hevNativeEnabled=$hevNativeEnabled")
        appendLine("hevLibraryLoaded=$hevLibraryLoaded")
        appendLine("hevStartResult=${hevStartResult ?: "N/A"}")
        appendLine("hevRunning=$hevRunning")
        appendLine("socksTarget=$socksTarget")
        appendLine("torReady=$torReady")
        appendLine("androidVpnActiveDetected=$androidVpnActiveDetected")
        appendLine("hevConfigPath=${hevConfigPath ?: "N/A"}")
        appendLine("hevConfigExists=${hevConfigExists?.toString() ?: "N/A"}")
        appendLine("hevConfigSizeBytes=${hevConfigSizeBytes ?: "N/A"}")
        appendLine("hevConfigLastModified=${hevConfigLastModified?.toDisplayTime() ?: "N/A"}")
        appendLine("torSocksBaselineStatus=${torSocksBaselineStatus ?: "N/A"}")
        appendLine("torSocksBaselineIsTor=${torSocksBaselineIsTor?.toString() ?: "N/A"}")
        appendLine("torSocksBaselineExitIp=${torSocksBaselineExitIp ?: "N/A"}")
        appendLine("inAppVpnValidationStatus=${inAppVpnValidationStatus ?: "N/A"}")
        appendLine("browserValidationInstruction=$browserValidationInstruction")
        appendLine("vpnDnsValidationStatus=${vpnDnsValidationStatus ?: "N/A"}")
        appendLine("vpnTcpValidationStatus=${vpnTcpValidationStatus ?: "N/A"}")
        appendLine("vpnTorValidationStatus=${vpnTorValidationStatus ?: "N/A"}")
        appendLine("lastValidationUrl=${lastValidationUrl ?: "N/A"}")
        appendLine("lastValidationStatus=${lastValidationStatus ?: "N/A"}")
        appendLine("lastValidationIsTor=${lastValidationIsTor?.toString() ?: "N/A"}")
        appendLine("lastValidationExitIp=${lastValidationExitIp ?: "N/A"}")
        appendLine("hevTxPackets=${txPackets ?: "N/A"}")
        appendLine("hevTxBytes=${txBytes ?: "N/A"}")
        appendLine("hevRxPackets=${rxPackets ?: "N/A"}")
        appendLine("hevRxBytes=${rxBytes ?: "N/A"}")
        appendLine("lastError=${lastError ?: "N/A"}")
        appendLine("startedAt=${startedAt?.toDisplayTime() ?: "N/A"}")
        appendLine("stoppedAt=${stoppedAt?.toDisplayTime() ?: "N/A"}")
        appendLine("stopDurationMs=${stopDurationMs ?: "N/A"}")
        appendLine("hevConfigContents=${hevConfigContents ?: "N/A"}")
    }.trimEnd()
}

private fun Long.toDisplayTime(): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(this))
