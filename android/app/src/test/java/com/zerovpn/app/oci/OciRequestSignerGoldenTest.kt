package com.zerovpn.app.oci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.PrivateKey

class OciRequestSignerGoldenTest {
    /**
     * The signing string (canonical request) must be identical for both auth modes
     * — only the keyId format differs.
     *
     * Security token: keyId = "ST$<token>"
     * API key: keyId = "<tenancy>/<user>/<fingerprint>"
     */
    @Test fun signingStringIsIdenticalForBothAuthModes() {
        val pair = OciRequestSigner.generateKeyPair()
        val privateKey: PrivateKey = pair.private
        val securityToken = "test-security-token-abc123"
        val tenancyOcid = "ocid1.tenancy.oc1..aaaa"
        val userOcid = "ocid1.user.oc1..bbbb"
        val fingerprint = OciRequestSigner.md5Fingerprint(pair.public as java.security.interfaces.RSAPublicKey)

        // GET request — security token mode
        val (stAuthHeader, stDate, stSigningString) = OciRequestSigner.buildAuthHeader(
            tenancyOcid = tenancyOcid,
            userOcid = userOcid,
            fingerprint = fingerprint,
            privateKey = privateKey,
            method = "GET",
            path = "/20160918/availabilityDomains",
            host = "identity.eu-frankfurt-1.oraclecloud.com",
            useSecurityToken = true,
            securityToken = securityToken,
        )

        // GET request — API key mode
        val (apiKeyAuthHeader, apiKeyDate, apiKeySigningString) = OciRequestSigner.buildAuthHeader(
            tenancyOcid = tenancyOcid,
            userOcid = userOcid,
            fingerprint = fingerprint,
            privateKey = privateKey,
            method = "GET",
            path = "/20160918/availabilityDomains",
            host = "identity.eu-frankfurt-1.oraclecloud.com",
            useSecurityToken = false,
            securityToken = null,
        )

        // The signing strings must be identical (same canonical request)
        assertEquals("Signing string must be identical for both auth modes", stSigningString, apiKeySigningString)

        // The keyId formats must differ
        assertTrue("Security token keyId must use ST$ prefix", stAuthHeader.contains("keyId=\"ST\$"))
        assertTrue("API key keyId must use tenancy/user/fingerprint format",
            apiKeyAuthHeader.contains("keyId=\"$tenancyOcid/$userOcid/$fingerprint\""))
        assertNotEquals("Auth headers must differ (different keyId)", stAuthHeader, apiKeyAuthHeader)
    }

    /**
     * POST signing strings include body headers and must also be identical for both modes.
     */
    @Test fun postSigningStringIsIdenticalForBothAuthModes() {
        val pair = OciRequestSigner.generateKeyPair()
        val privateKey: PrivateKey = pair.private
        val securityToken = "test-security-token-xyz789"
        val tenancyOcid = "ocid1.tenancy.oc1..cccc"
        val userOcid = "ocid1.user.oc1..dddd"
        val fingerprint = OciRequestSigner.md5Fingerprint(pair.public as java.security.interfaces.RSAPublicKey)
        val body = """{"availabilityDomain":"AD-1","shape":"VM.Standard.A1.Flex"}"""

        val (stAuthHeader, _, stSigningString) = OciRequestSigner.buildAuthHeader(
            tenancyOcid = tenancyOcid,
            userOcid = userOcid,
            fingerprint = fingerprint,
            privateKey = privateKey,
            method = "POST",
            path = "/20160918/instances",
            host = "iaas.eu-frankfurt-1.oraclecloud.com",
            useSecurityToken = true,
            securityToken = securityToken,
            body = body,
        )

        val (apiKeyAuthHeader, _, apiKeySigningString) = OciRequestSigner.buildAuthHeader(
            tenancyOcid = tenancyOcid,
            userOcid = userOcid,
            fingerprint = fingerprint,
            privateKey = privateKey,
            method = "POST",
            path = "/20160918/instances",
            host = "iaas.eu-frankfurt-1.oraclecloud.com",
            useSecurityToken = false,
            securityToken = null,
            body = body,
        )

        assertEquals("POST signing string must be identical for both auth modes", stSigningString, apiKeySigningString)
        assertFalse("Security token keyId must not be empty", stAuthHeader.contains("keyId=\"\""))
        assertFalse("API key keyId must not use ST$ prefix", apiKeyAuthHeader.contains("keyId=\"ST\$"))
    }
}