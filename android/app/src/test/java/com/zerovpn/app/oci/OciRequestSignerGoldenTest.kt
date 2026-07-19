package com.zerovpn.app.oci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.PrivateKey
import java.util.Base64

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

    /**
     * API-key mode keyId must be exactly {tenancyOcid}/{userOcid}/{fingerprint}.
     */
    @Test fun apiKeyKeyIdIsExactlyTenancyUserFingerprint() {
        val pair = OciRequestSigner.generateKeyPair()
        val privateKey: PrivateKey = pair.private
        val tenancyOcid = "ocid1.tenancy.oc1..test-tenancy"
        val userOcid = "ocid1.user.oc1..test-user"
        val fingerprint = OciRequestSigner.md5Fingerprint(pair.public as java.security.interfaces.RSAPublicKey)

        val (authHeader, _, _) = OciRequestSigner.buildAuthHeader(
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

        val expectedKeyId = "$tenancyOcid/$userOcid/$fingerprint"
        assertTrue("keyId must be present", authHeader.contains("keyId=\"$expectedKeyId\""))
        assertFalse("keyId must not contain ST\$ prefix", authHeader.contains("ST\$"))
        // Verify exact substring from start of keyId= to closing quote
        val keyIdStart = authHeader.indexOf("keyId=\"")
        val keyIdEnd = authHeader.indexOf("\"", keyIdStart + 7)
        val actualKeyId = authHeader.substring(keyIdStart + 7, keyIdEnd)
        assertEquals("keyId must equal tenancy/user/fingerprint exactly", expectedKeyId, actualKeyId)
    }

    /**
     * Deterministic GET signing string: date, (request-target), and host.
     * When a fixed date header is supplied the signing string must match a
     * known golden value exactly.
     */
    @Test fun getSigningStringMatchesGoldenCanonicalRequest() {
        val pair = OciRequestSigner.generateKeyPair()
        val privateKey: PrivateKey = pair.private
        val fingerprint = OciRequestSigner.md5Fingerprint(pair.public as java.security.interfaces.RSAPublicKey)
        val fixedDate = "Sun, 19 Jul 2026 16:00:00 GMT"

        val (_, _, signingString) = OciRequestSigner.buildAuthHeader(
            tenancyOcid = "ocid1.tenancy.oc1..aaaa",
            userOcid = "ocid1.user.oc1..bbbb",
            fingerprint = fingerprint,
            privateKey = privateKey,
            method = "GET",
            path = "/20160918/availabilityDomains",
            host = "identity.eu-frankfurt-1.oraclecloud.com",
            headers = mapOf("date" to fixedDate),
            useSecurityToken = false,
            securityToken = null,
        )

        val expected = "date: $fixedDate\n(request-target): get /20160918/availabilityDomains\nhost: identity.eu-frankfurt-1.oraclecloud.com"
        assertEquals("GET signing string must match golden canonical request", expected, signingString)
    }

    /**
     * Deterministic POST signing string includes body hash and content headers.
     */
    @Test fun postSigningStringMatchesGoldenCanonicalRequest() {
        val pair = OciRequestSigner.generateKeyPair()
        val privateKey: PrivateKey = pair.private
        val fingerprint = OciRequestSigner.md5Fingerprint(pair.public as java.security.interfaces.RSAPublicKey)
        val fixedDate = "Sun, 19 Jul 2026 16:00:00 GMT"
        val body = """{"availabilityDomain":"AD-1","shape":"VM.Standard.A1.Flex"}"""
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val contentLength = bodyBytes.size.toString()
        val contentSha256 = Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(bodyBytes)
        )

        val (_, _, signingString) = OciRequestSigner.buildAuthHeader(
            tenancyOcid = "ocid1.tenancy.oc1..aaaa",
            userOcid = "ocid1.user.oc1..bbbb",
            fingerprint = fingerprint,
            privateKey = privateKey,
            method = "POST",
            path = "/20160918/instances",
            host = "iaas.eu-frankfurt-1.oraclecloud.com",
            headers = mapOf("date" to fixedDate),
            useSecurityToken = false,
            securityToken = null,
            body = body,
        )

        val expectedLines = listOf(
            "date: $fixedDate",
            "(request-target): post /20160918/instances",
            "host: iaas.eu-frankfurt-1.oraclecloud.com",
            "content-length: $contentLength",
            "content-type: application/json",
            "x-content-sha256: $contentSha256",
        )
        assertEquals("POST signing string must match golden canonical request", expectedLines.joinToString("\n"), signingString)
    }

    /**
     * Foreground (provisioner) and background (retry worker) API-key signing
     * both go through the same OciRequestSigner.buildAuthHeader path. With
     * identical inputs they produce identical signing strings and Authorization
     * header structures.
     */
    @Test fun foregroundAndBackgroundApiKeySigningUseSharedSigner() {
        val pair = OciRequestSigner.generateKeyPair()
        val privateKey: PrivateKey = pair.private
        val fingerprint = OciRequestSigner.md5Fingerprint(pair.public as java.security.interfaces.RSAPublicKey)
        val tenancyOcid = "ocid1.tenancy.oc1..aaaa"
        val userOcid = "ocid1.user.oc1..bbbb"
        val fixedDate = "Sun, 19 Jul 2026 16:00:00 GMT"

        // Foreground call
        val (fgAuth, _, fgSigning) = OciRequestSigner.buildAuthHeader(
            tenancyOcid = tenancyOcid,
            userOcid = userOcid,
            fingerprint = fingerprint,
            privateKey = privateKey,
            method = "GET",
            path = "/20160918/instances",
            host = "iaas.eu-frankfurt-1.oraclecloud.com",
            headers = mapOf("date" to fixedDate),
            useSecurityToken = false,
            securityToken = null,
        )

        // Background call — same inputs
        val (bgAuth, _, bgSigning) = OciRequestSigner.buildAuthHeader(
            tenancyOcid = tenancyOcid,
            userOcid = userOcid,
            fingerprint = fingerprint,
            privateKey = privateKey,
            method = "GET",
            path = "/20160918/instances",
            host = "iaas.eu-frankfurt-1.oraclecloud.com",
            headers = mapOf("date" to fixedDate),
            useSecurityToken = false,
            securityToken = null,
        )

        assertEquals("Foreground and background signing strings must match", fgSigning, bgSigning)
        assertEquals("Foreground and background Authorization headers must match", fgAuth, bgAuth)
        assertTrue("Header must contain API-key keyId", fgAuth.contains("keyId=\"$tenancyOcid/$userOcid/$fingerprint\""))
    }

    /**
     * The headers listed in the Authorization header must match the signed
     * header names and order exactly.
     */
    @Test fun authorizationHeaderListsExactlyTheSignedHeaders() {
        val pair = OciRequestSigner.generateKeyPair()
        val privateKey: PrivateKey = pair.private
        val fingerprint = OciRequestSigner.md5Fingerprint(pair.public as java.security.interfaces.RSAPublicKey)
        val body = """{"cidrBlock":"10.0.0.0/24"}"""

        val (authHeader, _, signingString) = OciRequestSigner.buildAuthHeader(
            tenancyOcid = "ocid1.tenancy.oc1..aaaa",
            userOcid = "ocid1.user.oc1..bbbb",
            fingerprint = fingerprint,
            privateKey = privateKey,
            method = "POST",
            path = "/20160918/vcns",
            host = "iaas.eu-frankfurt-1.oraclecloud.com",
            useSecurityToken = false,
            securityToken = null,
            body = body,
        )

        val headersLine = signingString.split("\n").map { it.substringBefore(":") }
        val headersAttr = authHeader
            .substringAfter("headers=\"")
            .substringBefore("\"")
            .split(" ")
        assertEquals("Signed header names must match Authorization headers attribute", headersLine, headersAttr)
    }

    /**
     * The private key used for signing must derive the exact RSA public key
     * that was uploaded to Oracle. Any key mismatch would produce a valid
     * signature that Oracle rejects with HTTP 401.
     */
    @Test fun privateKeyDerivesUploadedPublicKey() {
        val pair = OciRequestSigner.generateKeyPair()
        val privateKey: PrivateKey = pair.private
        val uploadedPublicKey = pair.public as java.security.interfaces.RSAPublicKey

        val derivedPublicKey = OciCredentialIdentity.publicKeyFrom(privateKey)

        assertEquals("Derived modulus must match uploaded modulus", uploadedPublicKey.modulus, derivedPublicKey.modulus)
        assertEquals("Derived public exponent must match uploaded exponent", uploadedPublicKey.publicExponent, derivedPublicKey.publicExponent)
        assertArrayEquals("Derived DER encoding must match uploaded DER encoding", uploadedPublicKey.encoded, derivedPublicKey.encoded)
    }

    /**
     * The fingerprint must be calculated from the DER-encoded uploaded public key.
     * It must match the fingerprint derived from the same private key.
     */
    @Test fun fingerprintMatchesUploadedPublicKey() {
        val pair = OciRequestSigner.generateKeyPair()
        val privateKey: PrivateKey = pair.private
        val uploadedPublicKey = pair.public as java.security.interfaces.RSAPublicKey

        val fingerprintFromPublic = OciRequestSigner.md5Fingerprint(uploadedPublicKey)
        val derivedPublicKey = OciCredentialIdentity.publicKeyFrom(privateKey)
        val fingerprintFromDerived = OciRequestSigner.md5Fingerprint(derivedPublicKey)

        assertEquals("Fingerprint from uploaded public key must match fingerprint from derived key", fingerprintFromPublic, fingerprintFromDerived)
        assertEquals("OCI identity verification must succeed", fingerprintFromPublic, OciCredentialIdentity.verify(privateKey, fingerprintFromPublic))
    }
}
