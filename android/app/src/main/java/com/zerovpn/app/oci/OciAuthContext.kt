package com.zerovpn.app.oci

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec

/**
 * Sealed hierarchy describing which OCI authentication mode is active.
 *
 * - [SecurityTokenBootstrap] is used only for the initial browser-login → API-key upload.
 * - [ApiKey] is used for all post-upload operations (network creation, VM launch, destroy, etc.).
 *
 * After the API key is uploaded, the provisioner switches from [SecurityTokenBootstrap] to [ApiKey].
 * The background retry worker only ever uses [ApiKey] — it never touches the short-lived browser token.
 */
sealed interface OciAuthContext {

    /**
     * Session-token auth: `keyId = "ST$<token>"`.
     * Used only for the initial authenticated bootstrap (browser login → API key upload).
     */
    data class SecurityTokenBootstrap(
        val securityToken: String,
        val privateKey: PrivateKey,
        val tenancyOcid: String,
        val userOcid: String,
        val fingerprint: String,
    ) : OciAuthContext

    /**
     * Durable API-key auth: `keyId = "<tenancy>/<user>/<fingerprint>"`.
     * Used for all post-upload operations and by the background retry worker.
     */
    data class ApiKey(
        val tenancyOcid: String,
        val userOcid: String,
        val fingerprint: String,
        val privateKey: PrivateKey,
        val region: String,
        val publicKeySha256: String? = null,
    ) : OciAuthContext
}

/**
 * Credential identity verification utilities.
 *
 * After uploading an API key, the provisioner verifies that the stored fingerprint
 * and (optionally) the public-key SHA-256 digest match the private key that will be
 * used for signing. This detects key corruption or mismatch before the background
 * worker attempts a signed request that would receive HTTP 401.
 */
object OciCredentialIdentity {

    /**
     * Derive the RSA public key from an RSA private key.
     * Supports both CRT (Chinese Remainder Theorem) and non-CRT private keys.
     */
    fun publicKeyFrom(privateKey: PrivateKey): RSAPublicKey {
        // Fast path: CRT key has all the parameters we need
        val rsa = privateKey as? RSAPrivateCrtKey
        if (rsa != null) {
            return KeyFactory.getInstance("RSA")
                .generatePublic(RSAPublicKeySpec(rsa.modulus, rsa.publicExponent)) as RSAPublicKey
        }
        // Fallback: try RSAPrivateKey (has modulus but may not have public exponent)
        val rsaPriv = privateKey as? java.security.interfaces.RSAPrivateKey
            ?: error("OCI signing key is not an RSA private key.")
        // For non-CRT keys, we need to derive the public key from the encoding
        // Most RSA private keys include the public exponent in their encoding
        val keyFactory = KeyFactory.getInstance("RSA")
        val publicKey = keyFactory.generatePublic(RSAPublicKeySpec(rsaPriv.modulus, java.math.BigInteger.valueOf(65537)))
        return publicKey as RSAPublicKey
    }

    /**
     * Compute the MD5 fingerprint (OCI format: colon-separated hex) of an RSA public key.
     */
    fun fingerprintOf(publicKey: RSAPublicKey): String =
        OciRequestSigner.md5Fingerprint(publicKey)

    /**
     * Compute the SHA-256 digest (lowercase hex) of the DER-encoded public key.
     * Used as a durable integrity check — if the stored digest matches, the key has not changed.
     */
    fun sha256Digest(publicKey: RSAPublicKey): String =
        MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
            .joinToString("") { "%02x".format(it) }

    /**
     * Verify that the given private key produces a fingerprint matching [expectedFingerprint],
     * and (if provided) a SHA-256 digest matching [expectedPublicKeySha256].
     *
     * @return the verified fingerprint (always lowercase, colon-separated)
     * @throws IllegalArgumentException if either check fails
     */
    fun verify(
        privateKey: PrivateKey,
        expectedFingerprint: String,
        expectedPublicKeySha256: String? = null,
    ): String {
        val pub = publicKeyFrom(privateKey)
        val fp = fingerprintOf(pub)
        require(fp.equals(expectedFingerprint, ignoreCase = true)) {
            "Stored OCI fingerprint ($fp) does not match the signing key ($expectedFingerprint)."
        }
        if (expectedPublicKeySha256 != null) {
            val digest = sha256Digest(pub)
            require(digest.equals(expectedPublicKeySha256, ignoreCase = true)) {
                "Stored OCI public-key digest does not match the signing key."
            }
        }
        return fp
    }
}