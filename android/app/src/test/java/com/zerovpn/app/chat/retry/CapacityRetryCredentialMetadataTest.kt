package com.zerovpn.app.chat.retry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CapacityRetryCredentialMetadataTest {
    @Test fun sessionJsonCarriesNonSecretOciMetadata() {
        val session = CapacityRetrySession.newSession(
            candidateId = "candidate:1",
            mode = CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            sourceExitId = null,
            compartmentOcid = "ocid1.tenancy.oc1..tenancy",
            userOcid = "ocid1.user.oc1..user",
            tenancyOcid = "ocid1.tenancy.oc1..tenancy",
            fingerprint = "aa:bb:cc",
            selectedRegion = "uk-london-1",
            tokenRegion = "uk-cardiff-1",
            tokenRegionSource = "token-claim",
            subnetId = "ocid1.subnet.oc1..subnet",
            sshPublicKey = "ssh-rsa AAAA zerovpn-android",
            clock = Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC),
        )
        val decoded = CapacityRetrySession.fromJson(session.toJson())
        assertEquals(session.userOcid, decoded.userOcid)
        assertEquals(session.tenancyOcid, decoded.tenancyOcid)
        assertEquals(session.fingerprint, decoded.fingerprint)
        assertEquals(session.selectedRegion, decoded.selectedRegion)
        assertEquals(session.tokenRegion, decoded.tokenRegion)
        assertEquals(session.tokenRegionSource, decoded.tokenRegionSource)
        assertEquals(session.subnetId, decoded.subnetId)
        assertEquals(session.sshPublicKey, decoded.sshPublicKey)
    }

    @Test fun oldSessionJsonWithoutCredentialMetadataStillDecodes() {
        val decoded = CapacityRetrySession.fromJson(CapacityRetrySession.newSession(
            candidateId = "candidate:1",
            mode = CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            sourceExitId = null,
            compartmentOcid = "tenancy",
        ).toJson().apply {
            remove("userOcid")
            remove("tenancyOcid")
            remove("fingerprint")
            remove("selectedRegion")
            remove("tokenRegion")
        })
        assertTrue(decoded.userOcid == null && decoded.selectedRegion == null)
    }
}
