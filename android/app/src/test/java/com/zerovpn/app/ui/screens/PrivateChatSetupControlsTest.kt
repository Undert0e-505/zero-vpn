package com.zerovpn.app.ui.screens

import com.zerovpn.app.ui.provisioning.OracleOnboardingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
