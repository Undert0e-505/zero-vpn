package com.zerovpn.app.oci

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.UUID

/**
 * OCI request signing utility.
 *
 * OCI API uses RSA-SHA256 request signing. Every API call must be signed
 * with the user's private key. The signature is sent in the
 * `Authorization` header as `Signature ...`.
 *
 * This is a minimal implementation — no OCI SDK dependency needed.
 *
 * Reference: https://docs.oracle.com/en-us/iaas/Content/API/Concepts/signingrequests.htm
 */
object OciRequestSigner {

    /**
     * Generate an RSA 2048-bit keypair for OCI API signing.
     * On Android, use KeyPairGenerator with "AndroidKeyStore" provider
     * instead of this, but for the spike we use plain JCA.
     */
    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        return kpg.generateKeyPair()
    }

    /**
     * Serialize an RSA public key to PEM format for OCI API key upload.
     */
    fun publicKeyToPem(publicKey: RSAPublicKey): String {
        val der = publicKey.encoded
        val b64 = Base64.getEncoder().encodeToString(der)
        return "-----BEGIN PUBLIC KEY-----\n$b64\n-----END PUBLIC KEY-----"
    }

    /**
     * Compute the SHA-256 fingerprint of a public key (OCI format).
     * OCI uses the colon-separated hex of the SHA-256 of the DER-encoded public key.
     */
    fun publicKeyFingerprint(publicKey: RSAPublicKey): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey.encoded)
        return hash.joinToString(":") { "%02x".format(it) }
    }

        /**
     * Encode a public key as a JWK (JSON Web Key) for the OAuth2 bootstrap URL.
     * OCI expects the JWK in the `public_key` query parameter.
     *
     * The JWK format for RSA:
     * {"kty":"RSA","e":"<base64url e>","n":"<base64url n>","alg":"RS256","use":"sig"}
     *
     * IMPORTANT: BigInteger.toByteArray() may include a leading 0x00 sign byte
     * for positive numbers when the high bit is set. This must be stripped
     * for JWK compliance — the modulus must be unsigned big-endian bytes.
     */
    fun publicKeyToJwk(publicKey: RSAPublicKey): String {
        // Strip leading 0x00 sign byte from modulus if present
        val nBytes = publicKey.modulus.toByteArray()
        val nStripped = if (nBytes.isNotEmpty() && nBytes[0] == 0x00.toByte()) {
            nBytes.copyOfRange(1, nBytes.size)
        } else {
            nBytes
        }
        // Also strip from exponent (usually 65537 = 0x010001, no sign byte, but be safe)
        val eBytes = publicKey.publicExponent.toByteArray()
        val eStripped = if (eBytes.isNotEmpty() && eBytes[0] == 0x00.toByte()) {
            eBytes.copyOfRange(1, eBytes.size)
        } else {
            eBytes
        }

        // OCI CLI uses base64.urlsafe_b64encode which INCLUDES padding
        val encoder = Base64.getUrlEncoder() // WITH padding, matching Python CLI
        val n = encoder.encodeToString(nStripped)
        val e = encoder.encodeToString(eStripped)
        return """{"kty":"RSA","e":"$e","n":"$n","alg":"RS256","use":"sig"}"""
    }

    /**
     * Base64url-encode a string (for the OAuth2 public_key parameter).
     * Uses padding to match the OCI CLI's base64.urlsafe_b64encode behavior.
     */
    fun base64UrlEncode(input: String): String {
        return Base64.getUrlEncoder().encodeToString(input.toByteArray())
    }

    /**
     * Sign a request string with RSA-SHA256.
     *
     * OCI signing algorithm:
     * 1. Build the signing string from specific headers
     * 2. Sign with RSA-SHA256
     * 3. Base64-encode the signature
     */
    fun sign(privateKey: java.security.PrivateKey, data: String): String {
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(data.toByteArray())
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    /**
     * Build the OCI Authorization header value for an API request.
     *
     * Format: Signature version="1",keyId="<tenancy>/<user>/<fingerprint>",algorithm="rsa-sha256",headers="...",signature="<base64>"
     */
    fun buildAuthHeader(
        tenancyOcid: String,
        userOcid: String,
        fingerprint: String,
        privateKey: java.security.PrivateKey,
        method: String,
        path: String,
        host: String,
        headers: Map<String, String> = emptyMap(),
    ): String {
        // OCI signing headers (minimal set)
        val signingHeaders = listOf(
            "date" to (headers["date"] ?: java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC))),
            "(request-target)" to "$method $path",
            "host" to host,
        )

        val signingString = signingHeaders.joinToString("\n") { (k, v) -> "$k: $v" }
        val signature = sign(privateKey, signingString)
        val headerNames = signingHeaders.joinToString(",") { it.first }

        val keyId = "ST-$tenancyOcid/$userOcid/$fingerprint"

        return """Signature version="1",keyId="$keyId",algorithm="rsa-sha256",headers="$headerNames",signature="$signature""""
    }

    /**
     * Build the Oracle OAuth2 authorize URL for the bootstrap flow.
     *
     * This is the URL that opens in Chrome Custom Tab for the user to
     * log in to Oracle. After login, Oracle redirects to redirectUri
     * with the security token in the URL fragment.
     */
    fun buildAuthorizeUrl(
        region: String,
        realm: String,
        publicKeyJwkBase64: String,
        redirectUri: String,
        tenancyName: String? = null,
    ): String {
        val params = mutableListOf(
            "action" to "login",
            "client_id" to "iaas_console",
            "response_type" to "token id_token",
            "nonce" to UUID.randomUUID().toString(),
            "scope" to "openid",
            "public_key" to publicKeyJwkBase64,
            "redirect_uri" to redirectUri,
        )
        if (tenancyName != null) {
            params.add("tenant" to tenancyName)
        }

        val queryString = params.joinToString("&") { (k, v) ->
            "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }

        return "https://login.$region.$realm/v1/oauth2/authorize?$queryString"
    }

    /**
     * Extract the security token from the OAuth2 redirect URL fragment.
     *
     * The redirect URL looks like:
     *   zerovpn://auth/callback#security_token=<JWT>&id_token=<JWT>&...
     *
     * Returns the security_token value, or null if not found.
     */
    fun extractSecurityToken(redirectUrl: String): String? {
        val fragment = redirectUrl.substringAfter("#", "")
        val params = fragment.split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            k to v
        }
        return params["security_token"]
    }

    /**
     * Decode a JWT and extract claims (without verification — for
     * extracting user/tenancy OCIDs from the security token).
     */
    fun decodeJwt(jwt: String): Map<String, Any?> {
        val parts = jwt.split(".")
        if (parts.size < 2) return emptyMap()
        val payload = parts[1]
        // Add padding for base64url
        val padded = payload.padEnd((payload.length + 3) / 4 * 4, '=')
        val json = String(Base64.getUrlDecoder().decode(padded))
        // Simple JSON parse — use org.json on Android
        // For spike, return a map of the key claims
        return mapOf(
            "sub" to Regex(""""sub"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1),
            "tenant" to Regex(""""tenant"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1),
            "exp" to Regex(""""exp"\s*:\s*(\d+)"""").find(json)?.groupValues?.get(1)?.toLong(),
        )
    }
}