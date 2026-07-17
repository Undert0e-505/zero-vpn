package com.zerovpn.app.chat.retry

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities

/**
 * Safe network diagnostics collected after a transient network failure.
 *
 * Does NOT log user traffic, VPN keys, Oracle credentials, or request signatures.
 */
data class NetworkDiagnostics(
    val activeNetworkAvailable: Boolean,
    val transport: String?,          // Wi-Fi / cellular / ethernet / VPN / unknown
    val validatedInternet: Boolean,
    val privateDnsMode: String?,     // from LinkProperties
    val dnsServers: List<String>,    // from LinkProperties
    val failedHostname: String?,
    val dnsExceptionClass: String?,
    val requestConstructionBegan: Boolean,
    val requestTransmissionBegan: Boolean,
    val consecutiveTransientFailures: Int,
)

/**
 * Collects [NetworkDiagnostics] using [ConnectivityManager].
 *
 * This collector only gathers network transport and DNS configuration metadata.
 * It does NOT inspect user traffic, VPN keys, Oracle credentials, or request signatures.
 */
class NetworkDiagnosticsCollector(private val context: Context) {

    fun collect(
        failedHostname: String? = null,
        dnsExceptionClass: String? = null,
        requestConstructionBegan: Boolean = false,
        requestTransmissionBegan: Boolean = false,
        consecutiveTransientFailures: Int = 0,
    ): NetworkDiagnostics {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val capabilities = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val linkProperties = activeNetwork?.let { cm.getLinkProperties(it) }

        val activeNetworkAvailable = capabilities != null
        val transport = capabilities?.let { caps ->
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "unknown"
            }
        }
        val validatedInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        val privateDnsMode = linkProperties?.privateDnsServerName ?: run {
            if (linkProperties?.isPrivateDnsActive == true) "opportunistic" else null
        }

        val dnsServers = linkProperties?.dnsServers
            ?.mapNotNull { it.hostAddress }
            ?: emptyList()

        return NetworkDiagnostics(
            activeNetworkAvailable = activeNetworkAvailable,
            transport = transport,
            validatedInternet = validatedInternet,
            privateDnsMode = privateDnsMode,
            dnsServers = dnsServers,
            failedHostname = failedHostname,
            dnsExceptionClass = dnsExceptionClass,
            requestConstructionBegan = requestConstructionBegan,
            requestTransmissionBegan = requestTransmissionBegan,
            consecutiveTransientFailures = consecutiveTransientFailures,
        )
    }
}