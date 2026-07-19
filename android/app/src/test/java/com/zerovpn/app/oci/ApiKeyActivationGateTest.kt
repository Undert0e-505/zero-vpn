package com.zerovpn.app.oci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the API-key activation gate diagnostics.
 *
 * After uploading a new API key, the provisioner probes with a read-only GET
 * using API-key auth. On 401 it retries with backoff (2s, 4s, 8s, 16s, 32s).
 * Only 401 triggers retries; other HTTP errors stop immediately.
 *
 * These tests verify the exception diagnostics and keyId format. The actual
 * probe loop is tested via the integration path through OciProvisioner.
 */
class ApiKeyActivationGateTest {

    @Test fun `activationFailedExceptionContainsSafeDiagnostics`() {
        val ex = OciProvisioner.ApiKeyActivationFailedException(
            fingerprint = "ab:cd:ef:12:34:56:78:90:ab:cd:ef:12:34:56:78:90",
            keyId = "ocid1.tenancy.oc1..test/ocid1.user.oc1..test/ab:cd:ef:12:34:56:78:90:ab:cd:ef:12:34:56:78:90",
            endpoint = "https://identity.uk-london-1.oraclecloud.com/20160918/users/test/apiKeys",
            attemptCount = 5,
        )
        val message = ex.message ?: ""
        assertTrue("Message should contain keyId", message.contains("keyId="))
        assertTrue("Message should contain attempt count", message.contains("attempt"))
        assertFalse("Message must not contain private key material", message.contains("PRIVATE KEY"))
        assertFalse("Message must not contain authorization signature", message.contains("Signature algorithm"))
    }

    @Test fun `activationFailedExceptionDoesNotLeakAuthorizationHeader`() {
        val ex = OciProvisioner.ApiKeyActivationFailedException(
            fingerprint = "ab:cd:ef:12:34:56:78:90:ab:cd:ef:12:34:56:78:90",
            keyId = "ocid1.tenancy.oc1..test/ocid1.user.oc1..test/ab:cd:ef:12:34:56:78:90:ab:cd:ef:12:34:56:78:90",
            endpoint = "https://identity.uk-london-1.oraclecloud.com/20160918/users/test/apiKeys",
            attemptCount = 3,
        )
        val message = ex.message ?: ""
        assertFalse("Must not contain Authorization header", message.contains("Authorization"))
        assertFalse("Must not contain signature value", message.contains("signature="))
    }

    @Test fun `apiKeyKeyIdFormatIsTenancySlashUserSlashFingerprint`() {
        val tenancy = "ocid1.tenancy.oc1..aaaa"
        val user = "ocid1.user.oc1..bbbb"
        val fingerprint = "ab:cd:ef:12:34:56:78:90:ab:cd:ef:12:34:56:78:90"
        val expectedKeyId = "$tenancy/$user/$fingerprint"

        // Verify that buildAuthHeader with useSecurityToken=false produces the correct keyId
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

    @Test fun `activationFailureReportsDistinctPhaseFromUploadSuccess`() {
        // The exception message must indicate the upload succeeded but activation failed,
        // not that the upload itself failed.
        val ex = OciProvisioner.ApiKeyActivationFailedException(
            fingerprint = "ab:cd:ef:12:34:56:78:90:ab:cd:ef:12:34:56:78:90",
            keyId = "ocid1.tenancy.oc1..test/ocid1.user.oc1..test/ab:cd:ef:12:34:56:78:90:ab:cd:ef:12:34:56:78:90",
            endpoint = "https://identity.uk-london-1.oraclecloud.com/20160918/users/test/apiKeys",
            attemptCount = 5,
        )
        val message = ex.message ?: ""
        assertTrue("Must say 'activation failed'", message.contains("activation failed"))
        assertTrue("Must say 'uploaded successfully'", message.contains("uploaded successfully"))
        assertFalse("Must not say 'Upload failed'", message.contains("Upload failed"))
    }
}