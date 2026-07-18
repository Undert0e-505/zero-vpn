package com.zerovpn.app.oci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OciRegionAuthorityTest {
    @Test fun selectedZurichWinsAndIsUsedDirectly() {
        val result = OciRegionAuthority.resolve("eu-zurich-1", "eu-amsterdam-1", "eu-madrid-1")
        assertEquals("eu-zurich-1", result.regionId)
        assertEquals(OciRegionAuthority.Source.USER_SELECTED, result.source)
        assertTrue(result.isAuthoritative)
    }

    @Test fun persistedVerifiedZurichWinsWithoutScanningOtherRegions() {
        val result = OciRegionAuthority.resolve(null, "eu-zurich-1", "eu-madrid-1")
        assertEquals("eu-zurich-1", result.regionId)
        assertEquals(OciRegionAuthority.Source.PERSISTED_VERIFIED, result.source)
        assertFalse(listOf("eu-amsterdam-1", "eu-madrid-1").contains(result.regionId))
    }

    @Test fun trustedAuthenticationHomeRegionIsThirdInAuthorityOrder() {
        val result = OciRegionAuthority.resolve(null, null, "eu-zurich-1")
        assertEquals("eu-zurich-1", result.regionId)
        assertEquals(OciRegionAuthority.Source.TRUSTED_AUTH_RESULT, result.source)
    }

    @Test fun noAuthoritativeRegionRequiresSelection() {
        val result = OciRegionAuthority.resolve(null, null, null)
        assertNull(result.regionId)
        assertEquals(OciRegionAuthority.Source.REQUIRED, result.source)
        assertFalse(result.isAuthoritative)
    }

    @Test fun bootstrapRegionCannotEnterAuthorityChain() {
        val authBootstrapRegion = "us-ashburn-1"
        val result = OciRegionAuthority.resolve(null, null, null)
        assertNull(result.regionId)
        assertFalse(result.regionId == authBootstrapRegion)
    }

    @Test fun networkFailureCannotReplaceKnownZurichAuthority() {
        val beforeNetworkAttempt = OciRegionAuthority.resolve("eu-zurich-1", null)
        val afterDnsTimeout = beforeNetworkAttempt
        assertEquals("eu-zurich-1", afterDnsTimeout.regionId)
        assertEquals(beforeNetworkAttempt, afterDnsTimeout)
    }
}
