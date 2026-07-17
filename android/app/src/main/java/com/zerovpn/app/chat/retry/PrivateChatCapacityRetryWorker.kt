package com.zerovpn.app.chat.retry

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zerovpn.app.MainActivity
import com.zerovpn.app.storage.SecureSecretStore
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Instant

internal data class PrivateChatCapacityRetryWorkerDependencies(
    val repository: CapacityRetryRepository,
    val vault: RetryCredentialVault,
    val provisioningLease: ProvisioningOperationLease,
    val diagnosticLog: CapacityRetryDiagnosticLog,
    val notifier: CapacityRetryNotifier,
    val launcher: BackgroundLaunchExecutor,
    val workScheduler: CapacityRetryWorkScheduler,
)

internal fun privateChatCapacityRetryWorkerDependencies(context: Context): PrivateChatCapacityRetryWorkerDependencies {
    val appContext = context.applicationContext
    return PrivateChatCapacityRetryWorkerDependencies(
        repository = CapacityRetryRepository.fromContext(appContext),
        vault = RetryCredentialVault(SecureSecretStore(appContext)),
        provisioningLease = ProvisioningOperationLease.fromContext(appContext),
        diagnosticLog = CapacityRetryDiagnosticLog.fromContext(appContext),
        notifier = CapacityRetryNotifier(appContext),
        launcher = OciBackgroundLauncher(),
        workScheduler = CapacityRetryWorkScheduler(appContext),
    )
}

class PrivateChatCapacityRetryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private var dependencyOverride: PrivateChatCapacityRetryWorkerDependencies? = null

    internal constructor(
        appContext: Context,
        params: WorkerParameters,
        dependencies: PrivateChatCapacityRetryWorkerDependencies,
    ) : this(appContext, params) {
        dependencyOverride = dependencies
    }

    internal fun loadDependenciesFromApplicationContext(): PrivateChatCapacityRetryWorkerDependencies =
        dependencyOverride ?: privateChatCapacityRetryWorkerDependencies(applicationContext)

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
        val dependencies = loadDependenciesFromApplicationContext()
        val repository = dependencies.repository
        val vault = dependencies.vault
        val notifier = dependencies.notifier
        val launcher = dependencies.launcher
        val workScheduler = dependencies.workScheduler
        val provisioningLease = dependencies.provisioningLease
        val diagnosticLog = dependencies.diagnosticLog
        fun record(message: String) = diagnosticLog.append(sessionId, message)

        record("Worker started for session " + sessionId + " at " + Instant.now(Clock.systemUTC()))
        val current = repository.markTimedOutIfNeeded(sessionId)
        if (current == null) {
            record("Session updated: session not found")
            return Result.success()
        }
        if (current.state == CapacityRetryState.TIMED_OUT) {
            record("Cooldown check: not eligible (retry deadline expired)")
            record("Session updated: new state " + current.state.name)
            vault.clearCredentials(sessionId)
            workScheduler.cancel(current)
            notifier.notifyTimedOut(current)
            return Result.success()
        }
        val initiallyEligible = current.isLaunchEligible(Instant.now(Clock.systemUTC()))
        record("Cooldown check: " + if (initiallyEligible) "eligible" else "not eligible")
        if (!initiallyEligible) {
            return Result.success()
        }
        if (!shouldRunCapacityRetryWorker(current, provisioningLease.isHeldByLiveProcess())) {
            record("Lease check: denied")
            return Result.success()
        }
        if (!provisioningLease.tryAcquire(ProvisioningLeaseOperation.BACKGROUND_CAPACITY_RETRY)) {
            record("Lease check: denied")
            return Result.success()
        }
        record("Lease check: acquired")

        try {
            val locked = repository.markTimedOutIfNeeded(sessionId) ?: return Result.success()
            val stillEligible = locked.isLaunchEligible(Instant.now(Clock.systemUTC()))
            if (!stillEligible) {
                record("Cooldown check: not eligible after lease acquisition")
                return Result.success()
            }
            if (!shouldRunCapacityRetryWorker(locked, provisioningLeaseHeld = false)) {
                return Result.success()
            }
            val started = repository.beginWorkerCycle(sessionId) ?: return Result.success()
            if (started.state == CapacityRetryState.TIMED_OUT) {
                record("Session updated: new state " + started.state.name)
                vault.clearCredentials(sessionId)
                workScheduler.cancel(started)
                notifier.notifyTimedOut(started)
                return Result.success()
            }
            if (started.state != CapacityRetryState.ACQUIRING) return Result.success()
            record("Session updated: new state " + started.state.name)

            val loaded = vault.loadCredentials(sessionId)
            if (loaded == null) {
                record("Credential load: failure")
                val paused = pauseAuthRequired(repository, sessionId)
                if (paused?.state == CapacityRetryState.PAUSED_AUTH_REQUIRED) {
                    record("Session updated: new state " + paused.state.name)
                    notifier.notifyAuthRequired(paused)
                }
                return Result.success()
            }

            val privateKey = runCatching { vault.loadPrivateKey(loaded.privateKeyPem) }.getOrElse {
                record("Credential load: failure")
                val paused = pauseAuthRequired(repository, sessionId)
                if (paused?.state == CapacityRetryState.PAUSED_AUTH_REQUIRED) {
                    record("Session updated: new state " + paused.state.name)
                    notifier.notifyAuthRequired(paused)
                }
                return Result.success()
            }
            record("Credential load: success")

            val missingContext = listOfNotNull(
                "userOcid".takeIf { started.userOcid.isNullOrBlank() },
                "tenancyOcid".takeIf { started.tenancyOcid.isNullOrBlank() },
                "fingerprint".takeIf { started.fingerprint.isNullOrBlank() },
                "selectedRegion".takeIf { started.selectedRegion.isNullOrBlank() },
                "compartmentOcid".takeIf { started.compartmentOcid.isNullOrBlank() },
                "subnetId".takeIf { started.subnetId.isNullOrBlank() },
                "sshPublicKey".takeIf { started.sshPublicKey.isNullOrBlank() },
            )
            if (missingContext.isNotEmpty()) {
                record("Launch result: other (missing launch context)")
                val failed = updateAcquiringSession(repository, sessionId) { session ->
                    session.copy(
                        state = CapacityRetryState.FAILED_TERMINAL,
                        lastWorkerFinishedAtUtc = Instant.now(Clock.systemUTC()).toString(),
                        lastResult = "MISSING_LAUNCH_CONTEXT",
                        lastSafeErrorCategory = "invalid-launch-context",
                        requiresUserAction = true,
                        terminalReason = "Background retry is missing required non-secret launch context: ${missingContext.joinToString()}.",
                    )
                }
                if (failed?.state == CapacityRetryState.FAILED_TERMINAL) {
                    record("Session updated: new state " + failed.state.name)
                    workScheduler.cancel(failed)
                    notifier.notifyTerminalFailure(failed)
                    vault.clearCredentials(sessionId)
                }
                return Result.success()
            }

            record("Launch attempt: started")
            val retryToken = if (started.pendingMemoryGb == 4) {
                started.compactRetryToken
            } else {
                started.preferredRetryToken
            }
            val result = try {
                launcher.launchA1Instance(
                    credentials = BackgroundLaunchCredentials(
                        securityToken = loaded.securityToken,
                        privateKey = privateKey,
                        tenancyOcid = started.tenancyOcid!!,
                        userOcid = started.userOcid!!,
                        fingerprint = started.fingerprint!!,
                    ),
                    params = BackgroundLaunchParams(
                        compartmentOcid = started.compartmentOcid!!,
                        region = started.selectedRegion!!,
                        subnetId = started.subnetId!!,
                        sshPublicKey = started.sshPublicKey!!,
                    ),
                    pendingMemoryGb = started.pendingMemoryGb,
                    retryToken = retryToken,
                    sessionId = sessionId,
                )
            } catch (cancelled: CancellationException) {
                record("Launch result: other (cancelled; reconciliation required)")
                val ambiguous = updateAcquiringSession(repository, sessionId) { session ->
                    session.copy(
                        state = CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED,
                        lastWorkerFinishedAtUtc = Instant.now(Clock.systemUTC()).toString(),
                        lastResult = "CANCELLED_DURING_LAUNCH_RECONCILIATION_REQUIRED",
                        lastSafeErrorCategory = "ambiguous-cancelled-launch",
                        requiresUserAction = true,
                        terminalReason = "Open ZeroVPN to reconcile the interrupted Oracle launch before retrying.",
                    )
                }
                ambiguous?.let { record("Session updated: new state " + it.state.name) }
                throw cancelled
            } catch (error: Exception) {
                val diagnosticInfo = LaunchFailureDiagnostics.capture(
                    error = error,
                    failingOperation = "unknown-launch-operation",
                    retryToken = retryToken,
                    sessionId = sessionId,
                )
                record("Launch result: ${diagnosticInfo.summaryLine()}")
                diagnosticLog.appendFailure(sessionId, diagnosticInfo)
                val ambiguous = updateAcquiringSession(repository, sessionId) { session ->
                    session.copy(
                        state = CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED,
                        lastWorkerFinishedAtUtc = Instant.now(Clock.systemUTC()).toString(),
                        lastResult = "AMBIGUOUS_EXCEPTION_RECONCILIATION_REQUIRED",
                        lastSafeErrorCategory = diagnosticInfo.safeCategory(),
                        requiresUserAction = true,
                        terminalReason = CapacityRetryRepository.AMBIGUOUS_RECONCILIATION_MESSAGE,
                        nextEligibleAttemptAtUtc = null,
                        lastHttpStatus = null,
                        lastOciErrorCode = null,
                        lastRedactedRequestId = null,
                    )
                }
                ambiguous?.let { record("Session updated: new state " + it.state.name) }
                if (ambiguous != null) {
                    workScheduler.cancel(ambiguous)
                    notifier.notifyAmbiguousReconciliationRequired(ambiguous)
                }
                return Result.success()
            }

            result.failureDiagnosticsOrNull()?.let { diagnosticInfo ->
                record("Launch result: ${diagnosticInfo.summaryLine()}")
                diagnosticLog.appendFailure(sessionId, diagnosticInfo)
            }

            record(
                "Launch result: " + when (result) {
                    is BackgroundLaunchResult.Success -> "success"
                    is BackgroundLaunchResult.CapacityMiss -> "capacity-miss"
                    is BackgroundLaunchResult.RateLimited -> "rate-limited"
                    is BackgroundLaunchResult.TerminalFailure -> "other (terminal)"
                    is BackgroundLaunchResult.AmbiguousFailure -> "other (ambiguous)"
                    is BackgroundLaunchResult.LocalPreparationFailure -> "other (local preparation)"
                    is BackgroundLaunchResult.TransmissionFailure -> "other (transmission ambiguous)"
                },
            )
            val updated = repository.finishLaunchAttempt(sessionId, result)
            updated?.let { record("Session updated: new state " + it.state.name) }
            when (updated?.state) {
                CapacityRetryState.INSTANCE_ACQUIRED -> {
                    workScheduler.cancel(updated)
                    notifier.notifyInstanceAcquired()
                }
                CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED -> {
                    workScheduler.cancel(updated)
                    notifier.notifyAmbiguousReconciliationRequired(updated)
                }
                CapacityRetryState.FAILED_TERMINAL -> {
                    workScheduler.cancel(updated)
                    notifier.notifyTerminalFailure(updated)
                    vault.clearCredentials(sessionId)
                }
                else -> Unit
            }
            return Result.success()
        } finally {
            provisioningLease.release()
        }
    }

    private fun pauseAuthRequired(repository: CapacityRetryRepository, sessionId: String): CapacityRetrySession? =
        updateAcquiringSession(repository, sessionId) { session ->
            session.copy(
                state = CapacityRetryState.PAUSED_AUTH_REQUIRED,
                lastWorkerFinishedAtUtc = Instant.now(Clock.systemUTC()).toString(),
                lastResult = "PAUSED_AUTH_REQUIRED",
                lastSafeErrorCategory = "auth-required",
                requiresUserAction = true,
                terminalReason = "Open ZeroVPN to refresh Oracle signing credentials before background retry can continue.",
            )
        }

    private fun updateAcquiringSession(
        repository: CapacityRetryRepository,
        sessionId: String,
        transform: (CapacityRetrySession) -> CapacityRetrySession,
    ): CapacityRetrySession? = repository.updateSession(sessionId) { session ->
        if (session.state == CapacityRetryState.ACQUIRING) transform(session) else session
    }

    companion object {
        const val KEY_SESSION_ID = "capacity_retry_session_id"
    }
}

class CapacityRetryNotifier(private val context: Context) {
    fun notifyTimedOut(session: CapacityRetrySession) {
        notify(
            NOTIFICATION_TIMEOUT,
            "Private Chat retry timed out",
            "ZeroVPN stopped after ${session.retryCycleCount} background retry attempts.",
        )
    }

    fun notifyAuthRequired(session: CapacityRetrySession) {
        notify(
            NOTIFICATION_AUTH_REQUIRED,
            "Private Chat retry paused",
            "Open ZeroVPN to refresh Oracle access before retrying.",
        )
    }

    fun notifyInstanceAcquired() {
        notify(
            NOTIFICATION_INSTANCE_ACQUIRED,
            "Oracle VM acquired",
            "Open ZeroVPN to continue Private Chat setup.",
        )
    }

    fun notifyAmbiguousReconciliationRequired(session: CapacityRetrySession) {
        notify(
            NOTIFICATION_AMBIGUOUS_RECONCILIATION,
            "Private Chat launch needs reconciliation",
            session.terminalReason ?: CapacityRetryRepository.AMBIGUOUS_RECONCILIATION_MESSAGE,
            includeDiagnosticsAction = true,
        )
    }

    fun notifyTerminalFailure(session: CapacityRetrySession) {
        notify(
            NOTIFICATION_TERMINAL_FAILURE,
            "Private Chat retry stopped",
            session.terminalReason ?: "Background launch stopped before an Oracle instance request was sent.",
            includeDiagnosticsAction = true,
        )
    }

    private fun notify(
        id: Int,
        title: String,
        text: String,
        includeDiagnosticsAction: Boolean = false,
    ) {
        ensureChannel()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setLocalOnly(true)
            .setAutoCancel(true)
        if (includeDiagnosticsAction) {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_VIEW_DIAGNOSTICS
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_DIAGNOSTICS_REQUEST,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_menu_info_details, "View diagnostics", pendingIntent)
        }
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Private Chat status", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Private Chat capacity retry status changes"
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "private_chat_capacity_retry"
        private const val NOTIFICATION_INSTANCE_ACQUIRED = 5201
        private const val NOTIFICATION_AUTH_REQUIRED = 5202
        private const val NOTIFICATION_TIMEOUT = 5203
        private const val NOTIFICATION_AMBIGUOUS_RECONCILIATION = 5204
        private const val NOTIFICATION_TERMINAL_FAILURE = 5205
        private const val NOTIFICATION_DIAGNOSTICS_REQUEST = 5299
    }
}
