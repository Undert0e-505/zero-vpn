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
    val dnsMode: String = "not-safe-yet",
    val dnsLeakRisk: String = "unknown",
    val udpMode: String = "unsupported-tcp-only",
    val hevNativeEnabled: Boolean = false,
    val hevLibraryLoaded: Boolean = false,
    val hevRunning: Boolean = false,
    val socksTarget: String = "127.0.0.1:9050",
    val torReady: Boolean = false,
    val androidVpnActiveDetected: Boolean = false,
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
        appendLine("dnsMode=$dnsMode")
        appendLine("dnsLeakRisk=$dnsLeakRisk")
        appendLine("udpMode=$udpMode")
        appendLine("hevNativeEnabled=$hevNativeEnabled")
        appendLine("hevLibraryLoaded=$hevLibraryLoaded")
        appendLine("hevRunning=$hevRunning")
        appendLine("socksTarget=$socksTarget")
        appendLine("torReady=$torReady")
        appendLine("androidVpnActiveDetected=$androidVpnActiveDetected")
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
    }.trimEnd()
}

private fun Long.toDisplayTime(): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(this))
