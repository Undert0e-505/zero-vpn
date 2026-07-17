package com.zerovpn.app.ui.screens

import com.zerovpn.app.chat.retry.CapacityRetrySession
import com.zerovpn.app.chat.retry.CapacityRetryState
import com.zerovpn.app.chat.retry.CapacityRetryMode
import com.zerovpn.app.ui.provisioning.OracleOnboardingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PrivateChatSetupControlsTest {
    @Test fun activeRetryRendersOneDisabledAuthoritativePrivateChatSwitchRole() {
        val roles = visiblePrivateChatSwitchRoles(
            privateChatRequested = true,
            onboardingState = OracleOnboardingState.NotStarted,
            hasActiveCapacityRetry = true,
        )

        assertEquals(listOf(PrivateChatSwitchRole.PRIVATE_CHAT_REQUEST), roles)
        assertFalse(isPrivateChatRequestToggleEnabled(hasActiveCapacityRetry = true))
        assertFalse(PrivateChatSwitchRole.CAPACITY_RETRY_POLICY in roles)
    }

    @Test fun falseToTrueTransitionNeverDuplicatesThePrivateChatRequestSwitch() {
        val before = visiblePrivateChatSwitchRoles(
            privateChatRequested = false,
            onboardingState = OracleOnboardingState.NotStarted,
            hasActiveCapacityRetry = false,
        )
        val after = visiblePrivateChatSwitchRoles(
            privateChatRequested = true,
            onboardingState = OracleOnboardingState.NotStarted,
            hasActiveCapacityRetry = false,
        )

        assertEquals(1, before.count { it == PrivateChatSwitchRole.PRIVATE_CHAT_REQUEST })
        assertEquals(1, after.count { it == PrivateChatSwitchRole.PRIVATE_CHAT_REQUEST })
        assertTrue(PrivateChatSwitchRole.CAPACITY_RETRY_POLICY in after)
        assertEquals(after.size, after.distinct().size)
    }

    @Test fun policySwitchDisappearsOnceAuthenticationHasStarted() {
        val roles = visiblePrivateChatSwitchRoles(
            privateChatRequested = true,
            onboardingState = OracleOnboardingState.AuthLaunched,
            hasActiveCapacityRetry = false,
        )

        assertEquals(listOf(PrivateChatSwitchRole.PRIVATE_CHAT_REQUEST), roles)
    }

    @Test fun switchIsHiddenWhenRetrySessionIsActive() {
        val session = makeSession(CapacityRetryState.ACTIVE)
        assertTrue(shouldHidePrivateChatSwitch(session))
    }

    @Test fun switchIsHiddenWhenRetrySessionIsWaitingForRetry() {
        val session = makeSession(CapacityRetryState.WAITING_FOR_RETRY)
        assertTrue(shouldHidePrivateChatSwitch(session))
    }

    @Test fun switchIsHiddenWhenRetrySessionIsPausedAuthRequired() {
        val session = makeSession(CapacityRetryState.PAUSED_AUTH_REQUIRED)
        assertTrue(shouldHidePrivateChatSwitch(session))
    }

    @Test fun switchIsHiddenWhenRetrySessionIsFailedAmbiguous() {
        val session = makeSession(CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED)
        assertTrue(shouldHidePrivateChatSwitch(session))
    }

    @Test fun switchIsHiddenWhenRetrySessionIsFailedTerminal() {
        val session = makeSession(CapacityRetryState.FAILED_TERMINAL)
        assertTrue(shouldHidePrivateChatSwitch(session))
    }

    @Test fun switchIsHiddenWhenRetrySessionIsTimedOut() {
        val session = makeSession(CapacityRetryState.TIMED_OUT)
        assertTrue(shouldHidePrivateChatSwitch(session))
    }

    @Test fun switchIsVisibleWhenRetrySessionIsSucceeded() {
        val session = makeSession(CapacityRetryState.SUCCEEDED)
        assertFalse(shouldHidePrivateChatSwitch(session))
    }

    @Test fun switchIsVisibleWhenRetrySessionIsNull() {
        assertFalse(shouldHidePrivateChatSwitch(null))
    }

    @Test fun switchIsVisibleWhenRetrySessionIsCancelled() {
        val session = makeSession(CapacityRetryState.CANCELLED)
        assertFalse(shouldHidePrivateChatSwitch(session))
    }

    private fun makeSession(state: CapacityRetryState): CapacityRetrySession =
        CapacityRetrySession.newSession(
            candidateId = "candidate:test",
            mode = CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            sourceExitId = null,
            compartmentOcid = "tenancy",
        ).copy(state = state)
}
