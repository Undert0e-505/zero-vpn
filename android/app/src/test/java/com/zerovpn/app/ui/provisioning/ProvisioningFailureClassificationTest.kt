package com.zerovpn.app.ui.provisioning

import com.zerovpn.app.chat.retry.CapacityRetryMode
import com.zerovpn.app.chat.retry.CapacityRetrySession
import com.zerovpn.app.chat.retry.CapacityRetryState
import com.zerovpn.app.oci.VmLaunchFailure
import com.zerovpn.app.oci.VmLaunchFailureException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class ProvisioningFailureClassificationTest {
    @Test
    fun `Capacity error attributes to VM_LAUNCH`() {
        val (phase, _) = classifyProvisioningFailure(capacityError())

        assertEquals(Phase.VM_LAUNCH, phase)
    }

    @Test
    fun `Generic provisioning error uses event-based phase detection`() {
        val events = listOf(
            event(Phase.API_KEY, Status.SUCCESS),
            event(Phase.NETWORK, Status.ERROR),
        )

        val (phase, message) = classifyProvisioningFailure(
            error = IllegalStateException("network failed"),
            events = events,
            currentPhase = Phase.VM_LAUNCH,
        )

        assertEquals(Phase.NETWORK, phase)
        assertEquals("network failed", message)
    }

    @Test
    fun `Capacity error shows clean message, not raw HTTP response`() {
        val (_, message) = classifyProvisioningFailure(capacityError())

        assertEquals(CAPACITY_MESSAGE, message)
        assertFalse(message.contains("HTTP", ignoreCase = true))
        assertFalse(message.contains("InternalError", ignoreCase = true))
    }

    @Test
    fun `Rate limit error shows clean VM launch message`() {
        val (phase, message) = classifyProvisioningFailure(
            VmLaunchFailureException(VmLaunchFailure.RateLimited(RATE_LIMIT_MESSAGE, 900)),
        )

        assertEquals(Phase.VM_LAUNCH, phase)
        assertEquals(RATE_LIMIT_MESSAGE, message)
    }

    @Test
    fun `Retry with existing elapsed session selects one saved-session launch`() {
        val session = retrySession()

        val decision = decideManualCapacityRetry(
            session = session,
            pendingCapacityRetryEligible = false,
            now = Instant.parse("2026-07-15T12:15:00Z"),
        )

        assertEquals(ManualCapacityRetryDecision.Attempt(session.sessionId), decision)
    }

    @Test
    fun `Retry with existing active cooldown makes no launch or authentication decision`() {
        val decision = decideManualCapacityRetry(
            session = retrySession(),
            pendingCapacityRetryEligible = false,
            now = Instant.parse("2026-07-15T12:05:00Z"),
        )

        assertEquals(
            ManualCapacityRetryDecision.Cooldown(Duration.ofMinutes(10)),
            decision,
        )
    }

    @Test
    fun `Retry without session starts from staged credentials only when eligible`() {
        assertEquals(
            ManualCapacityRetryDecision.StartSession,
            decideManualCapacityRetry(null, pendingCapacityRetryEligible = true, Instant.parse("2026-07-15T12:00:00Z")),
        )
        assertEquals(
            ManualCapacityRetryDecision.NotAvailable,
            decideManualCapacityRetry(null, pendingCapacityRetryEligible = false, Instant.parse("2026-07-15T12:00:00Z")),
        )
    }

    @Test
    fun `Retry with missing credential state requests authentication`() {
        val paused = retrySession().copy(state = CapacityRetryState.PAUSED_AUTH_REQUIRED)
        assertTrue(
            decideManualCapacityRetry(paused, false, Instant.parse("2026-07-15T12:15:00Z")) is
                ManualCapacityRetryDecision.AuthenticationRequired,
        )
    }

    @Test
    fun `Retry with ambiguous reconciliation blocker never selects a launch`() {
        val ambiguous = retrySession().copy(
            state = CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED,
            requiresUserAction = true,
        )

        assertEquals(
            ManualCapacityRetryDecision.NotAvailable,
            decideManualCapacityRetry(
                ambiguous,
                pendingCapacityRetryEligible = true,
                now = Instant.parse("2026-07-15T12:15:00Z"),
            ),
        )
    }

    @Test
    fun `Auth error attributes to AUTH`() {
        val (phase, message) = classifyProvisioningFailure(
            error = IllegalArgumentException("auth failed"),
            currentPhase = Phase.AUTH,
        )

        assertEquals(Phase.AUTH, phase)
        assertEquals("auth failed", message)
    }

    private fun capacityError(): VmLaunchFailureException = VmLaunchFailureException(
        VmLaunchFailure.OutOfHostCapacity(CAPACITY_MESSAGE),
    )

    private fun event(phase: Phase, status: Status): ProvisioningEvent = ProvisioningEvent(
        timestamp = 1L,
        phase = phase,
        status = status,
        message = status.name,
    )

    private fun retrySession(): CapacityRetrySession = CapacityRetrySession.newSession(
        candidateId = "candidate:1",
        mode = CapacityRetryMode.INITIAL_PRIVATE_CHAT,
        sourceExitId = null,
        compartmentOcid = "ocid1.tenancy.oc1..example",
        pendingMemoryGb = 4,
        clock = Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC),
    )

    private companion object {
        const val CAPACITY_MESSAGE =
            "Oracle has no A1 host capacity in your home region right now. Try again later, or try a different region."
        const val RATE_LIMIT_MESSAGE = "Oracle is temporarily rate limiting VM requests."
    }
}
