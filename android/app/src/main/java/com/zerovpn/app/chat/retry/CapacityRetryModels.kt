package com.zerovpn.app.chat.retry

import org.json.JSONArray
import org.json.JSONObject
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class CapacityRetryState {
    NONE,
    WAITING_FOR_RETRY,
    ACTIVE,
    PAUSED_AUTH_REQUIRED,
    ACQUIRING,
    INSTANCE_ACQUIRED,
    RESUME_PROVISIONING_REQUIRED,
    SUCCEEDED,
    TIMED_OUT,
    CANCELLED,
    FAILED_TERMINAL,
    FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED,
}

enum class CapacityRetryMode {
    INITIAL_PRIVATE_CHAT,
    DEFERRED_PRIVATE_CHAT_CANDIDATE,
}

enum class PrivateChatCapabilityStatus {
    NOT_REQUESTED,
    REQUESTED,
    INSTALLING,
    HEALTHY,
    FAILED,
    DEFERRED,
    CANDIDATE_ACQUIRING,
    CANDIDATE_PROVISIONING,
    READY_TO_SWITCH,
    SWITCH_FAILED,
}

enum class DeferredCandidateState {
    NONE,
    ACQUIRING_CAPACITY,
    PROVISIONING,
    TESTING,
    READY_TO_SWITCH,
    FAILED,
    SWITCHED,
    CANCELLED,
}

data class PrivateChatCandidate(
    val candidateId: String,
    val sourceExitId: String,
    val candidateExitId: String? = null,
    val state: DeferredCandidateState = DeferredCandidateState.ACQUIRING_CAPACITY,
    val createdAtUtc: String,
    val updatedAtUtc: String = createdAtUtc,
    val readyToSwitchAtUtc: String? = null,
    val lastError: String? = null,
)

data class CapacityRetrySession(
    val sessionId: String,
    val candidateId: String,
    val mode: CapacityRetryMode,
    val state: CapacityRetryState,
    val createdAtUtc: String,
    val deadlineUtc: String,
    val lastLaunchAttemptFinishedAtUtc: String? = null,
    val nextEligibleAttemptAtUtc: String? = null,
    val pendingMemoryGb: Int = 6,
    val lastWorkerStartedAtUtc: String? = null,
    val lastWorkerFinishedAtUtc: String? = null,
    val retryCycleCount: Int = 0,
    val launchRequestCount6Gb: Int = 0,
    val launchRequestCount4Gb: Int = 0,
    val lastAttemptMemoryGb: Int? = null,
    val lastHttpStatus: Int? = null,
    val lastOciErrorCode: String? = null,
    val lastSafeErrorCategory: String? = null,
    val lastRedactedRequestId: String? = null,
    val lastResult: String? = null,
    val uniqueWorkName: String,
    val preferredRetryToken: String,
    val compactRetryToken: String,
    val instanceOcid: String? = null,
    val instanceDisplayName: String? = null,
    val availabilityDomain: String? = null,
    val compartmentOcid: String? = null,
    val userOcid: String? = null,
    val tenancyOcid: String? = null,
    val fingerprint: String? = null,
    val selectedRegion: String? = null,
    val tokenRegion: String? = null,
    val tokenRegionSource: String? = null,
    val subnetId: String? = null,
    val sshPublicKey: String? = null,
    val sourceExitId: String? = null,
    val requiresUserAction: Boolean = false,
    val terminalReason: String? = null,
) {
    fun isTerminal(): Boolean = state in setOf(
        CapacityRetryState.SUCCEEDED,
        CapacityRetryState.TIMED_OUT,
        CapacityRetryState.CANCELLED,
        CapacityRetryState.FAILED_TERMINAL,
        CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED,
    )

    fun remaining(now: Instant): Duration = Duration.between(now, Instant.parse(deadlineUtc)).coerceAtLeast(Duration.ZERO)

    fun toJson(): JSONObject = JSONObject()
        .put("sessionId", sessionId)
        .put("candidateId", candidateId)
        .put("mode", mode.name)
        .put("state", state.name)
        .put("createdAtUtc", createdAtUtc)
        .put("deadlineUtc", deadlineUtc)
        .put("lastLaunchAttemptFinishedAtUtc", lastLaunchAttemptFinishedAtUtc)
        .put("nextEligibleAttemptAtUtc", nextEligibleAttemptAtUtc)
        .put("pendingMemoryGb", pendingMemoryGb)
        .put("lastWorkerStartedAtUtc", lastWorkerStartedAtUtc)
        .put("lastWorkerFinishedAtUtc", lastWorkerFinishedAtUtc)
        .put("retryCycleCount", retryCycleCount)
        .put("launchRequestCount6Gb", launchRequestCount6Gb)
        .put("launchRequestCount4Gb", launchRequestCount4Gb)
        .put("lastAttemptMemoryGb", lastAttemptMemoryGb)
        .put("lastHttpStatus", lastHttpStatus)
        .put("lastOciErrorCode", lastOciErrorCode)
        .put("lastSafeErrorCategory", lastSafeErrorCategory)
        .put("lastRedactedRequestId", lastRedactedRequestId)
        .put("lastResult", lastResult)
        .put("uniqueWorkName", uniqueWorkName)
        .put("preferredRetryToken", preferredRetryToken)
        .put("compactRetryToken", compactRetryToken)
        .put("instanceOcid", instanceOcid)
        .put("instanceDisplayName", instanceDisplayName)
        .put("availabilityDomain", availabilityDomain)
        .put("compartmentOcid", compartmentOcid)
        .put("userOcid", userOcid)
        .put("tenancyOcid", tenancyOcid)
        .put("fingerprint", fingerprint)
        .put("selectedRegion", selectedRegion)
        .put("tokenRegion", tokenRegion)
        .put("tokenRegionSource", tokenRegionSource)
        .put("subnetId", subnetId)
        .put("sshPublicKey", sshPublicKey)
        .put("sourceExitId", sourceExitId)
        .put("requiresUserAction", requiresUserAction)
        .put("terminalReason", terminalReason)

    companion object {
        const val TARGET_INTERVAL_MINUTES = 15L
        const val RETRY_WINDOW_HOURS = 24L

        fun newSession(
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
            clock: Clock = Clock.systemUTC(),
        ): CapacityRetrySession {
            val now = Instant.now(clock)
            val startedAt = initialLaunchAttemptFinishedAtUtc
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: now
            val normalizedPendingMemoryGb = normalizeA1MemoryGb(pendingMemoryGb)
            val normalizedAttemptMemoryGb = initialAttemptMemoryGb
                ?.let(::normalizeA1MemoryGb)
            val minimumNextEligibleAt = nextEligibleLaunchAt(startedAt)
            val requestedNextEligibleAt = initialNextEligibleAttemptAtUtc
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val nextEligibleAt = requestedNextEligibleAt
                ?.takeIf { it.isAfter(minimumNextEligibleAt) }
                ?: minimumNextEligibleAt
            val sessionId = "retry:${UUID.randomUUID()}"
            return CapacityRetrySession(
                sessionId = sessionId,
                candidateId = candidateId,
                mode = mode,
                state = CapacityRetryState.WAITING_FOR_RETRY,
                createdAtUtc = startedAt.toString(),
                deadlineUtc = startedAt.plus(Duration.ofHours(RETRY_WINDOW_HOURS)).toString(),
                lastLaunchAttemptFinishedAtUtc = initialLaunchAttemptFinishedAtUtc,
                nextEligibleAttemptAtUtc = nextEligibleAt.toString(),
                pendingMemoryGb = normalizedPendingMemoryGb,
                launchRequestCount6Gb = if (normalizedAttemptMemoryGb == 6) 1 else 0,
                launchRequestCount4Gb = if (normalizedAttemptMemoryGb == 4) 1 else 0,
                lastAttemptMemoryGb = normalizedAttemptMemoryGb,
                lastHttpStatus = initialHttpStatus,
                lastOciErrorCode = initialOciErrorCode,
                lastSafeErrorCategory = when (initialLastResult) {
                    "RATE_LIMITED" -> "rate-limited"
                    "OUT_OF_HOST_CAPACITY" -> "capacity"
                    else -> null
                },
                lastResult = initialLastResult,
                uniqueWorkName = uniqueWorkName(sessionId),
                preferredRetryToken = retryToken(sessionId, candidateId, 6, 0),
                compactRetryToken = retryToken(sessionId, candidateId, 4, 0),
                compartmentOcid = compartmentOcid,
                userOcid = userOcid,
                tenancyOcid = tenancyOcid,
                fingerprint = fingerprint,
                selectedRegion = selectedRegion,
                tokenRegion = tokenRegion,
                tokenRegionSource = tokenRegionSource,
                subnetId = subnetId,
                sshPublicKey = sshPublicKey,
                sourceExitId = sourceExitId,
            )
        }

        fun uniqueWorkName(sessionId: String): String = "private-chat-capacity-$sessionId"
        fun retryToken(sessionId: String, candidateId: String, memoryGb: Int, generation: Int): String =
            "zerovpn-$candidateId-$sessionId-${memoryGb}gb-$generation"

        fun fromJson(json: JSONObject): CapacityRetrySession = CapacityRetrySession(
            sessionId = json.getString("sessionId"),
            candidateId = json.getString("candidateId"),
            mode = enumValueOf(json.optString("mode", CapacityRetryMode.INITIAL_PRIVATE_CHAT.name)),
            state = enumValueOf(json.optString("state", CapacityRetryState.NONE.name)),
            createdAtUtc = json.getString("createdAtUtc"),
            deadlineUtc = json.getString("deadlineUtc"),
            lastLaunchAttemptFinishedAtUtc = json.optNullableString("lastLaunchAttemptFinishedAtUtc"),
            nextEligibleAttemptAtUtc = json.optNullableString("nextEligibleAttemptAtUtc"),
            pendingMemoryGb = normalizeA1MemoryGb(json.optInt("pendingMemoryGb", 6)),
            lastWorkerStartedAtUtc = json.optNullableString("lastWorkerStartedAtUtc"),
            lastWorkerFinishedAtUtc = json.optNullableString("lastWorkerFinishedAtUtc"),
            retryCycleCount = json.optInt("retryCycleCount", 0),
            launchRequestCount6Gb = json.optInt("launchRequestCount6Gb", 0),
            launchRequestCount4Gb = json.optInt("launchRequestCount4Gb", 0),
            lastAttemptMemoryGb = json.optIntOrNull("lastAttemptMemoryGb"),
            lastHttpStatus = json.optIntOrNull("lastHttpStatus"),
            lastOciErrorCode = json.optNullableString("lastOciErrorCode"),
            lastSafeErrorCategory = json.optNullableString("lastSafeErrorCategory"),
            lastRedactedRequestId = json.optNullableString("lastRedactedRequestId"),
            lastResult = json.optNullableString("lastResult"),
            uniqueWorkName = json.optString("uniqueWorkName").takeIf { it.isNotBlank() } ?: uniqueWorkName(json.getString("sessionId")),
            preferredRetryToken = json.getString("preferredRetryToken"),
            compactRetryToken = json.getString("compactRetryToken"),
            instanceOcid = json.optNullableString("instanceOcid"),
            instanceDisplayName = json.optNullableString("instanceDisplayName"),
            availabilityDomain = json.optNullableString("availabilityDomain"),
            compartmentOcid = json.optNullableString("compartmentOcid"),
            userOcid = json.optNullableString("userOcid"),
            tenancyOcid = json.optNullableString("tenancyOcid"),
            fingerprint = json.optNullableString("fingerprint"),
            selectedRegion = json.optNullableString("selectedRegion"),
            tokenRegion = json.optNullableString("tokenRegion"),
            tokenRegionSource = json.optNullableString("tokenRegionSource"),
            subnetId = json.optNullableString("subnetId"),
            sshPublicKey = json.optNullableString("sshPublicKey"),
            sourceExitId = json.optNullableString("sourceExitId"),
            requiresUserAction = json.optBoolean("requiresUserAction", false),
            terminalReason = json.optNullableString("terminalReason"),
        )
    }
}

internal fun CapacityRetrySession.blocksReplacementLaunchSession(): Boolean =
    !isTerminal() || state == CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED

internal fun CapacityRetrySession.keepsPrivateChatRequested(): Boolean =
    state in setOf(
        CapacityRetryState.WAITING_FOR_RETRY,
        CapacityRetryState.ACTIVE,
        CapacityRetryState.ACQUIRING,
        CapacityRetryState.PAUSED_AUTH_REQUIRED,
        CapacityRetryState.INSTANCE_ACQUIRED,
        CapacityRetryState.RESUME_PROVISIONING_REQUIRED,
        CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED,
    )

internal fun List<CapacityRetrySession>.hasAuthoritativePrivateChatRetry(): Boolean =
    any(CapacityRetrySession::keepsPrivateChatRequested)

internal fun normalizeA1MemoryGb(memoryGb: Int): Int = if (memoryGb == 4) 4 else 6

internal fun alternateA1MemoryGb(memoryGb: Int): Int = if (normalizeA1MemoryGb(memoryGb) == 6) 4 else 6

internal fun nextEligibleLaunchAt(
    finishedAt: Instant,
    retryAfterSeconds: Long? = null,
): Instant {
    val cadence = finishedAt.plus(Duration.ofMinutes(CapacityRetrySession.TARGET_INTERVAL_MINUTES))
    val retryAfter = retryAfterSeconds
        ?.coerceAtLeast(0L)
        ?.let(finishedAt::plusSeconds)
    return if (retryAfter != null && retryAfter.isAfter(cadence)) retryAfter else cadence
}

internal fun CapacityRetrySession.cooldownRemaining(now: Instant): Duration {
    val next = nextEligibleAttemptAtUtc
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?: return Duration.ZERO
    return Duration.between(now, next).coerceAtLeast(Duration.ZERO)
}

internal fun CapacityRetrySession.isLaunchEligible(now: Instant): Boolean {
    if (state !in setOf(CapacityRetryState.WAITING_FOR_RETRY, CapacityRetryState.ACTIVE)) return false
    val deadline = runCatching { Instant.parse(deadlineUtc) }.getOrNull() ?: return false
    if (!now.isBefore(deadline)) return false
    return cooldownRemaining(now).isZero
}

fun PrivateChatCandidate.toJson(): JSONObject = JSONObject()
    .put("candidateId", candidateId)
    .put("sourceExitId", sourceExitId)
    .put("candidateExitId", candidateExitId)
    .put("state", state.name)
    .put("createdAtUtc", createdAtUtc)
    .put("updatedAtUtc", updatedAtUtc)
    .put("readyToSwitchAtUtc", readyToSwitchAtUtc)
    .put("lastError", lastError)

fun privateChatCandidateFromJson(json: JSONObject): PrivateChatCandidate = PrivateChatCandidate(
    candidateId = json.getString("candidateId"),
    sourceExitId = json.getString("sourceExitId"),
    candidateExitId = json.optNullableString("candidateExitId"),
    state = enumValueOf(json.optString("state", DeferredCandidateState.ACQUIRING_CAPACITY.name)),
    createdAtUtc = json.getString("createdAtUtc"),
    updatedAtUtc = json.optString("updatedAtUtc", json.getString("createdAtUtc")),
    readyToSwitchAtUtc = json.optNullableString("readyToSwitchAtUtc"),
    lastError = json.optNullableString("lastError"),
)

internal fun JSONObject.optNullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

internal fun JSONObject.optIntOrNull(name: String): Int? =
    if (!has(name) || isNull(name)) null else optInt(name)

internal fun Duration.coerceAtLeast(minimum: Duration): Duration = if (this < minimum) minimum else this

fun sessionsToJson(sessions: List<CapacityRetrySession>): String = JSONArray().also { array ->
    sessions.forEach { array.put(it.toJson()) }
}.toString()

fun sessionsFromJson(raw: String?): List<CapacityRetrySession> = runCatching {
    val array = JSONArray(raw ?: return emptyList())
    buildList {
        for (i in 0 until array.length()) add(CapacityRetrySession.fromJson(array.getJSONObject(i)))
    }
}.getOrDefault(emptyList())

fun candidatesToJson(candidates: List<PrivateChatCandidate>): String = JSONArray().also { array ->
    candidates.forEach { array.put(it.toJson()) }
}.toString()

fun candidatesFromJson(raw: String?): List<PrivateChatCandidate> = runCatching {
    val array = JSONArray(raw ?: return emptyList())
    buildList {
        for (i in 0 until array.length()) add(privateChatCandidateFromJson(array.getJSONObject(i)))
    }
}.getOrDefault(emptyList())

