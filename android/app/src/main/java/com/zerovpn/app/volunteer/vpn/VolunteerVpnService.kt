package com.zerovpn.app.volunteer.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class VolunteerVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tunFd: ParcelFileDescriptor? = null
    private var hevRunning = false
    private var stopInProgress = false
    private var stopCompleted = false
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
        if (!stopCompleted && !stopInProgress && (hevRunning || tunFd != null)) {
            stopVolunteerVpn()
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVolunteerVpn()
        super.onRevoke()
    }

    private fun startVolunteerVpn(socksHost: String, socksPort: Int) {
        if (hevRunning || tunFd != null) return

        stopCompleted = false
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
            zeroVpnAppIncludedInVolunteerVpn = false,
            zeroVpnAppExcludedFromVolunteerVpn = true,
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
                val builder = Builder()
                    .setBlocking(false)
                    .setSession(tunConfig.sessionName)
                    .setMtu(tunConfig.mtu)
                    .addAddress(tunConfig.address, tunConfig.prefixLength)
                    .addRoute(tunConfig.route, tunConfig.routePrefixLength)
                    .addDnsServer(tunConfig.dnsServer)

                val appExclusionError = disallowZeroVpnApp(builder)
                VolunteerVpnRuntime.updateDiagnostics {
                    it.copy(
                        zeroVpnAppIncludedInVolunteerVpn = appExclusionError != null,
                        zeroVpnAppExcludedFromVolunteerVpn = appExclusionError == null,
                        appExclusionError = appExclusionError,
                    )
                }
                if (appExclusionError != null) {
                    error("Failed to exclude ZeroVPN app from Volunteer VPN: $appExclusionError")
                }

                val establishedTun = builder.establish() ?: error("VpnService.Builder.establish() returned null")
                tunFd = establishedTun

                val configFile = withContext(Dispatchers.IO) {
                    HevTun2SocksConfig(
                        socksHost = socksHost,
                        socksPort = socksPort,
                        tunnelMtu = tunConfig.mtu,
                        mappedDnsAddress = tunConfig.dnsServer,
                    ).writeTo(File(cacheDir, "volunteer-hev/hev-socks5-tunnel.yml"))
                }
                val configContents = withContext(Dispatchers.IO) { configFile.readText() }

                VolunteerVpnRuntime.updateDiagnostics {
                    it.copy(
                        tunCreated = true,
                        tunFdOpen = true,
                        dnsServer = tunConfig.dnsServer,
                        dnsMode = DNS_MODE,
                        dnsLeakRisk = DNS_LEAK_RISK,
                        zeroVpnAppIncludedInVolunteerVpn = false,
                        zeroVpnAppExcludedFromVolunteerVpn = true,
                        appExclusionError = null,
                        hevConfigPath = configFile.absolutePath,
                        hevConfigExists = configFile.exists(),
                        hevConfigSizeBytes = configFile.length(),
                        hevConfigLastModified = configFile.lastModified(),
                        hevConfigContents = configContents,
                        androidVpnActiveDetected = isAndroidVpnActive(),
                    )
                }
                withContext(Dispatchers.IO) {
                    HevNativeLoader.TProxyStartService(configFile.absolutePath, establishedTun.fd)
                }
                hevRunning = true
                VolunteerVpnRuntime.update(
                    VolunteerVpnState.Running,
                    VolunteerVpnRuntime.diagnostics.value.copy(
                        volunteerVpnState = "Running",
                        tunCreated = true,
                        tunFdOpen = true,
                        hevStartResult = "TProxyStartService returned",
                        hevRunning = true,
                        androidVpnActiveDetected = isAndroidVpnActive(),
                        lastError = null,
                    ),
                )
                refreshStats()
                runTorSocksBaseline(socksHost, socksPort)
                refreshStats()
                runValidation()
                refreshStats()
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
        if (stopInProgress || stopCompleted) return
        stopInProgress = true
        scope.launch {
            val stopStartedAt = System.currentTimeMillis()
            VolunteerVpnRuntime.update(
                VolunteerVpnState.Stopping,
                VolunteerVpnRuntime.diagnostics.value.copy(volunteerVpnState = "Stopping"),
            )
            val stopError = withContext(Dispatchers.IO) {
                var error: String? = null
                if (hevRunning) {
                    runCatching { HevNativeLoader.TProxyStopService() }
                        .onFailure { error = "HEV stop failed: ${it.javaClass.simpleName}: ${it.message}" }
                    hevRunning = false
                }
                runCatching { tunFd?.close() }
                    .onFailure { error = listOfNotNull(error, "TUN close failed: ${it.javaClass.simpleName}: ${it.message}").joinToString("; ") }
                tunFd = null
                error
            }
            delay(250L)
            val stoppedAt = System.currentTimeMillis()
            val stoppedDiagnostics = VolunteerVpnRuntime.diagnostics.value.copy(
                volunteerVpnState = if (stopError == null) "Stopped" else "Failed",
                vpnServiceStarted = false,
                tunFdOpen = false,
                hevRunning = false,
                androidVpnActiveDetected = isAndroidVpnActive(),
                lastError = stopError ?: VolunteerVpnRuntime.diagnostics.value.lastError,
                stoppedAt = stoppedAt,
                stopDurationMs = stoppedAt - stopStartedAt,
            )
            VolunteerVpnRuntime.update(
                if (stopError == null) VolunteerVpnState.Stopped else VolunteerVpnState.Failed(stopError),
                stoppedDiagnostics,
            )
            stopCompleted = true
            stopInProgress = false
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
        val skipped = "Skipped: ZeroVPN app excluded from Volunteer VPN; use browser validation for the TUN data path."
        VolunteerVpnRuntime.updateDiagnostics {
            it.copy(
                lastValidationUrl = TorSocksProbe.DEFAULT_TEST_URL,
                inAppVpnValidationStatus = skipped,
                vpnDnsValidationStatus = skipped,
                vpnTcpValidationStatus = "Skipped: ZeroVPN app excluded; in-app TCP connect would bypass the Volunteer VPN.",
                vpnTorValidationStatus = skipped,
                lastValidationStatus = skipped,
                lastValidationIsTor = null,
                lastValidationExitIp = null,
                androidVpnActiveDetected = isAndroidVpnActive(),
            )
        }
    }

    private fun disallowZeroVpnApp(builder: Builder): String? =
        try {
            builder.addDisallowedApplication(packageName)
            null
        } catch (e: PackageManager.NameNotFoundException) {
            "${e.javaClass.simpleName}: ${e.message}"
        } catch (e: UnsupportedOperationException) {
            "${e.javaClass.simpleName}: ${e.message}"
        }

    private suspend fun runTorSocksBaseline(socksHost: String, socksPort: Int) {
        val result = runCatching {
            TorSocksProbe().run(socksHost = socksHost, socksPort = socksPort)
        }.getOrElse { e ->
            TorSocksProbe.Result(
                url = TorSocksProbe.DEFAULT_TEST_URL,
                status = "Failed: ${e.javaClass.simpleName}: ${e.message}",
                exitIp = null,
                isTor = null,
            )
        }
        VolunteerVpnRuntime.updateDiagnostics {
            it.copy(
                torSocksBaselineStatus = result.status,
                torSocksBaselineIsTor = result.isTor,
                torSocksBaselineExitIp = result.exitIp,
            )
        }
    }

    private suspend fun runHttpValidation(url: String): ValidationResult = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(url)
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

    private suspend fun runTcpValidation(host: String, port: Int): String = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 20_000)
            }
            "TCP connect succeeded: $host:$port"
        }.getOrElse { e ->
            "Failed: ${e.javaClass.simpleName}: ${e.message}"
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
        private const val DNS_MODE = "hev-map-dns-through-socks"
        private const val DNS_LEAK_RISK = "unknown"
        private const val UDP_MODE = "unsupported-not-validated-tor-socks"

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
