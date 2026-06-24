package com.zerovpn.app.vpn

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.util.Log
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class WireGuardTunnelController(
    context: Context,
) {
    private companion object {
        const val TAG = "ZeroVPN/WireGuard"
        const val PREFS_NAME = "zerovpn_vpn"
        const val KEY_ACTIVE_EXIT_ID = "active_exit_id"
        const val KEY_ACTIVE_TUNNEL_NAME = "active_tunnel_name"
        const val KEY_ACTIVE_ENDPOINT = "active_endpoint"
        const val KEY_ACTIVE_TUNNEL_ADDRESS = "active_tunnel_address"
        const val KEY_ACTIVE_SELECTED_EXIT_ID = "active_selected_exit_id"
        const val KEY_ACTIVE_CONNECTED_AT = "active_connected_at"
    }

    private val appContext = context.applicationContext
    private val backend by lazy { GoBackend(appContext) }
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<VpnConnectionState>(VpnConnectionState.Disconnected)
    val state: StateFlow<VpnConnectionState> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow(VpnDiagnostics())
    val diagnostics: StateFlow<VpnDiagnostics> = _diagnostics.asStateFlow()

    private var activeExitId: String? = null
    private var activeTunnel: ZeroVpnTunnel? = null
    private var activeTunnelName: String? = null
    private var restoredRuntimeConfig: String? = null

    fun prepareVpn(): Intent? {
        val intent = VpnService.prepare(appContext)
        Log.d(TAG, "VpnService.prepare returned ${if (intent == null) "null (permission already granted)" else "non-null consent intent"}")
        return intent
    }

    fun markPermissionRequired(exitId: String) {
        Log.d(TAG, "VPN permission required for exitId=$exitId")
        _state.value = VpnConnectionState.PermissionRequired(exitId)
    }

    fun onPermissionDenied() {
        Log.w(TAG, "VPN permission denied or canceled")
        _state.value = VpnConnectionState.Failed("VPN permission was not granted.")
    }

    fun fail(message: String) {
        Log.w(TAG, message)
        _diagnostics.value = _diagnostics.value.copy(
            backendState = "Failed",
            lastError = message,
        )
        _state.value = VpnConnectionState.Failed(message)
    }

    fun prepareDiagnostics(exit: ConfiguredExit) {
        val currentState = _state.value
        val backendState = if (
            currentState is VpnConnectionState.Connected && currentState.exitId == exit.id
        ) {
            "Connected"
        } else {
            "Ready"
        }
        _diagnostics.value = summarizeRuntimeConfig(
            exit = exit,
            tunnelName = tunnelNameFor(exit),
            backendState = backendState,
            runtimeConfig = exit.wireGuardConfig,
            configParseStatus = "Not parsed",
        )
    }

    suspend fun reconcile(exits: List<ConfiguredExit>, selectedExitId: String?) {
        val persistedTunnelName = prefs.getString(KEY_ACTIVE_TUNNEL_NAME, null)
        val persistedExitId = prefs.getString(KEY_ACTIVE_EXIT_ID, null)
        val persistedConnectedAt = prefs.getLong(KEY_ACTIVE_CONNECTED_AT, 0L).takeIf { it > 0L }
        val vpnSnapshot = inspectAndroidVpnTransport()
        if (persistedTunnelName.isNullOrBlank()) {
            if (vpnSnapshot.detected) {
                activeExitId = null
                activeTunnelName = vpnSnapshot.interfaceName ?: "Android VPN"
                _state.value = VpnConnectionState.ActiveUnknown(activeTunnelName ?: "Android VPN")
                _diagnostics.value = _diagnostics.value.copy(
                    backendState = "VPN Active",
                    activeExitId = null,
                    persistedActiveExitId = null,
                    persistedTunnelName = null,
                    selectedExitId = selectedExitId,
                    tunnelName = activeTunnelName,
                    reconciliationSource = "Android TRANSPORT_VPN fallback; no persisted ZeroVPN tunnel metadata",
                    androidVpnDetected = true,
                    androidVpnInterfaceName = vpnSnapshot.interfaceName,
                    androidVpnDnsServers = vpnSnapshot.dnsServers,
                    androidVpnRoutes = vpnSnapshot.routes,
                    androidVpnLinkAddresses = vpnSnapshot.linkAddresses,
                    lastError = "Active Android VPN detected; exit mapping unavailable.",
                )
            } else if (activeTunnel == null && _state.value !is VpnConnectionState.Disconnected) {
                _state.value = VpnConnectionState.Disconnected
                _diagnostics.value = _diagnostics.value.copy(
                    backendState = "Disconnected",
                    reconciliationSource = "stale cleared",
                    androidVpnDetected = false,
                )
            }
            return
        }

        try {
            val runningNames = withContext(Dispatchers.IO) { backend.runningTunnelNames }
            val tunnel = activeTunnel ?: ZeroVpnTunnel(persistedTunnelName) { tunnelState ->
                if (tunnelState == Tunnel.State.DOWN) {
                    clearActiveState()
                    _state.value = VpnConnectionState.Disconnected
                    _diagnostics.value = _diagnostics.value.copy(
                        backendState = "Disconnected",
                        reconciliationSource = "tunnel callback DOWN",
                    )
                }
            }
            val tunnelState = withContext(Dispatchers.IO) { backend.getState(tunnel) }
            val goBackendIsRunning = persistedTunnelName in runningNames || tunnelState == Tunnel.State.UP
            val matchedPersistedExit = exits.firstOrNull { it.id == persistedExitId }
            val matchedExit = matchedPersistedExit
                ?: exits.firstOrNull { it.id == selectedExitId }
                ?: exits.singleOrNull()

            if (!goBackendIsRunning && !vpnSnapshot.detected) {
                Log.d(TAG, "Reconcile cleared stale active VPN metadata for tunnel=$persistedTunnelName; GoBackend DOWN and no Android VPN transport")
                clearActiveState()
                _state.value = VpnConnectionState.Disconnected
                _diagnostics.value = _diagnostics.value.copy(
                    backendState = "Disconnected",
                    persistedActiveExitId = persistedExitId,
                    persistedTunnelName = persistedTunnelName,
                    persistedConnectedAt = persistedConnectedAt,
                    selectedExitId = selectedExitId,
                    reconciliationSource = "stale cleared",
                    goBackendRunningTunnelNames = runningNames.joinToString(", ").ifBlank { "none" },
                    androidVpnDetected = false,
                    lastError = null,
                )
                return
            }

            activeTunnel = tunnel
            activeTunnelName = persistedTunnelName
            activeExitId = persistedExitId
            restoredRuntimeConfig = matchedPersistedExit?.wireGuardConfig

            if (matchedExit != null && matchedExit.id == persistedExitId) {
                val summary = summarizeRuntimeConfig(
                    exit = matchedExit,
                    tunnelName = persistedTunnelName,
                    backendState = if (goBackendIsRunning) "Connected" else "VPN Active",
                    runtimeConfig = matchedExit.wireGuardConfig,
                    configParseStatus = if (goBackendIsRunning) {
                        "Reconciled after restart"
                    } else {
                        "Restored from Android VPN state"
                    },
                )
                _state.value = VpnConnectionState.Connected(matchedExit.id)
                _diagnostics.value = summary.copy(
                    activeExitId = matchedExit.id,
                    persistedActiveExitId = persistedExitId,
                    persistedTunnelName = persistedTunnelName,
                    persistedConnectedAt = persistedConnectedAt,
                    reconciliationSource = if (goBackendIsRunning) "GoBackend UP" else "Android TRANSPORT_VPN fallback",
                    goBackendRunningTunnelNames = runningNames.joinToString(", ").ifBlank { "none" },
                    androidVpnDetected = vpnSnapshot.detected,
                    androidVpnInterfaceName = vpnSnapshot.interfaceName,
                    androidVpnDnsServers = vpnSnapshot.dnsServers,
                    androidVpnRoutes = vpnSnapshot.routes,
                    androidVpnLinkAddresses = vpnSnapshot.linkAddresses,
                    lastError = null,
                )
                Log.d(TAG, "Reconciled active VPN tunnel=$persistedTunnelName exitId=${matchedExit.id} source=${_diagnostics.value.reconciliationSource}")
            } else {
                _state.value = VpnConnectionState.ActiveUnknown(persistedTunnelName)
                _diagnostics.value = _diagnostics.value.copy(
                    backendState = if (goBackendIsRunning) "Connected" else "VPN Active",
                    activeExitId = persistedExitId,
                    persistedActiveExitId = persistedExitId,
                    persistedTunnelName = persistedTunnelName,
                    persistedConnectedAt = persistedConnectedAt,
                    tunnelName = persistedTunnelName,
                    endpoint = prefs.getString(KEY_ACTIVE_ENDPOINT, null),
                    tunnelAddress = prefs.getString(KEY_ACTIVE_TUNNEL_ADDRESS, null),
                    selectedExitId = selectedExitId,
                    reconciliationSource = if (goBackendIsRunning) {
                        "GoBackend UP; exit mapping unavailable"
                    } else {
                        "Android TRANSPORT_VPN fallback; exit mapping unavailable"
                    },
                    goBackendRunningTunnelNames = runningNames.joinToString(", ").ifBlank { "none" },
                    androidVpnDetected = vpnSnapshot.detected,
                    androidVpnInterfaceName = vpnSnapshot.interfaceName,
                    androidVpnDnsServers = vpnSnapshot.dnsServers,
                    androidVpnRoutes = vpnSnapshot.routes,
                    androidVpnLinkAddresses = vpnSnapshot.linkAddresses,
                    lastError = "VPN is active, but the configured exit record is unavailable.",
                )
                Log.w(TAG, "Active VPN tunnel is running but exit metadata is unavailable: tunnel=$persistedTunnelName exitId=$persistedExitId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to reconcile VPN state: ${e.javaClass.simpleName}: ${e.message}", e)
            if (vpnSnapshot.detected) {
                activeTunnelName = persistedTunnelName
                activeExitId = persistedExitId
                _state.value = VpnConnectionState.ActiveUnknown(persistedTunnelName ?: "Android VPN")
                _diagnostics.value = _diagnostics.value.copy(
                    backendState = "VPN Active",
                    activeExitId = persistedExitId,
                    persistedActiveExitId = persistedExitId,
                    persistedTunnelName = persistedTunnelName,
                    persistedConnectedAt = persistedConnectedAt,
                    tunnelName = persistedTunnelName,
                    selectedExitId = selectedExitId,
                    reconciliationSource = "Android TRANSPORT_VPN fallback after GoBackend error",
                    androidVpnDetected = true,
                    androidVpnInterfaceName = vpnSnapshot.interfaceName,
                    androidVpnDnsServers = vpnSnapshot.dnsServers,
                    androidVpnRoutes = vpnSnapshot.routes,
                    androidVpnLinkAddresses = vpnSnapshot.linkAddresses,
                    lastError = "GoBackend could not verify VPN state: ${e.message}",
                )
                return
            }
            clearActiveState()
            _state.value = VpnConnectionState.Disconnected
            _diagnostics.value = _diagnostics.value.copy(
                backendState = "Unknown",
                persistedActiveExitId = persistedExitId,
                persistedTunnelName = persistedTunnelName,
                persistedConnectedAt = persistedConnectedAt,
                reconciliationSource = "stale cleared after GoBackend error and no Android VPN",
                androidVpnDetected = false,
                lastError = "Unable to verify VPN state: ${e.message}",
            )
        }
    }

    suspend fun connect(exit: ConfiguredExit) {
        _state.value = VpnConnectionState.Connecting(exit.id)
        try {
            val runtimeConfig = exit.wireGuardConfig
            val tunnelName = tunnelNameFor(exit)
            val summary = summarizeRuntimeConfig(
                exit = exit,
                tunnelName = tunnelName,
                backendState = "Connecting",
                runtimeConfig = runtimeConfig,
                configParseStatus = "Not parsed",
            )
            _diagnostics.value = summary.copy(lastError = null)
            val validation = validateRuntimeConfig(exit, summary)
            val validatedSummary = summary.copy(keyValidation = validation)
            _diagnostics.value = validatedSummary.copy(lastError = null)
            Log.d(TAG, "Exact runtime WireGuard config for Config.parse:\n${validatedSummary.runtimeConfigRedacted}")
            Log.d(
                TAG,
                "Tunnel runtime config exitId=${exit.id}, tunnelName=$tunnelName, endpoint=${summary.endpoint}, address=${summary.tunnelAddress}, dns=${summary.dns}, allowedIps=${summary.allowedIps}, keepalive=${summary.persistentKeepalive}, androidClientPublicKey=${summary.androidClientPublicKey}, androidPeerServerPublicKey=${summary.androidPeerServerPublicKey}, expectedServerPeerPublicKey=${summary.provisionedServerPeerPublicKey}, expectedServerPublicKey=${summary.selectedNodeServerPublicKey}, validation=$validation, configPresent=${exit.wireGuardConfig.isNotBlank()}",
            )
            if (activeTunnel != null) {
                disconnect()
                _state.value = VpnConnectionState.Connecting(exit.id)
            }
            val tunnel = ZeroVpnTunnel(tunnelName) { tunnelState ->
                if (tunnelState == Tunnel.State.DOWN) {
                    activeExitId = null
                    activeTunnel = null
                    activeTunnelName = null
                    restoredRuntimeConfig = null
                    _state.value = VpnConnectionState.Disconnected
                }
            }
            val config = try {
                parseConfig(runtimeConfig)
            } catch (e: Exception) {
                _diagnostics.value = validatedSummary.copy(
                    configParseStatus = "FAILED: ${e.javaClass.simpleName}: ${e.message}",
                    lastError = e.message,
                )
                throw e
            }
            val parsedSummary = validatedSummary.copy(configParseStatus = "Succeeded")
            _diagnostics.value = parsedSummary
            withContext(Dispatchers.IO) {
                backend.setState(tunnel, Tunnel.State.UP, config)
            }
            activeTunnel = tunnel
            activeTunnelName = tunnelName
            activeExitId = exit.id
            restoredRuntimeConfig = runtimeConfig
            persistActiveState(exit, tunnelName, parsedSummary)
            _state.value = VpnConnectionState.Connected(exit.id)
            _diagnostics.value = parsedSummary.copy(
                backendState = "Connected",
                reconciliationSource = "GoBackend connect",
                lastError = null,
            )
            Log.d(TAG, "Tunnel connect success exitId=${exit.id}, tunnelName=$tunnelName")
        } catch (e: Exception) {
            clearActiveState()
            Log.e(TAG, "Tunnel connect failed exitId=${exit.id}: ${e.javaClass.simpleName}: ${e.message}", e)
            _diagnostics.value = _diagnostics.value.copy(
                backendState = "Failed",
                lastError = e.message ?: "Failed to connect VPN.",
            )
            _state.value = VpnConnectionState.Failed(e.message ?: "Failed to connect VPN.")
            throw e
        }
    }

    suspend fun disconnect() {
        val exitId = activeExitId
        val tunnelName = activeTunnelName ?: prefs.getString(KEY_ACTIVE_TUNNEL_NAME, null)
        val tunnel = activeTunnel ?: tunnelName?.let { ZeroVpnTunnel(it) {} }
        _state.value = VpnConnectionState.Disconnecting(exitId)
        try {
            Log.d(TAG, "Tunnel disconnect start exitId=$exitId, tunnelName=$tunnelName")
            _diagnostics.value = _diagnostics.value.copy(backendState = "Disconnecting")
            if (tunnel != null) {
                withContext(Dispatchers.IO) {
                    val backendKnowsTunnel = backend.getState(tunnel) == Tunnel.State.UP ||
                        tunnelName in backend.runningTunnelNames
                    if (backendKnowsTunnel) {
                        backend.setState(tunnel, Tunnel.State.DOWN, null)
                    } else {
                        val config = restoredRuntimeConfig?.let { parseConfig(it) }
                        if (config != null) {
                            Log.d(TAG, "Disconnecting restored Android VPN by reacquiring tunnel handle first")
                            backend.setState(tunnel, Tunnel.State.UP, config)
                            backend.setState(tunnel, Tunnel.State.DOWN, null)
                        } else {
                            backend.setState(tunnel, Tunnel.State.DOWN, null)
                        }
                    }
                }
            }
            if (waitForVpnTransportToClear().detected) {
                error("Android still reports an active VPN. Disconnect from Android VPN settings if this persists.")
            }
            clearActiveState()
            _state.value = VpnConnectionState.Disconnected
            _diagnostics.value = _diagnostics.value.copy(backendState = "Disconnected", lastError = null)
            Log.d(TAG, "Tunnel disconnect success exitId=$exitId")
        } catch (e: Exception) {
            Log.e(TAG, "Tunnel disconnect failed exitId=$exitId: ${e.javaClass.simpleName}: ${e.message}", e)
            _diagnostics.value = _diagnostics.value.copy(
                backendState = "Failed",
                lastError = e.message ?: "Failed to disconnect VPN.",
            )
            _state.value = VpnConnectionState.Failed(e.message ?: "Failed to disconnect VPN.")
            throw e
        }
    }

    suspend fun disconnectIfActive(exitId: String?): Boolean {
        if (activeTunnel == null && prefs.getString(KEY_ACTIVE_TUNNEL_NAME, null).isNullOrBlank()) return true
        val currentExitId = activeExitId ?: prefs.getString(KEY_ACTIVE_EXIT_ID, null)
        if (exitId != null && currentExitId != exitId) return true
        return try {
            disconnect()
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun latestHandshakeEpochMillis(): Long? {
        val tunnel = activeTunnel ?: return null
        return withContext(Dispatchers.IO) {
            val stats = backend.getStatistics(tunnel)
            stats.peers()
                .mapNotNull { peer -> stats.peer(peer)?.latestHandshakeEpochMillis() }
                .filter { it > 0L }
                .maxOrNull()
        }
    }

    private fun parseConfig(wireGuardConfig: String): Config {
        val bytes = wireGuardConfig.toByteArray(Charsets.UTF_8)
        return Config.parse(ByteArrayInputStream(bytes))
    }

    private fun summarizeRuntimeConfig(
        exit: ConfiguredExit,
        tunnelName: String,
        backendState: String,
        runtimeConfig: String,
        configParseStatus: String,
    ): VpnDiagnostics {
        val interfaceSection = section(runtimeConfig, "Interface")
        val peerSection = section(runtimeConfig, "Peer")
        val clientPrivateKey = value(interfaceSection, "PrivateKey")
        val peerServerPublicKey = value(peerSection, "PublicKey")
        val clientPublicKey = clientPrivateKey?.let { privateKey ->
            runCatching { KeyPair(Key.fromBase64(privateKey)).publicKey.toBase64() }.getOrNull()
        }
        return VpnDiagnostics(
            backendState = backendState,
            selectedExitId = exit.id,
            activeExitId = activeExitId,
            persistedActiveExitId = prefs.getString(KEY_ACTIVE_EXIT_ID, null),
            persistedTunnelName = prefs.getString(KEY_ACTIVE_TUNNEL_NAME, null),
            persistedConnectedAt = prefs.getLong(KEY_ACTIVE_CONNECTED_AT, 0L).takeIf { it > 0L },
            tunnelName = tunnelName,
            configParseStatus = configParseStatus,
            runtimeConfigRedacted = redactWireGuardConfig(runtimeConfig),
            selectedExitPublicIp = exit.publicIp,
            tunnelAddress = value(interfaceSection, "Address"),
            endpoint = value(peerSection, "Endpoint"),
            dns = value(interfaceSection, "DNS"),
            allowedIps = value(peerSection, "AllowedIPs"),
            persistentKeepalive = value(peerSection, "PersistentKeepalive"),
            androidClientPublicKey = clientPublicKey,
            androidPeerServerPublicKey = peerServerPublicKey,
            selectedNodeServerPublicKey = exit.serverPublicKey,
            provisionedServerPeerPublicKey = exit.serverPeerPublicKey,
        )
    }

    private fun persistActiveState(exit: ConfiguredExit, tunnelName: String, diagnostics: VpnDiagnostics) {
        prefs.edit().apply {
            putString(KEY_ACTIVE_EXIT_ID, exit.id)
            putString(KEY_ACTIVE_TUNNEL_NAME, tunnelName)
            putString(KEY_ACTIVE_ENDPOINT, diagnostics.endpoint ?: "${exit.publicIp}:${exit.wireGuardPort}")
            putString(KEY_ACTIVE_TUNNEL_ADDRESS, diagnostics.tunnelAddress ?: "10.66.66.2/32")
            putString(KEY_ACTIVE_SELECTED_EXIT_ID, exit.id)
            putLong(KEY_ACTIVE_CONNECTED_AT, System.currentTimeMillis())
        }.apply()
    }

    private fun clearActiveState() {
        activeExitId = null
        activeTunnel = null
        activeTunnelName = null
        restoredRuntimeConfig = null
        prefs.edit().apply {
            remove(KEY_ACTIVE_EXIT_ID)
            remove(KEY_ACTIVE_TUNNEL_NAME)
            remove(KEY_ACTIVE_ENDPOINT)
            remove(KEY_ACTIVE_TUNNEL_ADDRESS)
            remove(KEY_ACTIVE_SELECTED_EXIT_ID)
            remove(KEY_ACTIVE_CONNECTED_AT)
        }.apply()
    }

    private fun inspectAndroidVpnTransport(): AndroidVpnSnapshot {
        return runCatching {
            val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (network in connectivityManager.allNetworks) {
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val properties = connectivityManager.getLinkProperties(network)
                return AndroidVpnSnapshot(
                    detected = true,
                    interfaceName = properties?.interfaceName,
                    dnsServers = properties?.dnsServers
                        ?.joinToString(", ") { it.hostAddress.orEmpty() }
                        ?.takeIf { it.isNotBlank() },
                    routes = properties?.routes
                        ?.joinToString(", ") { it.toString() }
                        ?.takeIf { it.isNotBlank() },
                    linkAddresses = properties?.linkAddresses
                        ?.joinToString(", ") { it.toString() }
                        ?.takeIf { it.isNotBlank() },
                )
            }
            AndroidVpnSnapshot(detected = false)
        }.getOrElse { e ->
            Log.w(TAG, "Unable to inspect Android VPN transport: ${e.javaClass.simpleName}: ${e.message}", e)
            AndroidVpnSnapshot(detected = false)
        }
    }

    private suspend fun waitForVpnTransportToClear(): AndroidVpnSnapshot {
        var latest = inspectAndroidVpnTransport()
        repeat(5) { attempt ->
            if (!latest.detected) return latest
            Log.d(TAG, "Android VPN transport still visible after disconnect attempt ${attempt + 1}; waiting briefly")
            delay(200)
            latest = inspectAndroidVpnTransport()
        }
        return latest
    }

    private data class AndroidVpnSnapshot(
        val detected: Boolean,
        val interfaceName: String? = null,
        val dnsServers: String? = null,
        val routes: String? = null,
        val linkAddresses: String? = null,
    )

    private fun validateRuntimeConfig(exit: ConfiguredExit, diagnostics: VpnDiagnostics): String {
        val failures = mutableListOf<String>()
        if (diagnostics.androidClientPublicKey.isNullOrBlank()) {
            failures += "runtime client public key missing or invalid"
        }
        if (diagnostics.provisionedServerPeerPublicKey.isNullOrBlank()) {
            failures += "provisioned server peer public key missing"
        }
        if (
            !diagnostics.androidClientPublicKey.isNullOrBlank() &&
            !diagnostics.provisionedServerPeerPublicKey.isNullOrBlank() &&
            diagnostics.androidClientPublicKey != diagnostics.provisionedServerPeerPublicKey
        ) {
            failures += "runtime client public key != provisioned server peer public key"
        }

        if (diagnostics.androidPeerServerPublicKey.isNullOrBlank()) {
            failures += "runtime peer/server public key missing"
        }
        if (diagnostics.selectedNodeServerPublicKey.isNullOrBlank()) {
            failures += "selected node server public key missing"
        }
        if (
            !diagnostics.androidPeerServerPublicKey.isNullOrBlank() &&
            !diagnostics.selectedNodeServerPublicKey.isNullOrBlank() &&
            diagnostics.androidPeerServerPublicKey != diagnostics.selectedNodeServerPublicKey
        ) {
            failures += "runtime peer/server public key != selected node server public key"
        }

        val expectedEndpoint = "${exit.publicIp}:${exit.wireGuardPort}"
        if (diagnostics.endpoint != expectedEndpoint) {
            failures += "runtime endpoint ${diagnostics.endpoint ?: "missing"} != selected endpoint $expectedEndpoint"
        }
        if (exit.provider == ExitProvider.OCI && diagnostics.tunnelAddress != "10.66.66.2/32") {
            failures += "runtime address ${diagnostics.tunnelAddress ?: "missing"} != 10.66.66.2/32"
        } else if (exit.provider == ExitProvider.SHARED_WIREGUARD && diagnostics.tunnelAddress.isNullOrBlank()) {
            failures += "runtime address missing"
        }

        if (failures.isNotEmpty()) {
            val message = "WireGuard runtime config mismatch: ${failures.joinToString("; ")}"
            Log.e(TAG, message)
            throw IllegalStateException(message)
        }
        return "OK"
    }

    private fun tunnelNameFor(exit: ConfiguredExit): String {
        val suffix = exit.id
            .replace(Regex("[^A-Za-z0-9_.=-]"), "_")
            .takeLast(40)
        return "ZeroVPN_$suffix"
    }

    private fun redactWireGuardConfig(config: String): String =
        config.lineSequence()
            .map { line ->
                val trimmed = line.trimStart()
                val leadingWhitespace = line.take(line.length - trimmed.length)
                when {
                    trimmed.startsWith("PrivateKey", ignoreCase = true) && trimmed.contains("=") ->
                        "${leadingWhitespace}PrivateKey = REDACTED"
                    trimmed.startsWith("PresharedKey", ignoreCase = true) && trimmed.contains("=") ->
                        "${leadingWhitespace}PresharedKey = REDACTED"
                    else -> line
                }
            }
            .joinToString("\n")

    private fun section(config: String, name: String): String {
        val start = config.indexOf("[$name]")
        if (start < 0) return ""
        val next = config.indexOf("\n[", start + name.length + 2)
        return if (next >= 0) config.substring(start, next) else config.substring(start)
    }

    private fun value(section: String, key: String): String? =
        section.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key", ignoreCase = true) && it.contains("=") }
            ?.substringAfter("=")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
}
