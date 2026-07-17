package com.zerovpn.app.chat.retry

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class CapacityRetryRepository(
    private val prefs: SharedPreferences,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun sessions(): List<CapacityRetrySession> = sessionsFromJson(prefs.getString(KEY_SESSIONS, null))

    fun candidates(): List<PrivateChatCandidate> = candidatesFromJson(prefs.getString(KEY_CANDIDATES, null))

    fun activeSession(): CapacityRetrySession? = sessions().firstOrNull {
        it.state in setOf(
            CapacityRetryState.WAITING_FOR_RETRY,
            CapacityRetryState.ACTIVE,
            CapacityRetryState.ACQUIRING,
            CapacityRetryState.PAUSED_AUTH_REQUIRED,
            CapacityRetryState.INSTANCE_ACQUIRED,
            CapacityRetryState.RESUME_PROVISIONING_REQUIRED,
            CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED,
        )
    }

    fun session(sessionId: String): CapacityRetrySession? = sessions().firstOrNull { it.sessionId == sessionId }

    fun capacityRetryPolicyEnabled(): Boolean = prefs.getBoolean(KEY_POLICY_ENABLED, false)

    fun setCapacityRetryPolicyEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_POLICY_ENABLED, enabled).commit()
    }

    fun createSession(
        candidateId: String,
        mode: CapacityRetryMode,
        sourceExitId: String?,
        compartmentOcid: String?,
        userOcid: String? = null,
        tenancyOcid: String? = null,
        fingerprint: String? = null,
        selectedRegion: String? = null,
        tokenRegion: String? = null,
        tokenRegionSource: String? = null,
        subnetId: String? = null,
        sshPublicKey: String? = null,
        initialLaunchAttemptFinishedAtUtc: String? = null,
        initialNextEligibleAttemptAtUtc: String? = null,
        pendingMemoryGb: Int = 6,
        initialAttemptMemoryGb: Int? = null,
        initialHttpStatus: Int? = null,
        initialOciErrorCode: String? = null,
        initialLastResult: String? = null,
    ): CapacityRetrySession = synchronized(REPOSITORY_LOCK) {
        val existing = sessions().firstOrNull {
            it.candidateId == candidateId && it.blocksReplacementLaunchSession()
        }
        if (existing != null) return@synchronized existing
        val session = CapacityRetrySession.newSession(
            candidateId = candidateId,
            mode = mode,
            sourceExitId = sourceExitId,
            compartmentOcid = compartmentOcid,
            userOcid = userOcid,
            tenancyOcid = tenancyOcid,
            fingerprint = fingerprint,
            selectedRegion = selectedRegion,
            tokenRegion = tokenRegion,
            tokenRegionSource = tokenRegionSource,
            subnetId = subnetId,
            sshPublicKey = sshPublicKey,
            initialLaunchAttemptFinishedAtUtc = initialLaunchAttemptFinishedAtUtc,
            initialNextEligibleAttemptAtUtc = initialNextEligibleAttemptAtUtc,
            pendingMemoryGb = pendingMemoryGb,
            initialAttemptMemoryGb = initialAttemptMemoryGb,
            initialHttpStatus = initialHttpStatus,
            initialOciErrorCode = initialOciErrorCode,
            initialLastResult = initialLastResult,
            clock = clock,
        )
        replaceSession(session)
        session
    }

    fun replaceSession(session: CapacityRetrySession) = synchronized(REPOSITORY_LOCK) {
        val merged = (sessions().filterNot { it.sessionId == session.sessionId } + session)
            .sortedBy { it.createdAtUtc }
        prefs.edit().putString(KEY_SESSIONS, sessionsToJson(merged)).apply()
    }

    fun updateSession(
        sessionId: String,
        transform: (CapacityRetrySession) -> CapacityRetrySession,
    ): CapacityRetrySession? = synchronized(REPOSITORY_LOCK) {
        val current = session(sessionId) ?: return null
        val updated = transform(current)
        replaceSession(updated)
        updated
    }

    fun markTimedOutIfNeeded(sessionId: String): CapacityRetrySession? {
        val now = Instant.now(clock)
        return updateSession(sessionId) { session ->
            if (
                session.state in setOf(CapacityRetryState.WAITING_FOR_RETRY, CapacityRetryState.ACTIVE) &&
                !now.isBefore(Instant.parse(session.deadlineUtc))
            ) {
                session.copy(
                    state = CapacityRetryState.TIMED_OUT,
                    lastWorkerFinishedAtUtc = now.toString(),
                    terminalReason = "The fixed 24-hour retry window expired.",
                    lastResult = "TIMED_OUT",
                    requiresUserAction = true,
                )
            } else {
                session
            }
        }
    }

    fun cancelSession(sessionId: String, reason: String = "User stopped retrying."): CapacityRetrySession? =
        updateSession(sessionId) { session ->
            if (session.state == CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED) {
                session
            } else {
                session.copy(
                    state = CapacityRetryState.CANCELLED,
                    lastWorkerFinishedAtUtc = Instant.now(clock).toString(),
                    terminalReason = reason,
                    lastResult = "CANCELLED",
                    requiresUserAction = true,
                )
            }
        }

    fun beginWorkerCycle(sessionId: String): CapacityRetrySession? = synchronized(REPOSITORY_LOCK) {
        val now = Instant.now(clock)
        val current = session(sessionId) ?: return@synchronized null
        if (current.state !in setOf(CapacityRetryState.WAITING_FOR_RETRY, CapacityRetryState.ACTIVE)) {
            return@synchronized current
        }
        if (!now.isBefore(Instant.parse(current.deadlineUtc))) {
            return@synchronized markTimedOutIfNeeded(sessionId)
        }
        if (!current.isLaunchEligible(now)) return@synchronized current
        val launchGeneration = current.launchRequestCount6Gb + current.launchRequestCount4Gb + 1
        val updated = current.copy(
            state = CapacityRetryState.ACQUIRING,
            lastWorkerStartedAtUtc = now.toString(),
            preferredRetryToken = CapacityRetrySession.retryToken(current.sessionId, current.candidateId, 6, launchGeneration),
            compactRetryToken = CapacityRetrySession.retryToken(current.sessionId, current.candidateId, 4, launchGeneration),
        )
        replaceSession(updated)
        updated
    }

    internal fun finishLaunchAttempt(
        sessionId: String,
        result: BackgroundLaunchResult,
    ): CapacityRetrySession? {
        val now = Instant.now(clock)
        return updateSession(sessionId) { session ->
            if (session.state != CapacityRetryState.ACQUIRING) return@updateSession session
            val attemptedMemoryGb = normalizeA1MemoryGb(session.pendingMemoryGb)
            val finished = session.copy(
                lastWorkerFinishedAtUtc = now.toString(),
            )
            val counted = if (result is BackgroundLaunchResult.LocalPreparationFailure ||
                result is BackgroundLaunchResult.AuthenticationFailure) {
                finished
            } else {
                finished.copy(
                    launchRequestCount6Gb = session.launchRequestCount6Gb + if (attemptedMemoryGb == 6) 1 else 0,
                    launchRequestCount4Gb = session.launchRequestCount4Gb + if (attemptedMemoryGb == 4) 1 else 0,
                    lastAttemptMemoryGb = attemptedMemoryGb,
                    lastLaunchAttemptFinishedAtUtc = now.toString(),
                    nextEligibleAttemptAtUtc = nextEligibleLaunchAt(
                        finishedAt = now,
                        retryAfterSeconds = (result as? BackgroundLaunchResult.RateLimited)?.retryAfterSeconds,
                    ).toString(),
                )
            }
            when (result) {
                is BackgroundLaunchResult.Success -> counted.copy(
                    state = CapacityRetryState.INSTANCE_ACQUIRED,
                    instanceOcid = result.instanceOcid,
                    instanceDisplayName = result.displayName,
                    availabilityDomain = result.availabilityDomain,
                    lastHttpStatus = 200,
                    lastOciErrorCode = null,
                    lastSafeErrorCategory = null,
                    lastRedactedRequestId = null,
                    lastResult = "INSTANCE_ACQUIRED",
                    requiresUserAction = true,
                    terminalReason = "Open ZeroVPN to continue Private Chat setup on the acquired VM.",
                )
                is BackgroundLaunchResult.CapacityMiss -> counted.copy(
                    state = CapacityRetryState.ACTIVE,
                    retryCycleCount = session.retryCycleCount + 1,
                    pendingMemoryGb = alternateA1MemoryGb(attemptedMemoryGb),
                    lastHttpStatus = result.status,
                    lastOciErrorCode = "InternalError",
                    lastSafeErrorCategory = "capacity",
                    lastRedactedRequestId = result.requestId,
                    lastResult = "OUT_OF_HOST_CAPACITY",
                    requiresUserAction = false,
                    terminalReason = null,
                )
                is BackgroundLaunchResult.RateLimited -> counted.copy(
                    state = CapacityRetryState.ACTIVE,
                    pendingMemoryGb = attemptedMemoryGb,
                    lastHttpStatus = 429,
                    lastOciErrorCode = "TooManyRequests",
                    lastSafeErrorCategory = "rate-limited",
                    lastRedactedRequestId = null,
                    lastResult = "RATE_LIMITED",
                    requiresUserAction = false,
                    terminalReason = null,
                )
                is BackgroundLaunchResult.TerminalFailure -> counted.copy(
                    state = CapacityRetryState.FAILED_TERMINAL,
                    lastHttpStatus = result.status,
                    lastOciErrorCode = result.code,
                    lastSafeErrorCategory = result.category,
                    lastRedactedRequestId = result.requestId,
                    lastResult = "FAILED_TERMINAL",
                    requiresUserAction = true,
                    terminalReason = "Oracle rejected the VM launch request.",
                )
                is BackgroundLaunchResult.AmbiguousFailure -> counted.copy(
                    state = CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED,
                    pendingMemoryGb = attemptedMemoryGb,
                    nextEligibleAttemptAtUtc = null,
                    lastHttpStatus = result.status.takeIf { it > 0 },
                    lastOciErrorCode = null,
                    lastSafeErrorCategory = result.category,
                    lastRedactedRequestId = result.requestId,
                    lastResult = "AMBIGUOUS_FAILURE_RECONCILIATION_REQUIRED",
                    requiresUserAction = true,
                    terminalReason = AMBIGUOUS_RECONCILIATION_MESSAGE,
                )
                is BackgroundLaunchResult.LocalPreparationFailure -> counted.copy(
                    state = CapacityRetryState.FAILED_TERMINAL,
                    pendingMemoryGb = attemptedMemoryGb,
                    lastHttpStatus = null,
                    lastOciErrorCode = null,
                    lastSafeErrorCategory = result.category,
                    lastRedactedRequestId = result.diagnostics.redactedRequestId,
                    lastResult = "LOCAL_PREPARATION_FAILURE",
                    requiresUserAction = true,
                    terminalReason = "Background launch stopped before the instance request was sent (${result.category}). ${result.safeMessage}",
                )
                is BackgroundLaunchResult.AuthenticationFailure -> counted.copy(
                    state = CapacityRetryState.PAUSED_AUTH_REQUIRED,
                    pendingMemoryGb = attemptedMemoryGb,
                    lastHttpStatus = result.httpStatus,
                    lastOciErrorCode = null,
                    lastSafeErrorCategory = result.category,
                    lastRedactedRequestId = result.redactedRequestId,
                    lastResult = "OCI_AUTHENTICATION_FAILED",
                    requiresUserAction = true,
                    terminalReason = "OCI rejected the background worker\u2019s signed request (HTTP ${result.httpStatus}). Open ZeroVPN to refresh Oracle credentials.",
                )
                is BackgroundLaunchResult.TransmissionFailure -> counted.copy(
                    state = CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED,
                    pendingMemoryGb = attemptedMemoryGb,
                    nextEligibleAttemptAtUtc = null,
                    lastHttpStatus = null,
                    lastOciErrorCode = null,
                    lastSafeErrorCategory = result.category,
                    lastRedactedRequestId = result.redactedRequestId,
                    lastResult = "TRANSMISSION_FAILURE_RECONCILIATION_REQUIRED",
                    requiresUserAction = true,
                    terminalReason = AMBIGUOUS_RECONCILIATION_MESSAGE,
                )
            }
        }
    }

    fun finishCapacityMiss(sessionId: String): CapacityRetrySession? =
        finishLaunchAttempt(sessionId, BackgroundLaunchResult.CapacityMiss(500, null))

    fun createCandidate(sourceExitId: String): PrivateChatCandidate {
        val existing = candidates().firstOrNull {
            it.sourceExitId == sourceExitId && it.state !in setOf(
                DeferredCandidateState.SWITCHED,
                DeferredCandidateState.CANCELLED,
                DeferredCandidateState.FAILED,
            )
        }
        if (existing != null) return existing
        val now = Instant.now(clock).toString()
        val candidate = PrivateChatCandidate(
            candidateId = "candidate:${java.util.UUID.randomUUID()}",
            sourceExitId = sourceExitId,
            createdAtUtc = now,
        )
        replaceCandidate(candidate)
        return candidate
    }

    fun replaceCandidate(candidate: PrivateChatCandidate) = synchronized(REPOSITORY_LOCK) {
        val merged = (candidates().filterNot { it.candidateId == candidate.candidateId } + candidate)
            .sortedBy { it.createdAtUtc }
        prefs.edit().putString(KEY_CANDIDATES, candidatesToJson(merged)).apply()
    }

    companion object {
        private val REPOSITORY_LOCK = Any()
        internal const val AMBIGUOUS_RECONCILIATION_MESSAGE =
            "Background launch failed ambiguously. Open ZeroVPN to review diagnostics and reconcile before retrying."
        const val PREFS_NAME = "zerovpn_provisioning"
        const val KEY_POLICY_ENABLED = "capacity_retry_policy_enabled"
        private const val KEY_SESSIONS = "private_chat_capacity_retry_sessions_json"
        private const val KEY_CANDIDATES = "private_chat_candidates_json"

        fun fromContext(context: Context): CapacityRetryRepository = CapacityRetryRepository(
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        )
    }
}

internal fun capacityRetryInitialDelay(
    session: CapacityRetrySession,
    now: Instant,
): Duration {
    val nextEligible = session.nextEligibleAttemptAtUtc
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?: now.plus(Duration.ofMinutes(CapacityRetrySession.TARGET_INTERVAL_MINUTES))
    return Duration.between(now, nextEligible).coerceAtLeast(Duration.ZERO)
}

class CapacityRetryWorkScheduler(
    private val context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
    private val clock: Clock = Clock.systemUTC(),
) {
    fun enqueue(session: CapacityRetrySession): Boolean =
        enqueueOperation(session) != null

    suspend fun enqueueAndAwait(session: CapacityRetrySession): Boolean {
        val operation = enqueueOperation(session) ?: return false
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                operation.result.get(10, TimeUnit.SECONDS)
            }
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun enqueueOperation(session: CapacityRetrySession): Operation? {
        val leaseHeld = ProvisioningOperationLease.fromContext(context).isHeldByLiveProcess()
        if (
            !shouldEnqueueCapacityRetry(
                session = session,
                topLevelProvisioningRunning = false,
                provisioningJobActive = false,
                provisioningLeaseHeld = leaseHeld,
            )
        ) {
            return null
        }
        val initialDelay = capacityRetryInitialDelay(session, Instant.now(clock))
        val request = PeriodicWorkRequestBuilder<PrivateChatCapacityRetryWorker>(
            CapacityRetrySession.TARGET_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        )
            .setInitialDelay(initialDelay.toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInputData(workDataOf(PrivateChatCapacityRetryWorker.KEY_SESSION_ID to session.sessionId))
            .addTag(session.sessionId)
            .build()
        return workManager.enqueueUniquePeriodicWork(
            session.uniqueWorkName,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(session: CapacityRetrySession) {
        workManager.cancelUniqueWork(session.uniqueWorkName)
    }
}

