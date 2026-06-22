package com.zerovpn.app.volunteer.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.zerovpn.app.volunteer.TorSocksProbe
import com.zerovpn.app.volunteer.tun2socks.HevNativeLoader
import com.zerovpn.app.volunteer.tun2socks.HevTun2SocksConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class VolunteerVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tunFd: ParcelFileDescriptor? = null
    private var hevRunning = false
    private var startedAt: Long? = null
    private val tunConfig = VolunteerTunConfig()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVolunteerVpn(
                socksHost = intent.getStringExtra(EXTRA_SOCKS_HOST) ?: DEFAULT_SOCKS_HOST,
                socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, DEFAULT_SOCKS_PORT),
            )
            ACTION_STOP -> stopVolunteerVpn()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopVolunteerVpn()
        scope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVolunteerVpn()
        super.onRevoke()
    }

    private fun startVolunteerVpn(socksHost: String, socksPort: Int) {
        if (hevRunning || tunFd != null) return

        startedAt = System.currentTimeMillis()
        val smoke = HevNativeLoader.smokeTest()
        val initialDiagnostics = VolunteerVpnDiagnostics(
            volunteerVpnState = "StartingVpn",
            androidVpnPermission = "granted",
            vpnServiceStarted = true,
            tunAddress = tunConfig.addressDisplay,
            routes = tunConfig.routeDisplay,
            dnsMode = DNS_MODE,
            dnsLeakRisk = DNS_LEAK_RISK,
            udpMode = UDP_MODE,
            hevNativeEnabled = smoke.enabled,
            hevLibraryLoaded = smoke.loaded,
            socksTarget = "$socksHost:$socksPort",
            torReady = true,
            startedAt = startedAt,
            lastError = smoke.lastLoadError,
        )
        VolunteerVpnRuntime.update(VolunteerVpnState.StartingVpn, initialDiagnostics)
        if (!smoke.loaded) {
            VolunteerVpnRuntime.update(
                VolunteerVpnState.Failed("HEV native library is not loaded."),
                initialDiagnostics.copy(
                    volunteerVpnState = "Failed",
                    lastError = smoke.lastLoadError ?: "HEV native library is not loaded.",
                ),
            )
            stopSelf()
            return
        }

        scope.launch {
            runCatching {
                val establishedTun = Builder()
                    .setSession(tunConfig.sessionName)
                    .setMtu(tunConfig.mtu)
                    .addAddress(tunConfig.address, tunConfig.prefixLength)
                    .addRoute(tunConfig.route, tunConfig.routePrefixLength)
                    .establish() ?: error("VpnService.Builder.establish() returned null")
                tunFd = establishedTun

                val configPath = withContext(Dispatchers.IO) {
                    HevTun2SocksConfig(
                        socksHost = socksHost,
                        socksPort = socksPort,
                        tunnelMtu = tunConfig.mtu,
                        tunnelIpv4 = tunConfig.address,
                    ).writeTo(File(cacheDir, "volunteer-hev/hev-socks5-tunnel.yml")).absolutePath
                }

                VolunteerVpnRuntime.updateDiagnostics {
                    it.copy(
                        tunCreated = true,
                        tunFdOpen = true,
                        androidVpnActiveDetected = isAndroidVpnActive(),
                    )
                }
                withContext(Dispatchers.IO) {
                    HevNativeLoader.TProxyStartService(configPath, establishedTun.fd)
                }
                hevRunning = true
                VolunteerVpnRuntime.update(
                    VolunteerVpnState.Running,
                    VolunteerVpnRuntime.diagnostics.value.copy(
                        volunteerVpnState = "Running",
                        tunCreated = true,
                        tunFdOpen = true,
                        hevRunning = true,
                        androidVpnActiveDetected = isAndroidVpnActive(),
                        lastError = null,
                    ),
                )
                refreshStats()
                runValidation()
            }.getOrElse { e ->
                VolunteerVpnRuntime.update(
                    VolunteerVpnState.Failed(e.message ?: e.javaClass.simpleName),
                    VolunteerVpnRuntime.diagnostics.value.copy(
                        volunteerVpnState = "Failed",
                        lastError = e.message ?: e.javaClass.simpleName,
                    ),
                )
                stopVolunteerVpn()
            }
        }
    }

    private fun stopVolunteerVpn() {
        scope.launch {
            val stopStartedAt = System.currentTimeMillis()
            VolunteerVpnRuntime.update(
                VolunteerVpnState.Stopping,
                VolunteerVpnRuntime.diagnostics.value.copy(volunteerVpnState = "Stopping"),
            )
            withContext(Dispatchers.IO) {
                if (hevRunning) {
                    runCatching { HevNativeLoader.TProxyStopService() }
                    hevRunning = false
                }
                runCatching { tunFd?.close() }
                tunFd = null
            }
            delay(250L)
            val stoppedAt = System.currentTimeMillis()
            VolunteerVpnRuntime.update(
                VolunteerVpnState.Stopped,
                VolunteerVpnRuntime.diagnostics.value.copy(
                    volunteerVpnState = "Stopped",
                    vpnServiceStarted = false,
                    tunFdOpen = false,
                    hevRunning = false,
                    androidVpnActiveDetected = isAndroidVpnActive(),
                    stoppedAt = stoppedAt,
                    stopDurationMs = stoppedAt - stopStartedAt,
                ),
            )
            stopSelf()
        }
    }

    private suspend fun refreshStats() {
        withContext(Dispatchers.IO) {
            runCatching { HevNativeLoader.TProxyGetStats() }.getOrNull()
        }?.let { stats ->
            VolunteerVpnRuntime.updateDiagnostics {
                it.copy(
                    hevRunning = hevRunning,
                    txPackets = stats.getOrNull(0),
                    txBytes = stats.getOrNull(1),
                    rxPackets = stats.getOrNull(2),
                    rxBytes = stats.getOrNull(3),
                    lastError = it.lastError,
                )
            }
        }
    }

    private suspend fun runValidation() {
        val result = withContext(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url(TorSocksProbe.DEFAULT_TEST_URL)
                .header("Accept", "application/json")
                .build()

            runCatching {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    val json = runCatching { JSONObject(body) }.getOrNull()
                    ValidationResult(
                        status = "HTTP ${response.code}" + (
                            json?.takeIf { it.has("IsTor") }?.let { " IsTor=${it.optBoolean("IsTor")}" } ?: ""
                            ),
                        isTor = json?.takeIf { it.has("IsTor") }?.optBoolean("IsTor"),
                        exitIp = json?.optString("IP")?.takeIf { it.isNotBlank() },
                    )
                }
            }.getOrElse { e ->
                ValidationResult(status = "Failed: ${e.javaClass.simpleName}: ${e.message}", isTor = null, exitIp = null)
            }
        }
        VolunteerVpnRuntime.updateDiagnostics {
            it.copy(
                lastValidationUrl = TorSocksProbe.DEFAULT_TEST_URL,
                lastValidationStatus = result.status,
                lastValidationIsTor = result.isTor,
                lastValidationExitIp = result.exitIp,
                androidVpnActiveDetected = isAndroidVpnActive(),
            )
        }
    }

    private fun isAndroidVpnActive(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return false
        return connectivityManager.allNetworks.any { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private data class ValidationResult(
        val status: String,
        val isTor: Boolean?,
        val exitIp: String?,
    )

    companion object {
        private const val ACTION_START = "com.zerovpn.app.volunteer.vpn.START"
        private const val ACTION_STOP = "com.zerovpn.app.volunteer.vpn.STOP"
        private const val EXTRA_SOCKS_HOST = "socksHost"
        private const val EXTRA_SOCKS_PORT = "socksPort"
        private const val DEFAULT_SOCKS_HOST = "127.0.0.1"
        private const val DEFAULT_SOCKS_PORT = 9050
        private const val DNS_MODE = "not-safe-yet-system-resolver"
        private const val DNS_LEAK_RISK = "true"
        private const val UDP_MODE = "unsupported-socks-udp-over-tcp-configured"

        fun startIntent(context: Context, socksHost: String, socksPort: Int): Intent =
            Intent(context, VolunteerVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SOCKS_HOST, socksHost)
                putExtra(EXTRA_SOCKS_PORT, socksPort)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, VolunteerVpnService::class.java).apply {
                action = ACTION_STOP
            }
    }
}
