package com.zerovpn.app.vpn

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class VpnViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = WireGuardTunnelController(application)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    val state: StateFlow<VpnConnectionState> = controller.state
    val diagnostics: StateFlow<VpnDiagnostics> = controller.diagnostics

    private val _userDiagnostics = MutableStateFlow(UserDiagnosticsState())
    val userDiagnostics: StateFlow<UserDiagnosticsState> = _userDiagnostics.asStateFlow()

    private var lastUserDiagnosticsRefresh = 0L

    fun prepareVpn(): Intent? = controller.prepareVpn()

    fun markPermissionRequired(exitId: String) {
        controller.markPermissionRequired(exitId)
    }

    fun onPermissionDenied() {
        controller.onPermissionDenied()
    }

    fun fail(message: String) {
        controller.fail(message)
    }

    fun prepareDiagnostics(exit: ConfiguredExit) {
        controller.prepareDiagnostics(exit)
    }

    fun reconcile(exits: List<ConfiguredExit>, selectedExitId: String?) {
        viewModelScope.launch {
            controller.reconcile(exits, selectedExitId)
        }
    }

    fun refreshUserDiagnostics(force: Boolean = false) {
        viewModelScope.launch {
            try {
                refreshUserDiagnosticsInternal(force)
            } catch (e: Exception) {
                _userDiagnostics.value = _userDiagnostics.value.copy(
                    exitIpStatus = if (state.value is VpnConnectionState.Disconnected) {
                        ExitIpStatus.NotConnected
                    } else {
                        ExitIpStatus.Failed
                    },
                    dnsLeakStatus = if (state.value is VpnConnectionState.Disconnected) {
                        DnsLeakStatus.NotConnected
                    } else {
                        DnsLeakStatus.Unknown
                    },
                    lastHandshakeStatus = if (state.value is VpnConnectionState.Disconnected) {
                        LastHandshakeStatus.NotConnected
                    } else {
                        LastHandshakeStatus.Unavailable
                    },
                    lastHandshakeText = if (state.value is VpnConnectionState.Disconnected) {
                        "Not connected"
                    } else {
                        "Unavailable"
                    },
                    lastUpdated = System.currentTimeMillis(),
                    userVisibleError = e.message ?: "Diagnostics refresh failed.",
                )
            }
        }
    }

    fun connect(exit: ConfiguredExit) {
        viewModelScope.launch {
            try {
                val currentState = state.value
                if (
                    currentState is VpnConnectionState.Connected && currentState.exitId != exit.id ||
                    currentState is VpnConnectionState.ActiveUnknown
                ) {
                    controller.disconnect()
                }
                controller.connect(exit)
                refreshUserDiagnosticsInternal(force = true)
            } catch (e: Exception) {
                // State is already updated by the controller.
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            try {
                controller.disconnect()
                _userDiagnostics.value = notConnectedDiagnostics()
            } catch (e: Exception) {
                // State is already updated by the controller.
            }
        }
    }

    suspend fun disconnectIfActive(exitId: String?): Boolean = controller.disconnectIfActive(exitId)

    private suspend fun refreshUserDiagnosticsInternal(force: Boolean) {
        val currentState = state.value
        if (currentState !is VpnConnectionState.Connected && currentState !is VpnConnectionState.ActiveUnknown) {
            _userDiagnostics.value = notConnectedDiagnostics()
            return
        }

        val now = System.currentTimeMillis()
        if (!force && now - lastUserDiagnosticsRefresh < 30_000L) return
        lastUserDiagnosticsRefresh = now

        _userDiagnostics.value = _userDiagnostics.value.copy(
            exitIpStatus = ExitIpStatus.Loading,
            dnsLeakStatus = DnsLeakStatus.Loading,
            userVisibleError = null,
        )

        val ipResult = runCatching { fetchIpInfo() }
        val dnsResult = runCatching { inspectDnsServers() }.getOrElse { e ->
            DnsResult(
                status = DnsLeakStatus.Unknown,
                summary = "Unknown",
                detail = "Could not inspect Android DNS resolver state: ${e.message ?: e.javaClass.simpleName}",
            )
        }
        val handshakeText = latestHandshakeText()

        _userDiagnostics.value = UserDiagnosticsState(
            exitIpStatus = if (ipResult.isSuccess) ExitIpStatus.Available else ExitIpStatus.Failed,
            exitIp = ipResult.getOrNull()?.ip,
            exitCountry = ipResult.getOrNull()?.country,
            exitProviderOrAsn = ipResult.getOrNull()?.org,
            dnsLeakStatus = dnsResult.status,
            dnsResolverSummary = dnsResult.summary,
            dnsLeakDetail = dnsResult.detail,
            lastHandshakeStatus = if (handshakeText != null) {
                LastHandshakeStatus.Available
            } else {
                LastHandshakeStatus.Unavailable
            },
            lastHandshakeText = handshakeText ?: "Unavailable on Android backend",
            lastUpdated = System.currentTimeMillis(),
            userVisibleError = ipResult.exceptionOrNull()?.message,
        )
    }

    private fun notConnectedDiagnostics(): UserDiagnosticsState = UserDiagnosticsState(
        exitIpStatus = ExitIpStatus.NotConnected,
        dnsLeakStatus = DnsLeakStatus.NotConnected,
        lastHandshakeStatus = LastHandshakeStatus.NotConnected,
        lastHandshakeText = "Not connected",
        dnsResolverSummary = "Not connected",
        dnsLeakDetail = "Connect to an exit node to run DNS diagnostics.",
        userVisibleError = null,
    )

    private suspend fun fetchIpInfo(): IpInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://ipinfo.io/json")
            .header("Accept", "application/json")
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("IP check failed: HTTP ${response.code}")
            }
            val json = JSONObject(body)
            IpInfo(
                ip = json.optString("ip").takeIf { it.isNotBlank() },
                country = json.optString("country").takeIf { it.isNotBlank() },
                org = json.optString("org").takeIf { it.isNotBlank() },
            )
        }
    }

    private fun inspectDnsServers(): DnsResult {
        val connectivityManager = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
            ?: return DnsResult(
                status = DnsLeakStatus.Unknown,
                summary = "Unknown",
                detail = "Android did not report an active network.",
            )
        val servers = connectivityManager.getLinkProperties(network)
            ?.dnsServers
            ?.map { it.hostAddress.orEmpty() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        if (servers.isEmpty()) {
            return DnsResult(
                status = DnsLeakStatus.Unknown,
                summary = "Unknown",
                detail = "Android did not report DNS resolvers for the active VPN network.",
            )
        }

        val configuredCloudflare = setOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
        val allConfigured = servers.all { it in configuredCloudflare }
        return if (allConfigured) {
            DnsResult(
                status = DnsLeakStatus.Passed,
                summary = servers.joinToString(", "),
                detail = "DNS appears to be using Cloudflare via the VPN configuration.",
            )
        } else {
            DnsResult(
                status = DnsLeakStatus.Warning,
                summary = servers.joinToString(", "),
                detail = "DNS resolver does not exactly match the configured VPN DNS. Your ISP may be seeing DNS requests.",
            )
        }
    }

    private suspend fun latestHandshakeText(): String? {
        val latest = runCatching { controller.latestHandshakeEpochMillis() }.getOrNull() ?: return null
        val now = System.currentTimeMillis()
        val ageSeconds = ((now - latest).coerceAtLeast(0L) / 1000L)
        val time = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(latest))
        return "$time (${formatAge(ageSeconds)} ago)"
    }

    private fun formatAge(ageSeconds: Long): String = when {
        ageSeconds < 60 -> "$ageSeconds seconds"
        ageSeconds < 3600 -> "${ageSeconds / 60} minutes"
        else -> "${ageSeconds / 3600} hours"
    }

    private data class IpInfo(
        val ip: String?,
        val country: String?,
        val org: String?,
    )

    private data class DnsResult(
        val status: DnsLeakStatus,
        val summary: String,
        val detail: String,
    )
}
