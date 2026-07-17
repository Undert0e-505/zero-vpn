package com.zerovpn.app.chat.retry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class CapacityRetryModelTest {
    private val fixedClock = Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC)

    @Test fun newSessionUsesFixedTwentyFourHourDeadline() {
        val session = newSession()
        assertEquals("2026-07-15T12:00:00Z", session.createdAtUtc)
        assertEquals("2026-07-16T12:00:00Z", session.deadlineUtc)
    }

    @Test fun newSessionUsesFifteenMinuteTargetCadence() {
        val session = newSession()
        assertEquals("2026-07-15T12:15:00Z", session.nextEligibleAttemptAtUtc)
        assertEquals(15L, CapacityRetrySession.TARGET_INTERVAL_MINUTES)
    }

    @Test fun newSessionNeverAcceptsAnEligibilityTimeEarlierThanFifteenMinutes() {
        val session = CapacityRetrySession.newSession(
            candidateId = "candidate:1",
            mode = CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            sourceExitId = null,
            compartmentOcid = "ocid1.tenancy.oc1..example",
            initialLaunchAttemptFinishedAtUtc = "2026-07-15T12:00:00Z",
            initialNextEligibleAttemptAtUtc = "2026-07-15T12:00:01Z",
            pendingMemoryGb = 4,
            clock = fixedClock,
        )

        assertEquals("2026-07-15T12:15:00Z", session.nextEligibleAttemptAtUtc)
    }

    @Test fun retryWindowConstantIsTwentyFourHours() {
        assertEquals(24L, CapacityRetrySession.RETRY_WINDOW_HOURS)
    }

    @Test fun newSessionWaitsForFirstBackgroundIntervalWithNoCycles() {
        val session = newSession()
        assertEquals(CapacityRetryState.WAITING_FOR_RETRY, session.state)
        assertEquals(6, session.pendingMemoryGb)
        assertEquals(0, session.retryCycleCount)
        assertEquals(0, session.launchRequestCount6Gb)
        assertEquals(0, session.launchRequestCount4Gb)
    }

    @Test fun foregroundCapacityTimestampAnchorsDeadlineAndPendingFourGb() {
        val session = CapacityRetrySession.newSession(
            candidateId = "candidate:1",
            mode = CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            sourceExitId = null,
            compartmentOcid = "ocid1.tenancy.oc1..example",
            initialLaunchAttemptFinishedAtUtc = "2026-07-15T11:55:00Z",
            initialNextEligibleAttemptAtUtc = "2026-07-15T12:10:00Z",
            pendingMemoryGb = 4,
            initialAttemptMemoryGb = 6,
            initialHttpStatus = 500,
            initialOciErrorCode = "InternalError",
            initialLastResult = "OUT_OF_HOST_CAPACITY",
            clock = fixedClock,
        )

        assertEquals("2026-07-15T11:55:00Z", session.createdAtUtc)
        assertEquals("2026-07-16T11:55:00Z", session.deadlineUtc)
        assertEquals("2026-07-15T11:55:00Z", session.lastLaunchAttemptFinishedAtUtc)
        assertEquals("2026-07-15T12:10:00Z", session.nextEligibleAttemptAtUtc)
        assertEquals(4, session.pendingMemoryGb)
        assertEquals(1, session.launchRequestCount6Gb)
    }

    @Test fun pendingMemoryAlternatesOnlyAfterCapacityMiss() {
        assertEquals(4, alternateA1MemoryGb(6))
        assertEquals(6, alternateA1MemoryGb(4))
    }

    @Test fun nextEligibleAttemptHonoursLongerRetryAfter() {
        val finishedAt = Instant.parse("2026-07-15T12:00:00Z")
        assertEquals(
            Instant.parse("2026-07-15T12:15:00Z"),
            nextEligibleLaunchAt(finishedAt, retryAfterSeconds = 60),
        )
        assertEquals(
            Instant.parse("2026-07-15T12:30:00Z"),
            nextEligibleLaunchAt(finishedAt, retryAfterSeconds = 1_800),
        )
    }

    @Test fun launchEligibilityUsesPersistedCooldownAndDeadline() {
        val session = newSession()
        assertFalse(session.isLaunchEligible(Instant.parse("2026-07-15T12:14:59Z")))
        assertTrue(session.isLaunchEligible(Instant.parse("2026-07-15T12:15:00Z")))
        assertFalse(session.isLaunchEligible(Instant.parse("2026-07-16T12:00:00Z")))
    }

    @Test fun preferredAndCompactRetryTokensAreDistinct() {
        val session = newSession()
        assertNotEquals(session.preferredRetryToken, session.compactRetryToken)
        assertTrue(session.preferredRetryToken.contains("6gb"))
        assertTrue(session.compactRetryToken.contains("4gb"))
    }

    @Test fun uniqueWorkNameContainsSessionId() {
        val session = newSession()
        assertTrue(session.uniqueWorkName.startsWith("private-chat-capacity-"))
        assertTrue(session.uniqueWorkName.contains(session.sessionId))
    }

    @Test fun waitingSessionIsNotTerminal() {
        assertFalse(newSession().isTerminal())
    }

    @Test fun timedOutSessionIsTerminal() {
        assertTrue(newSession().copy(state = CapacityRetryState.TIMED_OUT).isTerminal())
    }

    @Test fun cancelledSessionIsTerminal() {
        assertTrue(newSession().copy(state = CapacityRetryState.CANCELLED).isTerminal())
    }

    @Test fun ambiguousSessionIsTerminalBlocker() {
        assertTrue(newSession().copy(state = CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED).isTerminal())
    }

    @Test fun remainingTimeUsesPersistedDeadline() {
        val session = newSession()
        assertEquals(Duration.ofHours(24), session.remaining(Instant.parse("2026-07-15T12:00:00Z")))
        assertEquals(Duration.ofHours(1), session.remaining(Instant.parse("2026-07-16T11:00:00Z")))
    }

    @Test fun remainingTimeNeverNegative() {
        val session = newSession()
        assertEquals(Duration.ZERO, session.remaining(Instant.parse("2026-07-17T12:00:00Z")))
    }

    @Test fun sessionJsonRoundTripPreservesDurableFields() {
        val session = newSession().copy(
            lastLaunchAttemptFinishedAtUtc = "2026-07-15T12:45:00Z",
            nextEligibleAttemptAtUtc = "2026-07-15T13:00:00Z",
            pendingMemoryGb = 4,
            retryCycleCount = 3,
            launchRequestCount6Gb = 4,
            launchRequestCount4Gb = 2,
            lastHttpStatus = 500,
            lastOciErrorCode = "InternalError",
            lastSafeErrorCategory = "capacity",
            lastResult = "OUT_OF_HOST_CAPACITY_BOTH_CONFIGS",
            sourceExitId = "oci:source",
        )
        val decoded = CapacityRetrySession.fromJson(session.toJson())
        assertEquals(session, decoded)
    }

    @Test fun sessionsListJsonRoundTripPreservesOrder() {
        val first = newSession("candidate:a")
        val second = newSession("candidate:b")
        val decoded = sessionsFromJson(sessionsToJson(listOf(first, second)))
        assertEquals(listOf(first.sessionId, second.sessionId), decoded.map { it.sessionId })
    }

    @Test fun candidateJsonRoundTripPreservesSourceExit() {
        val candidate = PrivateChatCandidate(
            candidateId = "candidate:1",
            sourceExitId = "oci:source",
            createdAtUtc = "2026-07-15T12:00:00Z",
        )
        val decoded = privateChatCandidateFromJson(candidate.toJson())
        assertEquals(candidate, decoded)
    }

    @Test fun missingOptionalJsonValuesDecodeAsNull() {
        val session = CapacityRetrySession.fromJson(newSession().toJson())
        assertNull(session.instanceOcid)
        assertNull(session.lastHttpStatus)
    }

    private fun newSession(candidateId: String = "candidate:1"): CapacityRetrySession =
        CapacityRetrySession.newSession(
            candidateId = candidateId,
            mode = CapacityRetryMode.DEFERRED_PRIVATE_CHAT_CANDIDATE,
            sourceExitId = "oci:source",
            compartmentOcid = "ocid1.tenancy.oc1..example",
            clock = fixedClock,
        )
}
