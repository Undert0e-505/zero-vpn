package com.zerovpn.app.vpn

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.util.Log
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
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
        if (persistedTunnelName.isNullOrBlank()) {
            if (activeTunnel == null && _state.value !is VpnConnectionState.Disconnected) {
                _state.value = VpnConnectionState.Disconnected
                _diagnostics.value = _diagnostics.value.copy(backendState = "Disconnected")
            }
            return
        }

        try {
            val runningNames = withContext(Dispatchers.IO) { backend.runningTunnelNames }
            val tunnel = activeTunnel ?: ZeroVpnTunnel(persistedTunnelName) { tunnelState ->
                if (tunnelState == Tunnel.State.DOWN) {
                    clearActiveState()
                    _state.value = VpnConnectionState.Disconnected
                    _diagnostics.value = _diagnostics.value.copy(backendState = "Disconnected")
                }
            }
            val tunnelState = withContext(Dispatchers.IO) { backend.getState(tunnel) }
            val isRunning = persistedTunnelName in runningNames || tunnelState == Tunnel.State.UP

            if (!isRunning) {
                Log.d(TAG, "Reconcile cleared stale active VPN metadata for tunnel=$persistedTunnelName")
                clearActiveState()
                _state.value = VpnConnectionState.Disconnected
                _diagnostics.value = _diagnostics.value.copy(
                    backendState = "Disconnected",
                    lastError = null,
                )
                return
            }

            activeTunnel = tunnel
            activeTunnelName = persistedTunnelName
            activeExitId = persistedExitId

            val matchedExit = exits.firstOrNull { it.id == persistedExitId }
                ?: exits.firstOrNull { it.id == selectedExitId }
                ?: exits.singleOrNull()
            if (matchedExit != null && matchedExit.id == persistedExitId) {
                val summary = summarizeRuntimeConfig(
                    exit = matchedExit,
                    tunnelName = persistedTunnelName,
                    backendState = "Connected",
                    runtimeConfig = matchedExit.wireGuardConfig,
                    configParseStatus = "Reconciled after restart",
                )
                _state.value = VpnConnectionState.Connected(matchedExit.id)
                _diagnostics.value = summary.copy(
                    activeExitId = matchedExit.id,
                    lastError = null,
                )
                Log.d(TAG, "Reconciled active VPN tunnel=$persistedTunnelName exitId=${matchedExit.id}")
            } else {
                _state.value = VpnConnectionState.ActiveUnknown(persistedTunnelName)
                _diagnostics.value = _diagnostics.value.copy(
                    backendState = "Connected",
                    activeExitId = persistedExitId,
                    tunnelName = persistedTunnelName,
                    endpoint = prefs.getString(KEY_ACTIVE_ENDPOINT, null),
                    tunnelAddress = prefs.getString(KEY_ACTIVE_TUNNEL_ADDRESS, null),
                    selectedExitId = selectedExitId,
                    lastError = "VPN is active, but the configured exit record is unavailable.",
                )
                Log.w(TAG, "Active VPN tunnel is running but exit metadata is unavailable: tunnel=$persistedTunnelName exitId=$persistedExitId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to reconcile VPN state: ${e.javaClass.simpleName}: ${e.message}", e)
            // GoBackend owns the active tunnel list; if it cannot confirm a running
            // tunnel, avoid showing a stale connected state indefinitely.
            clearActiveState()
            _state.value = VpnConnectionState.Disconnected
            _diagnostics.value = _diagnostics.value.copy(
                backendState = "Unknown",
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
            persistActiveState(exit, tunnelName, parsedSummary)
            _state.value = VpnConnectionState.Connected(exit.id)
            _diagnostics.value = parsedSummary.copy(backendState = "Connected", lastError = null)
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
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
                }
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
        }.apply()
    }

    private fun clearActiveState() {
        activeExitId = null
        activeTunnel = null
        activeTunnelName = null
        prefs.edit().apply {
            remove(KEY_ACTIVE_EXIT_ID)
            remove(KEY_ACTIVE_TUNNEL_NAME)
            remove(KEY_ACTIVE_ENDPOINT)
            remove(KEY_ACTIVE_TUNNEL_ADDRESS)
            remove(KEY_ACTIVE_SELECTED_EXIT_ID)
        }.apply()
    }

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
        if (diagnostics.tunnelAddress != "10.66.66.2/32") {
            failures += "runtime address ${diagnostics.tunnelAddress ?: "missing"} != 10.66.66.2/32"
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
