package com.zerovpn.app.oci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OciCredentialIdentityTest {
    @Test fun publicKeyFromPrivateKeyProducesMatchingFingerprint() {
        val pair = OciRequestSigner.generateKeyPair()
        val pub = OciCredentialIdentity.publicKeyFrom(pair.private)
        val fingerprint = OciCredentialIdentity.fingerprintOf(pub)
        assertNotNull(fingerprint)
        // Fingerprint should be a 47-char string (16 hex bytes, colon-separated)
        assertEquals(47, fingerprint.length)
    }

    @Test fun sha256DigestIsLowercaseHex() {
        val pair = OciRequestSigner.generateKeyPair()
        val pub = OciCredentialIdentity.publicKeyFrom(pair.private)
        val digest = OciCredentialIdentity.sha256Digest(pub)
        assertEquals(64, digest.length)
        assert(digest.all { it in "0123456789abcdef" })
    }

    @Test fun verifySucceedsWhenFingerprintMatches() {
        val pair = OciRequestSigner.generateKeyPair()
        val pub = OciCredentialIdentity.publicKeyFrom(pair.private)
        val expectedFingerprint = OciCredentialIdentity.fingerprintOf(pub)
        val result = OciCredentialIdentity.verify(pair.private, expectedFingerprint)
        assertEquals(expectedFingerprint, result)
    }

    @Test fun verifySucceedsWithSha256Digest() {
        val pair = OciRequestSigner.generateKeyPair()
        val pub = OciCredentialIdentity.publicKeyFrom(pair.private)
        val fingerprint = OciCredentialIdentity.fingerprintOf(pub)
        val sha256 = OciCredentialIdentity.sha256Digest(pub)
        val result = OciCredentialIdentity.verify(pair.private, fingerprint, sha256)
        assertEquals(fingerprint, result)
    }

    @Test fun verifyFailsOnFingerprintMismatch() {
        val pair = OciRequestSigner.generateKeyPair()
        assertThrows(IllegalArgumentException::class.java) {
            OciCredentialIdentity.verify(pair.private, "ff:ff:ff:ff:ff:ff:ff:ff:ff:ff:ff:ff:ff:ff:ff:ff")
        }
    }

    @Test fun verifyFailsOnSha256Mismatch() {
        val pair = OciRequestSigner.generateKeyPair()
        val pub = OciCredentialIdentity.publicKeyFrom(pair.private)
        val fingerprint = OciCredentialIdentity.fingerprintOf(pub)
        assertThrows(IllegalArgumentException::class.java) {
            OciCredentialIdentity.verify(pair.private, fingerprint, "0".repeat(64))
        }
    }

    @Test fun publicKeyDerivedFromPrivateKeyMatchesUploadedKey() {
        val pair = OciRequestSigner.generateKeyPair()
        val uploadedPublicKey = pair.public as java.security.interfaces.RSAPublicKey
        val derivedPublicKey = OciCredentialIdentity.publicKeyFrom(pair.private)

        assertEquals("Derived modulus must match uploaded modulus", uploadedPublicKey.modulus, derivedPublicKey.modulus)
        assertEquals("Derived public exponent must match uploaded exponent", uploadedPublicKey.publicExponent, derivedPublicKey.publicExponent)
        assertArrayEquals("Derived DER encoding must match uploaded DER encoding", uploadedPublicKey.encoded, derivedPublicKey.encoded)
    }

    @Test fun fingerprintIsCalculatedFromUploadedPublicKey() {
        val pair = OciRequestSigner.generateKeyPair()
        val uploadedPublicKey = pair.public as java.security.interfaces.RSAPublicKey
        val derivedPublicKey = OciCredentialIdentity.publicKeyFrom(pair.private)

        val fingerprintFromUploaded = OciCredentialIdentity.fingerprintOf(uploadedPublicKey)
        val fingerprintFromDerived = OciCredentialIdentity.fingerprintOf(derivedPublicKey)

        assertEquals("Fingerprint from uploaded public key must match derived key fingerprint", fingerprintFromUploaded, fingerprintFromDerived)
        assertEquals("OCI identity verify must return the same fingerprint", fingerprintFromUploaded, OciCredentialIdentity.verify(pair.private, fingerprintFromUploaded))
    }

    @Test fun verifyFailsOnNonRsaKey() {
        val kpg = java.security.KeyPairGenerator.getInstance("EC")
        kpg.initialize(256)
        val ecKey = kpg.generateKeyPair().private
        assertThrows(IllegalStateException::class.java) {
            OciCredentialIdentity.publicKeyFrom(ecKey)
        }
    }
}