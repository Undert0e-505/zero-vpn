package com.zerovpn.app.chat.retry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class CapacityRetryWorkStateTest {
    private val start = Instant.parse("2026-07-16T20:05:00Z")
    private val session = CapacityRetrySession.newSession(
        candidateId = "candidate:work-state",
        mode = CapacityRetryMode.INITIAL_PRIVATE_CHAT,
        sourceExitId = null,
        compartmentOcid = "ocid1.tenancy.oc1..example",
        initialLaunchAttemptFinishedAtUtc = start.toString(),
        initialNextEligibleAttemptAtUtc = start.plus(Duration.ofMinutes(15)).toString(),
        pendingMemoryGb = 4,
        initialAttemptMemoryGb = 6,
        initialHttpStatus = 500,
        initialOciErrorCode = "InternalError",
        initialLastResult = "OUT_OF_HOST_CAPACITY",
        clock = Clock.fixed(start, ZoneOffset.UTC),
    )

    @Test fun reconciliationReenqueuesWhenWorkManagerHasNoRecord() {
        assertEquals(
            CapacityRetryReconciliationAction.REENQUEUE,
            decideCapacityRetryReconciliation(
                session = session,
                schedulerState = CapacityRetrySchedulerState.NOT_FOUND,
                now = start.plus(Duration.ofMinutes(26)),
                schedulingAllowed = true,
                credentialsAvailable = true,
            ),
        )
    }

    @Test fun reconciliationDoesNotReenqueueAlreadyEnqueuedWork() {
        assertEquals(
            CapacityRetryReconciliationAction.KEEP,
            decideCapacityRetryReconciliation(
                session = session,
                schedulerState = CapacityRetrySchedulerState.ENQUEUED,
                now = start.plus(Duration.ofMinutes(26)),
                schedulingAllowed = true,
                credentialsAvailable = true,
            ),
        )
    }

    @Test fun reconciliationReenqueuesCancelledOrFailedWork() {
        listOf(
            CapacityRetrySchedulerState.CANCELLED,
            CapacityRetrySchedulerState.FAILED,
            CapacityRetrySchedulerState.SUCCEEDED,
        ).forEach { schedulerState ->
            assertEquals(
                schedulerState.name,
                CapacityRetryReconciliationAction.REENQUEUE,
                decideCapacityRetryReconciliation(
                    session = session,
                    schedulerState = schedulerState,
                    now = start.plus(Duration.ofMinutes(26)),
                    schedulingAllowed = true,
                    credentialsAvailable = true,
                ),
            )
        }
    }

    @Test fun overdueReenqueueAddsNoNewFifteenMinuteDelay() {
        assertEquals(
            Duration.ZERO,
            capacityRetryInitialDelay(session, start.plus(Duration.ofMinutes(26))),
        )
        assertEquals(
            Duration.ofMinutes(15),
            capacityRetryInitialDelay(session, start),
        )
    }

    @Test fun diagnosticLogSurvivesRepositoryReconstruction() {
        val prefs = FakeSharedPreferences()
        CapacityRetryDiagnosticLog(prefs, Clock.fixed(start, ZoneOffset.UTC))
            .append(session.sessionId, "Worker started")

        val reconstructed = CapacityRetryDiagnosticLog(
            prefs,
            Clock.fixed(start.plusSeconds(1), ZoneOffset.UTC),
        ).entries(session.sessionId)

        assertEquals(1, reconstructed.size)
        assertEquals("2026-07-16T20:05:00Z", reconstructed.single().timestampUtc)
        assertTrue(reconstructed.single().message.contains("Worker started"))
    }
}
