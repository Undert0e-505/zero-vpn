package com.zerovpn.app.volunteer

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import androidx.localbroadcastmanager.content.LocalBroadcastManager
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
        onBootstrapProgress: suspend (Int?) -> Unit,
    ): Int = withContext(Dispatchers.Main.immediate) {
        latestStatus = null
        latestError = null
        registerReceivers()
        val service = bindService()

        val socksPort: Int = withTimeout<Int>(BOOTSTRAP_TIMEOUT_MS) {
            while (service.torControlConnection == null) {
                failIfError()
                delay(500)
            }

            var lastProgress: Int? = null
            var discoveredSocksPort: Int? = null
            while (discoveredSocksPort == null) {
                failIfError()
                val progress = readBootstrapProgress(service)
                if (progress != lastProgress) {
                    lastProgress = progress
                    onBootstrapProgress(progress)
                }
                val socksPort = service.socksPort
                if (
                    socksPort > 0 &&
                    (latestStatus == TorService.STATUS_ON || progress == 100)
                ) {
                    discoveredSocksPort = socksPort
                } else {
                    delay(1000)
                }
            }
            discoveredSocksPort
        }
        socksPort
    }

    suspend fun stop() {
        withContext(Dispatchers.Main.immediate) {
            unregisterReceivers()
            serviceConnection?.let { connection ->
                runCatching { appContext.unbindService(connection) }
            }
            serviceConnection = null
            torService = null
            latestStatus = null
            latestError = null
        }
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

    private fun readBootstrapProgress(service: TorService): Int? {
        val phase = service.getInfo("status/bootstrap-phase") ?: return null
        return BOOTSTRAP_PROGRESS_REGEX.find(phase)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun failIfError() {
        latestError?.let { error(it) }
    }

    companion object {
        private const val BOOTSTRAP_TIMEOUT_MS = 180_000L
        private val BOOTSTRAP_PROGRESS_REGEX = Regex("""PROGRESS=([0-9]{1,3})""")
    }
}
