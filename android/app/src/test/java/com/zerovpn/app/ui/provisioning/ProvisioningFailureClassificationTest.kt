package com.zerovpn.app.ui.provisioning

import com.zerovpn.app.oci.VmLaunchFailure
import com.zerovpn.app.oci.VmLaunchFailureException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

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

    private companion object {
        const val CAPACITY_MESSAGE =
            "Oracle has no A1 host capacity in your home region right now. Try again later, or try a different region."
    }
}