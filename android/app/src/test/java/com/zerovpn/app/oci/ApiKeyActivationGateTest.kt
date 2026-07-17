package com.zerovpn.app.oci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the two-stage API-key verification flow diagnostics.
 *
 * Stage 1: Security-token-authenticated GET to verify Oracle registered the key (fingerprint present in ListApiKeys).
 * Stage 2: API-key-authenticated GET to verify the signing implementation works.
 *
 * These tests verify exception diagnostics and keyId format. The actual probe loops
 * are tested via integration through OciProvisioner.
 */
class ApiKeyActivationGateTest {

    @Test fun `verificationExceptionForRegistrationPendingContainsSafeDiagnostics`() {
        val ex = OciProvisioner.ApiKeyVerificationException(
            stage = "KEY_REGISTRATION_PENDING",
            fingerprint = "ab:cd:ef:12:34:56:78:90:ab:cd:ef:12:34:56:78:90",
            keyId = "ocid1.tenancy.oc1..test/ocid1.user.oc1..test/ab:cd:ef:12:34:56:78:90:ab:cd:ef:12:34:56:78:90",
            endpoint = "https://identity.uk-london-1.oraclecloud.com/20160918/users/test/apiKeys",
            attemptCount = 5,
            requestId = "abc123def456",
        )
        val message = ex.message ?: ""
        assertTrue("Message should contain 'registration pending'", message.contains("registration pending"))
        assertTrue("Message should contain keyId", message.contains("keyId="))
        assertTrue("Message should contain attempt count", message.contains("attempt"))
        assertFalse("Message must not contain private key material", message.contains("PRIVATE KEY"))
        assertFalse("Message must not contain authorization signature", message.contains("Signature algorithm"))
    }

    @Test fun `verificationExceptionForSignatureValidationFailedContainsSafeDiagnostics`() {
        val ex = OciProvisioner.ApiKeyVerificationException(
            stage = "API_KEY_SIGNATURE_VALIDATION_FAILED",
            fingerprint = "ab:cd:ef:12:34:56:78:90:ab:cd:ef:12:34:56:78:90",
            keyId = "ocid1.tenancy.oc1..test/ocid1.user.oc1..test/ab:cd:ef:12:34:56:78:90:ab:cd:ef:12:34:56:78:90",
            endpoint = "https://identity.uk-london-1.oraclecloud.com/20160918/users/test/apiKeys",
            attemptCount = 1,
            requestId = "xyz789abc012",
        )
        val message = ex.message ?: ""
        assertTrue("Must say 'registered the API key'", message.contains("registered the API key"))
        assertTrue("Must say 'could not authenticate'", message.contains("could not authenticate"))
        assertTrue("Must say 'No cloud resources were created'", message.contains("No cloud resources were created"))
        assertTrue("Must say 'Do not create another key automatically'", message.contains("Do not create another key automatically"))
        assertFalse("Must not contain private key material", message.contains("PRIVATE KEY"))
        assertFalse("Must not contain signature value", message.contains("signature="))
        assertFalse("Must not contain Authorization header", message.contains("Authorization"))
    }

    @Test fun `apiKeyKeyIdFormatIsTenancySlashUserSlashFingerprint`() {
        val tenancy = "ocid1.tenancy.oc1..aaaa"
        val user = "ocid1.user.oc1..bbbb"
        val fingerprint = "ab:cd:ef:12:34:56:78:90:ab:cd:ef:12:34:56:78:90"
        val expectedKeyId = "$tenancy/$user/$fingerprint"

        val pair = OciRequestSigner.generateKeyPair()
        val (authHeader, _, _) = OciRequestSigner.buildAuthHeader(
            tenancyOcid = tenancy,
            userOcid = user,
            fingerprint = fingerprint,
            privateKey = pair.private,
            method = "GET",
            path = "/20160918/users/$user/apiKeys",
            host = "identity.uk-london-1.oraclecloud.com",
            useSecurityToken = false,
            securityToken = null,
        )
        assertTrue("Auth header must contain API-key keyId format", authHeader.contains("keyId=\"$expectedKeyId\""))
        assertFalse("Auth header must not contain ST\$ token prefix", authHeader.contains("ST\$"))
    }

    @Test fun `securityTokenKeyIdFormatUsesSTPrefix`() {
        val tenancy = "ocid1.tenancy.oc1..aaaa"
        val user = "ocid1.user.oc1..bbbb"
        val fingerprint = "ab:cd:ef:12:34:56:78:90:ab:cd:ef:12:34:56:78:90"
        val token = "test-security-token"
        val pair = OciRequestSigner.generateKeyPair()
        val (authHeader, _, _) = OciRequestSigner.buildAuthHeader(
            tenancyOcid = tenancy,
            userOcid = user,
            fingerprint = fingerprint,
            privateKey = pair.private,
            method = "GET",
            path = "/20160918/users/$user/apiKeys",
            host = "identity.uk-london-1.oraclecloud.com",
            useSecurityToken = true,
            securityToken = token,
        )
        assertTrue("Auth header must contain ST\$ prefix", authHeader.contains("keyId=\"ST\$$token\""))
        assertFalse("Auth header must not contain tenancy/user/fingerprint keyId", authHeader.contains("$tenancy/$user/$fingerprint"))
    }

    @Test fun `signatureValidationFailedDoesNotSayActivationDelay`() {
        val ex = OciProvisioner.ApiKeyVerificationException(
            stage = "API_KEY_SIGNATURE_VALIDATION_FAILED",
            fingerprint = "ab:cd:ef:12:34:56:78:90:ab:cd:ef:12:34:56:78:90",
            keyId = "ocid1.tenancy.oc1..test/ocid1.user.oc1..test/ab:cd:ef:12:34:56:78:90:ab:cd:ef:12:34:56:78:90",
            endpoint = "https://identity.uk-london-1.oraclecloud.com/20160918/users/test/apiKeys",
            attemptCount = 1,
            requestId = null,
        )
        val message = ex.message ?: ""
        assertFalse("Must not say 'activation delay'", message.contains("activation delay"))
        assertFalse("Must not say 'not yet active'", message.contains("not yet active"))
        assertFalse("Must not say 'propagation'", message.contains("propagation"))
    }
}