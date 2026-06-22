package com.zerovpn.app.volunteer

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.torproject.jni.TorService
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class EmbeddedTorController(context: Context) {
    private val appContext = context.applicationContext
    private val localBroadcastManager = LocalBroadcastManager.getInstance(appContext)
    private var torService: TorService? = null
    private var serviceConnection: ServiceConnection? = null
    private var statusReceiver: BroadcastReceiver? = null
    private var errorReceiver: BroadcastReceiver? = null

    @Volatile
    private var latestStatus: String? = null

    @Volatile
    private var latestError: String? = null

    suspend fun start(
        timeoutMs: Long,
        onBootstrapUpdate: suspend (BootstrapUpdate) -> Unit,
    ): StartResult = withContext(Dispatchers.Main.immediate) {
        latestStatus = null
        latestError = null
        registerReceivers()

        val stateDirExisted = torStateDir.exists()
        val cacheDirExisted = torCacheDir.exists()
        val bootstrapStartedAt = System.currentTimeMillis()
        val service = bindService()

        val result = withTimeout(timeoutMs) {
            while (service.torControlConnection == null) {
                failIfError()
                delay(CONTROL_CONNECTION_POLL_MS)
            }

            var lastUpdate: BootstrapUpdate? = null
            var discoveredSocksPort: Int? = null
            while (discoveredSocksPort == null) {
                failIfError()
                val update = readBootstrapUpdate(service)
                if (update != lastUpdate) {
                    lastUpdate = update
                    onBootstrapUpdate(update)
                }
                val socksPort = service.socksPort
                if (
                    socksPort > 0 &&
                    (latestStatus == TorService.STATUS_ON || update.progress == 100)
                ) {
                    val completedAt = System.currentTimeMillis()
                    discoveredSocksPort = socksPort
                    return@withTimeout StartResult(
                        socksPort = socksPort,
                        timeoutMs = timeoutMs,
                        coldStart = !stateDirExisted,
                        stateDirectoryExisted = stateDirExisted,
                        cacheDirectoryExisted = cacheDirExisted,
                        bootstrapStartedAt = bootstrapStartedAt,
                        bootstrapCompletedAt = completedAt,
                        bootstrapDurationMs = completedAt - bootstrapStartedAt,
                        bootstrapUpdate = update,
                    )
                }
                delay(BOOTSTRAP_POLL_MS)
            }
            error("Embedded Tor bootstrap ended without a SOCKS port.")
        }
        result
    }

    suspend fun stop(): StopResult = withContext(Dispatchers.Main.immediate) {
        val stopRequestedAt = System.currentTimeMillis()
        serviceConnection?.let { connection ->
            runCatching { appContext.unbindService(connection) }
        }
        runCatching { appContext.stopService(Intent(appContext, TorService::class.java)) }

        val stopped = waitForStopped()
        unregisterReceivers()
        serviceConnection = null
        torService = null
        val stoppedAt = System.currentTimeMillis()
        StopResult(
            stopRequestedAt = stopRequestedAt,
            stoppedAt = stoppedAt,
            stopDurationMs = stoppedAt - stopRequestedAt,
            torServiceBound = isServiceBound,
            torRunning = !stopped,
            latestStatus = latestStatus,
        ).also {
            latestStatus = null
            latestError = null
        }
    }

    suspend fun clearState(): ClearStateResult = withContext(Dispatchers.IO) {
        val stateExisted = torStateDir.exists()
        val cacheExisted = torCacheDir.exists()
        val stateDeleted = !stateExisted || torStateDir.deleteRecursively()
        val cacheDeleted = !cacheExisted || torCacheDir.deleteRecursively()
        ClearStateResult(
            stateDirectoryExisted = stateExisted,
            cacheDirectoryExisted = cacheExisted,
            stateDirectoryDeleted = stateDeleted,
            cacheDirectoryDeleted = cacheDeleted,
        )
    }

    fun snapshot(): Snapshot = Snapshot(
        torServiceBound = isServiceBound,
        torRunning = torService?.torControlConnection != null &&
            latestStatus != TorService.STATUS_OFF &&
            latestStatus != TorService.STATUS_STOPPING,
        latestStatus = latestStatus,
        stateDirectoryExists = torStateDir.exists(),
        cacheDirectoryExists = torCacheDir.exists(),
    )

    private val isServiceBound: Boolean
        get() = serviceConnection != null && torService != null

    private val torStateDir: File
        get() = File(appContext.applicationInfo.dataDir, TOR_STATE_DIR_NAME)

    private val torCacheDir: File
        get() = File(appContext.cacheDir, TorService.TAG)

    private suspend fun waitForStopped(): Boolean {
        val deadline = System.currentTimeMillis() + STOP_VERIFY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (latestStatus == TorService.STATUS_OFF || torService == null) {
                return true
            }
            delay(STOP_VERIFY_POLL_MS)
        }
        return latestStatus == TorService.STATUS_OFF || torService == null
    }

    private fun registerReceivers() {
        if (statusReceiver == null) {
            statusReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    latestStatus = intent.getStringExtra(TorService.EXTRA_STATUS)
                }
            }
            localBroadcastManager.registerReceiver(
                statusReceiver!!,
                IntentFilter(TorService.ACTION_STATUS),
            )
        }
        if (errorReceiver == null) {
            errorReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    latestError = intent.getStringExtra(Intent.EXTRA_TEXT)
                        ?: "Embedded Tor reported an unknown error."
                }
            }
            localBroadcastManager.registerReceiver(
                errorReceiver!!,
                IntentFilter(TorService.ACTION_ERROR),
            )
        }
    }

    private fun unregisterReceivers() {
        statusReceiver?.let { receiver ->
            runCatching { localBroadcastManager.unregisterReceiver(receiver) }
        }
        errorReceiver?.let { receiver ->
            runCatching { localBroadcastManager.unregisterReceiver(receiver) }
        }
        statusReceiver = null
        errorReceiver = null
    }

    private suspend fun bindService(): TorService = suspendCancellableCoroutine { continuation ->
        torService?.let {
            continuation.resume(it)
            return@suspendCancellableCoroutine
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                val service = (binder as TorService.LocalBinder).service
                torService = service
                if (continuation.isActive) {
                    continuation.resume(service)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                torService = null
            }
        }
        serviceConnection = connection
        val bound = appContext.bindService(
            Intent(appContext, TorService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound && continuation.isActive) {
            continuation.resumeWithException(IllegalStateException("Could not bind embedded Tor service."))
        }
        continuation.invokeOnCancellation {
            runCatching { appContext.unbindService(connection) }
            serviceConnection = null
            torService = null
        }
    }

    private fun readBootstrapUpdate(service: TorService): BootstrapUpdate {
        val phase = service.getInfo("status/bootstrap-phase")
        val progress = phase?.let { BOOTSTRAP_PROGRESS_REGEX.find(it) }
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val summary = phase?.let { BOOTSTRAP_SUMMARY_REGEX.find(it) }
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
        val tag = phase?.let { BOOTSTRAP_TAG_REGEX.find(it) }
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
        val message = when {
            progress != null && summary != null -> "Bootstrapped $progress%: $summary"
            progress != null -> "Bootstrapped $progress%"
            phase != null -> phase
            else -> "Waiting for bootstrap status"
        }
        return BootstrapUpdate(
            progress = progress,
            phase = tag,
            summary = summary,
            message = message,
            raw = phase,
        )
    }

    private fun failIfError() {
        latestError?.let { error(it) }
    }

    data class BootstrapUpdate(
        val progress: Int?,
        val phase: String?,
        val summary: String?,
        val message: String,
        val raw: String?,
    )

    data class StartResult(
        val socksPort: Int,
        val timeoutMs: Long,
        val coldStart: Boolean,
        val stateDirectoryExisted: Boolean,
        val cacheDirectoryExisted: Boolean,
        val bootstrapStartedAt: Long,
        val bootstrapCompletedAt: Long,
        val bootstrapDurationMs: Long,
        val bootstrapUpdate: BootstrapUpdate,
    )

    data class StopResult(
        val stopRequestedAt: Long,
        val stoppedAt: Long,
        val stopDurationMs: Long,
        val torServiceBound: Boolean,
        val torRunning: Boolean,
        val latestStatus: String?,
    )

    data class ClearStateResult(
        val stateDirectoryExisted: Boolean,
        val cacheDirectoryExisted: Boolean,
        val stateDirectoryDeleted: Boolean,
        val cacheDirectoryDeleted: Boolean,
    )

    data class Snapshot(
        val torServiceBound: Boolean,
        val torRunning: Boolean,
        val latestStatus: String?,
        val stateDirectoryExists: Boolean,
        val cacheDirectoryExists: Boolean,
    )

    companion object {
        const val COLD_BOOTSTRAP_TIMEOUT_MS = 120_000L
        const val WARM_BOOTSTRAP_TIMEOUT_MS = 45_000L
        const val STOP_VERIFY_TIMEOUT_MS = 5_000L
        private const val CONTROL_CONNECTION_POLL_MS = 500L
        private const val BOOTSTRAP_POLL_MS = 1_000L
        private const val STOP_VERIFY_POLL_MS = 250L
        private const val TOR_STATE_DIR_NAME = "app_TorService"
        private val BOOTSTRAP_PROGRESS_REGEX = Regex("""PROGRESS=([0-9]{1,3})""")
        private val BOOTSTRAP_TAG_REGEX = Regex("""TAG=([^ ]+)""")
        private val BOOTSTRAP_SUMMARY_REGEX = Regex("SUMMARY=\"([^\"]+)\"")
    }
}
