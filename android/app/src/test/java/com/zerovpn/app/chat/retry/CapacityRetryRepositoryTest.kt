package com.zerovpn.app.chat.retry

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CapacityRetryRepositoryTest {
    private val start = Instant.parse("2026-07-15T12:00:00Z")

    @Test fun optInCreatesExactlyOneWaitingSession() {
        val repo = repositoryAt(start)
        repo.createSession("candidate:1", CapacityRetryMode.INITIAL_PRIVATE_CHAT, null, "tenancy")
        repo.createSession("candidate:1", CapacityRetryMode.INITIAL_PRIVATE_CHAT, null, "tenancy")
        assertEquals(1, repo.sessions().count { it.state == CapacityRetryState.WAITING_FOR_RETRY })
    }

    @Test fun repositoryReconstructionPreservesCountAndDeadline() {
        val prefs = FakeSharedPreferences()
        val firstRepo = repositoryAt(start, prefs)
        val session = firstRepo.createSession("candidate:1", CapacityRetryMode.INITIAL_PRIVATE_CHAT, null, "tenancy")
        val secondRepo = repositoryAt(start.plusSeconds(60), prefs)
        assertEquals(1, secondRepo.sessions().size)
        assertEquals(session.deadlineUtc, secondRepo.sessions().single().deadlineUtc)
    }

    @Test fun processRecreationDoesNotResetRetryCycleCount() {
        val prefs = FakeSharedPreferences()
        val firstRepo = repositoryAt(start, prefs)
        val session = firstRepo.createSession("candidate:1", CapacityRetryMode.INITIAL_PRIVATE_CHAT, null, "tenancy")
        val attemptRepo = repositoryAt(start.plusSeconds(15 * 60), prefs)
        attemptRepo.beginWorkerCycle(session.sessionId)
        attemptRepo.finishLaunchAttempt(session.sessionId, BackgroundLaunchResult.CapacityMiss(500, null))
        val secondRepo = repositoryAt(start.plusSeconds(30), prefs)
        assertEquals(1, secondRepo.sessions().single().retryCycleCount)
    }

    @Test fun beginWorkerCycleDoesNotCountARequestBeforeLaunchFinishes() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession("candidate:1", CapacityRetryMode.INITIAL_PRIVATE_CHAT, null, "tenancy")
        val repo = repositoryAt(start.plusSeconds(15 * 60), prefs)
        repo.beginWorkerCycle(session.sessionId)
        repo.beginWorkerCycle(session.sessionId)
        val acquiring = repo.sessions().single()
        assertEquals(CapacityRetryState.ACQUIRING, acquiring.state)
        assertEquals(0, acquiring.retryCycleCount)
        assertEquals(0, acquiring.launchRequestCount6Gb)
        assertEquals(0, acquiring.launchRequestCount4Gb)
    }

    @Test fun delayedWorkerAfterDeadlineTimesOutWithoutCycleIncrement() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession("candidate:1", CapacityRetryMode.INITIAL_PRIVATE_CHAT, null, "tenancy")
        val lateRepo = repositoryAt(start.plusSeconds(24 * 60 * 60), prefs)
        lateRepo.beginWorkerCycle(session.sessionId)
        val timedOut = lateRepo.sessions().single()
        assertEquals(CapacityRetryState.TIMED_OUT, timedOut.state)
        assertEquals(0, timedOut.retryCycleCount)
    }

    @Test fun timeoutProducesTimedOutTerminalReason() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession("candidate:1", CapacityRetryMode.INITIAL_PRIVATE_CHAT, null, "tenancy")
        val lateRepo = repositoryAt(start.plusSeconds(24 * 60 * 60 + 1), prefs)
        lateRepo.markTimedOutIfNeeded(session.sessionId)
        val timedOut = lateRepo.sessions().single()
        assertEquals(CapacityRetryState.TIMED_OUT, timedOut.state)
        assertEquals("TIMED_OUT", timedOut.lastResult)
        assertTrue(timedOut.requiresUserAction)
    }

    @Test fun noTimeoutBeforeDeadline() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession("candidate:1", CapacityRetryMode.INITIAL_PRIVATE_CHAT, null, "tenancy")
        val repo = repositoryAt(start.plusSeconds(60), prefs)
        repo.markTimedOutIfNeeded(session.sessionId)
        assertEquals(CapacityRetryState.WAITING_FOR_RETRY, repo.sessions().single().state)
    }

    @Test fun cancellationIsTerminalAndPreservesHistory() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession("candidate:1", CapacityRetryMode.INITIAL_PRIVATE_CHAT, null, "tenancy")
        val repo = repositoryAt(start.plusSeconds(15 * 60), prefs)
        repo.beginWorkerCycle(session.sessionId)
        repo.cancelSession(session.sessionId)
        val cancelled = repo.sessions().single()
        assertEquals(CapacityRetryState.CANCELLED, cancelled.state)
        assertEquals(0, cancelled.retryCycleCount)
        assertEquals("CANCELLED", cancelled.lastResult)
    }

    @Test fun beginWorkerCycleAfterCancellationDoesNotIncrement() {
        val repo = repositoryAt(start)
        val session = repo.createSession("candidate:1", CapacityRetryMode.INITIAL_PRIVATE_CHAT, null, "tenancy")
        repo.cancelSession(session.sessionId)
        repo.beginWorkerCycle(session.sessionId)
        assertEquals(0, repo.sessions().single().retryCycleCount)
    }

    @Test fun capacityMissReturnsSessionToActiveAndSchedulesNextTarget() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession("candidate:1", CapacityRetryMode.INITIAL_PRIVATE_CHAT, null, "tenancy")
        val repo = repositoryAt(start.plusSeconds(15 * 60), prefs)
        repo.beginWorkerCycle(session.sessionId)
        repo.finishCapacityMiss(session.sessionId)
        val active = repo.sessions().single()
        assertEquals(CapacityRetryState.ACTIVE, active.state)
        assertEquals("OUT_OF_HOST_CAPACITY", active.lastResult)
        assertEquals("2026-07-15T12:30:00Z", active.nextEligibleAttemptAtUtc)
        assertEquals("2026-07-15T12:15:00Z", active.lastLaunchAttemptFinishedAtUtc)
        assertEquals(4, active.pendingMemoryGb)
        assertEquals(1, active.retryCycleCount)
        assertEquals(1, active.launchRequestCount6Gb)
        assertEquals(0, active.launchRequestCount4Gb)
    }

    @Test fun fourGbCapacityMissAlternatesPendingMemoryBackToSixGb() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession(
            candidateId = "candidate:1",
            mode = CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            sourceExitId = null,
            compartmentOcid = "tenancy",
            pendingMemoryGb = 4,
        )
        val repo = repositoryAt(start.plusSeconds(15 * 60), prefs)

        repo.beginWorkerCycle(session.sessionId)
        repo.finishLaunchAttempt(session.sessionId, BackgroundLaunchResult.CapacityMiss(500, null))

        val active = repo.sessions().single()
        assertEquals(6, active.pendingMemoryGb)
        assertEquals(0, active.launchRequestCount6Gb)
        assertEquals(1, active.launchRequestCount4Gb)
    }

    @Test fun rateLimitPreservesPendingMemoryAndRetryCountAndHonoursRetryAfter() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession(
            candidateId = "candidate:1",
            mode = CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            sourceExitId = null,
            compartmentOcid = "tenancy",
            pendingMemoryGb = 4,
        )
        val repo = repositoryAt(start.plusSeconds(15 * 60), prefs)

        repo.beginWorkerCycle(session.sessionId)
        repo.finishLaunchAttempt(session.sessionId, BackgroundLaunchResult.RateLimited(retryAfterSeconds = 1_800))

        val active = repo.sessions().single()
        assertEquals(CapacityRetryState.ACTIVE, active.state)
        assertEquals("RATE_LIMITED", active.lastResult)
        assertEquals(4, active.pendingMemoryGb)
        assertEquals(0, active.retryCycleCount)
        assertEquals(1, active.launchRequestCount4Gb)
        assertEquals("2026-07-15T12:45:00Z", active.nextEligibleAttemptAtUtc)
        assertFalse(active.requiresUserAction)
    }

    @Test fun rateLimitDoesNotResetTheFixedDeadlineAcrossRepositoryRecreation() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession(
            candidateId = "candidate:1",
            mode = CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            sourceExitId = null,
            compartmentOcid = "tenancy",
            initialLaunchAttemptFinishedAtUtc = start.toString(),
            pendingMemoryGb = 4,
            initialAttemptMemoryGb = 6,
            initialHttpStatus = 500,
            initialOciErrorCode = "InternalError",
            initialLastResult = "OUT_OF_HOST_CAPACITY",
        )
        val originalDeadline = session.deadlineUtc
        val attemptRepository = repositoryAt(start.plusSeconds(15 * 60), prefs)

        attemptRepository.beginWorkerCycle(session.sessionId)
        attemptRepository.finishLaunchAttempt(
            session.sessionId,
            BackgroundLaunchResult.RateLimited(retryAfterSeconds = 1_800),
        )

        val reconstructed = repositoryAt(start.plusSeconds(16 * 60), prefs).sessions().single()
        assertEquals(originalDeadline, reconstructed.deadlineUtc)
        assertEquals(4, reconstructed.pendingMemoryGb)
        assertEquals(0, reconstructed.retryCycleCount)
    }


    @Test fun workerCycleRotatesRetryTokensForNewLogicalLaunch() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession("candidate:1", CapacityRetryMode.INITIAL_PRIVATE_CHAT, null, "tenancy")
        val repo = repositoryAt(start.plusSeconds(15 * 60), prefs)
        repo.beginWorkerCycle(session.sessionId)
        val acquiring = repo.sessions().single()
        assertFalse(session.preferredRetryToken == acquiring.preferredRetryToken)
        assertTrue(acquiring.preferredRetryToken.endsWith("6gb-1"))
        assertTrue(acquiring.compactRetryToken.endsWith("4gb-1"))
    }
    @Test fun activeSessionIgnoresTimedOutSessions() {
        val repo = repositoryAt(start)
        val session = repo.createSession("candidate:1", CapacityRetryMode.INITIAL_PRIVATE_CHAT, null, "tenancy")
        repo.cancelSession(session.sessionId)
        assertNull(repo.activeSession())
    }

    @Test fun activeSessionReturnsAmbiguousBlockerForUserReconciliation() {
        val repo = repositoryAt(start)
        val session = repo.createSession("candidate:1", CapacityRetryMode.INITIAL_PRIVATE_CHAT, null, "tenancy")
        repo.replaceSession(session.copy(state = CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED))
        assertEquals(CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED, repo.activeSession()?.state)
    }

    @Test fun repeatedCandidateCreationRoutesToExistingCandidate() {
        val repo = repositoryAt(start)
        val first = repo.createCandidate("oci:source")
        val second = repo.createCandidate("oci:source")
        assertEquals(first.candidateId, second.candidateId)
        assertEquals(1, repo.candidates().size)
    }

    @Test fun switchedCandidateAllowsNewCandidateForSameSourceLater() {
        val repo = repositoryAt(start)
        val first = repo.createCandidate("oci:source")
        repo.replaceCandidate(first.copy(state = DeferredCandidateState.SWITCHED))
        val second = repo.createCandidate("oci:source")
        assertFalse(first.candidateId == second.candidateId)
    }

    @Test fun failedCandidateAllowsNewCandidateForSameSourceLater() {
        val repo = repositoryAt(start)
        val first = repo.createCandidate("oci:source")
        repo.replaceCandidate(first.copy(state = DeferredCandidateState.FAILED))
        val second = repo.createCandidate("oci:source")
        assertFalse(first.candidateId == second.candidateId)
    }

    @Test fun candidateRoundTripPreservesReadyToSwitchTimestamp() {
        val repo = repositoryAt(start)
        val candidate = repo.createCandidate("oci:source").copy(
            state = DeferredCandidateState.READY_TO_SWITCH,
            readyToSwitchAtUtc = "2026-07-15T13:00:00Z",
        )
        repo.replaceCandidate(candidate)
        assertEquals("2026-07-15T13:00:00Z", repo.candidates().single().readyToSwitchAtUtc)
    }

    @Test fun instanceAcquiredSessionIsActiveForResumeProvisioning() {
        val repo = repositoryAt(start)
        val session = repo.createSession("candidate:1", CapacityRetryMode.INITIAL_PRIVATE_CHAT, null, "tenancy")
        repo.replaceSession(session.copy(state = CapacityRetryState.INSTANCE_ACQUIRED, instanceOcid = "ocid1.instance.oc1..example"))
        assertNotNull(repo.activeSession())
    }

    @Test fun newSessionAfterTimeoutRequiresExplicitNewCandidateOrAction() {
        val prefs = FakeSharedPreferences()
        val firstRepo = repositoryAt(start, prefs)
        val session = firstRepo.createSession("candidate:1", CapacityRetryMode.INITIAL_PRIVATE_CHAT, null, "tenancy")
        firstRepo.replaceSession(session.copy(state = CapacityRetryState.TIMED_OUT))
        val second = firstRepo.createSession("candidate:1", CapacityRetryMode.INITIAL_PRIVATE_CHAT, null, "tenancy")
        assertFalse(session.sessionId == second.sessionId)
    }

    @Test fun retryTokensSurviveRepositoryReconstructionForAmbiguousReuse() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession("candidate:1", CapacityRetryMode.INITIAL_PRIVATE_CHAT, null, "tenancy")
        val reconstructed = repositoryAt(start.plusSeconds(1), prefs).sessions().single()
        assertEquals(session.preferredRetryToken, reconstructed.preferredRetryToken)
        assertEquals(session.compactRetryToken, reconstructed.compactRetryToken)
    }

    @Test fun ambiguousHttpResponsePausesAndPreservesLaunchIdentityAndDeadline() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession(
            candidateId = "candidate:ambiguous-http",
            mode = CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            sourceExitId = null,
            compartmentOcid = "tenancy",
            pendingMemoryGb = 4,
        )
        val repo = repositoryAt(start.plusSeconds(15 * 60), prefs)
        val acquiring = requireNotNull(repo.beginWorkerCycle(session.sessionId))

        repo.finishLaunchAttempt(
            session.sessionId,
            BackgroundLaunchResult.AmbiguousFailure(
                status = 500,
                category = "ambiguous-oci",
                requestId = "redacted-id",
            ),
        )

        val blocked = repo.sessions().single()
        assertEquals(CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED, blocked.state)
        assertEquals("AMBIGUOUS_FAILURE_RECONCILIATION_REQUIRED", blocked.lastResult)
        assertTrue(blocked.requiresUserAction)
        assertEquals(acquiring.preferredRetryToken, blocked.preferredRetryToken)
        assertEquals(acquiring.compactRetryToken, blocked.compactRetryToken)
        assertEquals(4, blocked.pendingMemoryGb)
        assertEquals(session.deadlineUtc, blocked.deadlineUtc)
        assertNull(blocked.nextEligibleAttemptAtUtc)
        assertEquals(0, blocked.retryCycleCount)
        assertEquals(1, blocked.launchRequestCount4Gb)
    }

    @Test fun transmissionFailureRequiresReconciliationAndCannotStartAnotherCycle() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession(
            "candidate:transmission",
            CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            null,
            "tenancy",
        )
        val attemptRepo = repositoryAt(start.plusSeconds(15 * 60), prefs)
        val acquiring = requireNotNull(attemptRepo.beginWorkerCycle(session.sessionId))
        val diagnostics = launchDiagnostics(
            progress = LaunchProgress.TRANSMISSION_STARTED,
            transmissionStarted = true,
            responseHeadersReceived = false,
        )

        attemptRepo.finishLaunchAttempt(
            session.sessionId,
            BackgroundLaunchResult.TransmissionFailure(
                category = "transmission-failure",
                safeMessage = diagnostics.safeExceptionMessage,
                exceptionClass = diagnostics.exceptionClass,
                rootCauseClass = diagnostics.rootCauseClass,
                redactedRequestId = null,
                diagnostics = diagnostics,
            ),
        )

        val laterRepo = repositoryAt(start.plusSeconds(30 * 60), prefs)
        laterRepo.beginWorkerCycle(session.sessionId)
        val blocked = laterRepo.sessions().single()
        assertEquals(CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED, blocked.state)
        assertEquals(acquiring.preferredRetryToken, blocked.preferredRetryToken)
        assertEquals(session.deadlineUtc, blocked.deadlineUtc)
        assertEquals(0, blocked.retryCycleCount)
        assertEquals(1, blocked.launchRequestCount6Gb)
    }

    @Test fun localPreparationFailureIsTerminalAndDoesNotCountALaunchRequest() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession(
            "candidate:local",
            CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            null,
            "tenancy",
        )
        val repo = repositoryAt(start.plusSeconds(15 * 60), prefs)
        repo.beginWorkerCycle(session.sessionId)
        val diagnostics = launchDiagnostics(
            progress = LaunchProgress.SIGNING_REQUEST,
            transmissionStarted = false,
            responseHeadersReceived = false,
        )

        repo.finishLaunchAttempt(
            session.sessionId,
            BackgroundLaunchResult.LocalPreparationFailure(
                progress = diagnostics.progress,
                category = "signing-error",
                safeMessage = diagnostics.safeExceptionMessage,
                exceptionClass = diagnostics.exceptionClass,
                diagnostics = diagnostics,
            ),
        )

        val failed = repo.sessions().single()
        assertEquals(CapacityRetryState.FAILED_TERMINAL, failed.state)
        assertEquals("signing-error", failed.lastSafeErrorCategory)
        assertEquals(0, failed.launchRequestCount6Gb)
        assertEquals(0, failed.launchRequestCount4Gb)
        assertNull(failed.lastLaunchAttemptFinishedAtUtc)
        assertEquals(session.deadlineUtc, failed.deadlineUtc)
    }

    @Test fun authenticationFailurePausesAuthRequiredAndPreservesDeadlineAndCredentials() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession(
            "candidate:auth-fail",
            CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            null,
            "tenancy",
        )
        val repo = repositoryAt(start.plusSeconds(15 * 60), prefs)
        repo.beginWorkerCycle(session.sessionId)
        val diagnostics = launchDiagnostics(
            progress = LaunchProgress.RESPONSE_RECEIVED,
            transmissionStarted = true,
            responseHeadersReceived = true,
        )

        repo.finishLaunchAttempt(
            session.sessionId,
            BackgroundLaunchResult.AuthenticationFailure(
                category = "oci-authentication-failed",
                safeMessage = "OCI rejected the signed request with HTTP 401.",
                httpStatus = 401,
                redactedRequestId = "redacted-req-id",
                diagnostics = diagnostics,
            ),
        )

        val paused = repo.sessions().single()
        assertEquals(CapacityRetryState.PAUSED_AUTH_REQUIRED, paused.state)
        assertEquals("OCI_AUTHENTICATION_FAILED", paused.lastResult)
        assertEquals("oci-authentication-failed", paused.lastSafeErrorCategory)
        assertEquals(401, paused.lastHttpStatus)
        assertTrue(paused.requiresUserAction)
        assertEquals(session.deadlineUtc, paused.deadlineUtc)
        // Should not count as a launch request
        assertEquals(0, paused.launchRequestCount6Gb)
        assertEquals(0, paused.launchRequestCount4Gb)
    }

    @Test fun ambiguousReconciliationBlockerCannotBeCancelledOrReplaced() {
        val repo = repositoryAt(start)
        val session = repo.createSession(
            "candidate:blocker",
            CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            null,
            "tenancy",
        )
        repo.replaceSession(
            session.copy(
                state = CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED,
                lastResult = "AMBIGUOUS_EXCEPTION_RECONCILIATION_REQUIRED",
            ),
        )

        val cancelled = requireNotNull(repo.cancelSession(session.sessionId))
        val replacement = repo.createSession(
            "candidate:blocker",
            CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            null,
            "tenancy",
        )

        assertEquals(CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED, cancelled.state)
        assertEquals(session.sessionId, replacement.sessionId)
        assertEquals(1, repo.sessions().size)
    }

    @Test fun workerCooldownGateMakesNoLaunchRequestBeforePersistedFirstInterval() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession(
            "candidate:1",
            CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            null,
            "tenancy",
        )
        repositoryAt(start.plusSeconds(14 * 60 + 59), prefs).beginWorkerCycle(session.sessionId)
        val coolingDown = repositoryAt(start, prefs).sessions().single()
        assertEquals(CapacityRetryState.WAITING_FOR_RETRY, coolingDown.state)
        assertEquals(0, coolingDown.retryCycleCount)
        assertEquals(0, coolingDown.launchRequestCount6Gb + coolingDown.launchRequestCount4Gb)
        assertNull(coolingDown.lastWorkerStartedAtUtc)

        repositoryAt(start.plusSeconds(15 * 60), prefs).beginWorkerCycle(session.sessionId)
        assertEquals(CapacityRetryState.ACQUIRING, repositoryAt(start, prefs).sessions().single().state)
        assertEquals(0, repositoryAt(start, prefs).sessions().single().retryCycleCount)
    }

    @Test fun capacityRetryPolicyPersistsBeforeAuthentication() {
        val prefs = FakeSharedPreferences()
        val repository = repositoryAt(start, prefs)
        repository.setCapacityRetryPolicyEnabled(true)
        assertTrue(repositoryAt(start.plusSeconds(1), prefs).capacityRetryPolicyEnabled())
        assertTrue(repository.sessions().isEmpty())
        repositoryAt(start.plusSeconds(2), prefs).setCapacityRetryPolicyEnabled(false)
        assertFalse(repositoryAt(start.plusSeconds(3), prefs).capacityRetryPolicyEnabled())
    }

    @Test fun relaunchReenqueueIsBlockedWhileForegroundProvisioningIsActive() {
        val session = repositoryAt(start).createSession(
            "candidate:1",
            CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            null,
            "tenancy",
        )
        assertFalse(
            shouldEnqueueCapacityRetry(
                session,
                topLevelProvisioningRunning = true,
                provisioningJobActive = false,
                provisioningLeaseHeld = true,
            ),
        )
        assertFalse(
            shouldEnqueueCapacityRetry(
                session,
                topLevelProvisioningRunning = false,
                provisioningJobActive = true,
                provisioningLeaseHeld = false,
            ),
        )
        assertTrue(
            shouldEnqueueCapacityRetry(
                session,
                topLevelProvisioningRunning = false,
                provisioningJobActive = false,
                provisioningLeaseHeld = false,
            ),
        )
    }

    @Test fun enqueuedWorkerGateSkipsWhileForegroundLeaseIsHeld() {
        val waitingSession = repositoryAt(start).createSession(
            "candidate:1",
            CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            null,
            "tenancy",
        )
        assertFalse(
            shouldRunCapacityRetryWorker(
                waitingSession,
                provisioningLeaseHeld = true,
            ),
        )
        assertTrue(
            shouldRunCapacityRetryWorker(
                waitingSession,
                provisioningLeaseHeld = false,
            ),
        )
    }

    @Test fun enqueuedWorkerGateRequiresAnEligiblePersistedSession() {
        val waitingSession = repositoryAt(start).createSession(
            "candidate:1",
            CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            null,
            "tenancy",
        )
        assertFalse(
            shouldRunCapacityRetryWorker(
                waitingSession.copy(state = CapacityRetryState.ACQUIRING),
                provisioningLeaseHeld = false,
            ),
        )
        assertFalse(
            shouldRunCapacityRetryWorker(
                waitingSession.copy(instanceOcid = "ocid1.instance.oc1..existing"),
                provisioningLeaseHeld = false,
            ),
        )
        assertFalse(
            shouldRunCapacityRetryWorker(
                waitingSession.copy(requiresUserAction = true),
                provisioningLeaseHeld = false,
            ),
        )
    }

    @Test fun provisioningLeaseBlocksOverlapAndClearsAfterProcessDeath() {
        val prefs = FakeSharedPreferences()
        val foreground = ProvisioningOperationLease(prefs, processInstanceId = "process-a")
        assertTrue(foreground.tryAcquire(ProvisioningLeaseOperation.FOREGROUND_PROVISIONING))
        assertTrue(foreground.isForegroundProvisioningActive())
        assertFalse(
            ProvisioningOperationLease(prefs, processInstanceId = "process-a")
                .tryAcquire(ProvisioningLeaseOperation.BACKGROUND_CAPACITY_RETRY),
        )

        val recreatedProcess = ProvisioningOperationLease(prefs, processInstanceId = "process-b")
        assertFalse(recreatedProcess.isHeldByLiveProcess())
        assertTrue(recreatedProcess.tryAcquire(ProvisioningLeaseOperation.BACKGROUND_CAPACITY_RETRY))
    }

    @Test fun everyLiveProvisioningLeaseBlocksAnEnqueuedWorkerLaunch() {
        val waitingSession = repositoryAt(start).createSession(
            "candidate:1",
            CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            null,
            "tenancy",
        )
        ProvisioningLeaseOperation.entries.forEach { operation ->
            val prefs = FakeSharedPreferences()
            val lease = ProvisioningOperationLease(prefs, processInstanceId = "process-$operation")
            assertTrue("lease should be acquired for $operation", lease.tryAcquire(operation))
            assertFalse(
                "$operation must exclude the capacity worker",
                shouldRunCapacityRetryWorker(
                    waitingSession,
                    provisioningLeaseHeld = lease.isHeldByLiveProcess(),
                ),
            )
            lease.release()
        }
    }

    @Test fun leaseCanOnlyBeReleasedByTheAcquiringInstance() {
        val prefs = FakeSharedPreferences()
        val owner = ProvisioningOperationLease(prefs, processInstanceId = "process-a")
        val nonOwner = ProvisioningOperationLease(prefs, processInstanceId = "process-a")
        assertTrue(owner.tryAcquire(ProvisioningLeaseOperation.FOREGROUND_PROVISIONING))

        nonOwner.release()

        assertTrue(owner.isHeldByLiveProcess())
        assertFalse(nonOwner.tryAcquire(ProvisioningLeaseOperation.BACKGROUND_CAPACITY_RETRY))
        owner.release()
        assertTrue(nonOwner.tryAcquire(ProvisioningLeaseOperation.BACKGROUND_CAPACITY_RETRY))
    }

    @Test fun cancelledSessionCannotBeResurrectedByALateCapacityResult() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession(
            "candidate:1",
            CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            null,
            "tenancy",
        )
        val acquiringRepository = repositoryAt(start.plusSeconds(15 * 60), prefs)
        acquiringRepository.beginWorkerCycle(session.sessionId)
        acquiringRepository.cancelSession(session.sessionId)

        acquiringRepository.finishCapacityMiss(session.sessionId)

        assertEquals(CapacityRetryState.CANCELLED, acquiringRepository.sessions().single().state)
    }

    @Test fun transientNetworkFailureStaysActiveAndSchedulesNextAttempt() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession(
            "candidate:transient",
            CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            null,
            "tenancy",
        )
        val repo = repositoryAt(start.plusSeconds(15 * 60), prefs)
        repo.beginWorkerCycle(session.sessionId)
        val diagnostics = launchDiagnostics(
            progress = LaunchProgress.SIGNING_REQUEST,
            transmissionStarted = false,
            responseHeadersReceived = false,
        )

        repo.finishLaunchAttempt(
            session.sessionId,
            BackgroundLaunchResult.TransientNetworkFailure(
                category = "transient-network-failure",
                safeMessage = "Network unavailable during Oracle preparation: UnknownHostException",
                exceptionClass = "UnknownHostException",
                failedHostname = "identity.eu-zurich-1.oci.oraclecloud.com",
                diagnostics = diagnostics,
            ),
        )

        val active = repo.sessions().single()
        assertEquals(CapacityRetryState.ACTIVE, active.state)
        assertEquals("TRANSIENT_NETWORK_FAILURE", active.lastResult)
        assertEquals("transient-network-failure", active.lastSafeErrorCategory)
        assertEquals(6, active.pendingMemoryGb)
        assertEquals(1, active.transientNetworkDeferrals)
        assertFalse(active.requiresUserAction)
        assertNull(active.terminalReason)
        // Next eligible attempt should be scheduled 15+ minutes later
        assertNotNull(active.nextEligibleAttemptAtUtc)
        // Deadline should NOT be reset
        assertEquals(session.deadlineUtc, active.deadlineUtc)
    }

    @Test fun transientNetworkFailureDoesNotIncrementLaunchCounters() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession(
            "candidate:transient-counters",
            CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            null,
            "tenancy",
        )
        val repo = repositoryAt(start.plusSeconds(15 * 60), prefs)
        repo.beginWorkerCycle(session.sessionId)
        val diagnostics = launchDiagnostics(
            progress = LaunchProgress.SIGNING_REQUEST,
            transmissionStarted = false,
            responseHeadersReceived = false,
        )

        repo.finishLaunchAttempt(
            session.sessionId,
            BackgroundLaunchResult.TransientNetworkFailure(
                category = "transient-network-failure",
                safeMessage = "Network unavailable",
                exceptionClass = "UnknownHostException",
                failedHostname = "identity.test",
                diagnostics = diagnostics,
            ),
        )

        val active = repo.sessions().single()
        assertEquals(0, active.launchRequestCount6Gb)
        assertEquals(0, active.launchRequestCount4Gb)
        assertEquals(0, active.instanceLaunchRequests6Gb)
        assertEquals(0, active.instanceLaunchRequests4Gb)
        assertEquals(0, active.backgroundLaunchAttempts)
        assertEquals(1, active.transientNetworkDeferrals)
    }

    @Test fun transientNetworkFailurePreservesRetryTokenAndDoesNotGenerateNewOne() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession(
            "candidate:transient-token",
            CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            null,
            "tenancy",
        )
        val repo = repositoryAt(start.plusSeconds(15 * 60), prefs)
        val acquiring = requireNotNull(repo.beginWorkerCycle(session.sessionId))
        val diagnostics = launchDiagnostics(
            progress = LaunchProgress.SIGNING_REQUEST,
            transmissionStarted = false,
            responseHeadersReceived = false,
        )

        repo.finishLaunchAttempt(
            session.sessionId,
            BackgroundLaunchResult.TransientNetworkFailure(
                category = "transient-network-failure",
                safeMessage = "Network unavailable",
                exceptionClass = "UnknownHostException",
                failedHostname = "identity.test",
                diagnostics = diagnostics,
            ),
        )

        val active = repo.sessions().single()
        assertEquals(acquiring.preferredRetryToken, active.preferredRetryToken)
        assertEquals(acquiring.compactRetryToken, active.compactRetryToken)
    }

    @Test fun laterWorkerSucceedsAfterPreviousTransientFailure() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession(
            "candidate:transient-then-success",
            CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            null,
            "tenancy",
        )
        // First attempt: transient network failure
        val firstRepo = repositoryAt(start.plusSeconds(15 * 60), prefs)
        firstRepo.beginWorkerCycle(session.sessionId)
        val diagnostics = launchDiagnostics(
            progress = LaunchProgress.SIGNING_REQUEST,
            transmissionStarted = false,
            responseHeadersReceived = false,
        )
        firstRepo.finishLaunchAttempt(
            session.sessionId,
            BackgroundLaunchResult.TransientNetworkFailure(
                category = "transient-network-failure",
                safeMessage = "Network unavailable",
                exceptionClass = "UnknownHostException",
                failedHostname = "identity.test",
                diagnostics = diagnostics,
            ),
        )

        // Second attempt: should be eligible since session is ACTIVE
        val secondRepo = repositoryAt(start.plusSeconds(30 * 60), prefs)
        val eligible = secondRepo.sessions().single()
        assertEquals(CapacityRetryState.ACTIVE, eligible.state)
        assertTrue(eligible.isLaunchEligible(start.plusSeconds(30 * 60)))
        val started = secondRepo.beginWorkerCycle(session.sessionId)
        assertNotNull(started)
        assertEquals(CapacityRetryState.ACQUIRING, started!!.state)
    }

    @Test fun beginWorkerCycleIncrementsWorkerCyclesStarted() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession(
            "candidate:worker-count",
            CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            null,
            "tenancy",
        )
        val repo = repositoryAt(start.plusSeconds(15 * 60), prefs)
        assertEquals(0, repo.sessions().single().workerCyclesStarted)
        repo.beginWorkerCycle(session.sessionId)
        assertEquals(1, repo.sessions().single().workerCyclesStarted)
        // A second beginWorkerCycle on an ACQUIRING session doesn't re-increment
        repo.beginWorkerCycle(session.sessionId)
        assertEquals(1, repo.sessions().single().workerCyclesStarted)
    }

    @Test fun legacyCountersMigratedToZeroForLegacySessions() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession(
            "candidate:legacy",
            CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            null,
            "tenancy",
        )
        // Simulate a legacy session: has retryCycleCount > 0 but workerCyclesStarted == 0
        // and has non-zero legacy launch counters
        repo(prefs, start).replaceSession(
            session.copy(
                retryCycleCount = 3,
                workerCyclesStarted = 0,
                launchRequestCount6Gb = 5,
                launchRequestCount4Gb = 2,
            ),
        )

        // Reconstruct from JSON (simulating process restart)
        val reconstructed = repositoryAt(start.plusSeconds(60), prefs).sessions().single()
        // Legacy counters should be reset to 0
        assertEquals(0, reconstructed.launchRequestCount6Gb)
        assertEquals(0, reconstructed.launchRequestCount4Gb)
        // retryCycleCount preserved
        assertEquals(3, reconstructed.retryCycleCount)
    }

    @Test fun capacityMissIncrementsAccurateCounters() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession(
            "candidate:accurate-counters",
            CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            null,
            "tenancy",
        )
        val repo = repositoryAt(start.plusSeconds(15 * 60), prefs)
        repo.beginWorkerCycle(session.sessionId)
        repo.finishCapacityMiss(session.sessionId)

        val active = repo.sessions().single()
        assertEquals(1, active.instanceLaunchRequests6Gb)
        assertEquals(0, active.instanceLaunchRequests4Gb)
        assertEquals(1, active.backgroundLaunchAttempts)
        // Legacy also incremented for backward compat
        assertEquals(1, active.launchRequestCount6Gb)
    }

    @Test fun durableLaunchContextSurvivesRepositoryReconstruction() {
        val prefs = FakeSharedPreferences()
        val session = repositoryAt(start, prefs).createSession(
            "candidate:durable-context",
            CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            null,
            "tenancy",
            ubuntuImageOcid = "ocid1.image.oc1..ubuntu",
            vcnOcid = "ocid1.vcn.oc1..vcn",
        )
        val reconstructed = repositoryAt(start.plusSeconds(60), prefs).sessions().single()
        assertEquals("ocid1.image.oc1..ubuntu", reconstructed.ubuntuImageOcid)
        assertEquals("ocid1.vcn.oc1..vcn", reconstructed.vcnOcid)
    }

    private fun repo(prefs: FakeSharedPreferences, instant: Instant) = repositoryAt(instant, prefs)

    private fun launchDiagnostics(
        progress: LaunchProgress,
        transmissionStarted: Boolean,
        responseHeadersReceived: Boolean,
    ): LaunchFailureDiagnostics = LaunchFailureDiagnostics.capture(
        error = IllegalStateException("safe launch failure"),
        progress = progress,
        failingOperation = "test-launch-operation",
        requestConstructionCompleted = progress >= LaunchProgress.REQUEST_READY,
        requestSigningCompleted = progress >= LaunchProgress.REQUEST_READY,
        transmissionStarted = transmissionStarted,
        responseHeadersReceived = responseHeadersReceived,
        retryToken = "retry-token-for-test",
        sessionId = "session-for-test",
    )

    private fun repositoryAt(instant: Instant, prefs: FakeSharedPreferences = FakeSharedPreferences()): CapacityRetryRepository =
        CapacityRetryRepository(prefs, Clock.fixed(instant, ZoneOffset.UTC))
}

internal class FakeSharedPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = linkedMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clear = false
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { if (key != null) pending[key] = values }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
        override fun remove(key: String?): SharedPreferences.Editor = apply { if (key != null) removals += key }
        override fun clear(): SharedPreferences.Editor = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) values.clear()
            removals.forEach { values.remove(it) }
            pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }
}

