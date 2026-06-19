package com.zerovpn.app.oci

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.UUID
import org.json.JSONObject

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
     * Compute the MD5 fingerprint of a public key (OCI API key fingerprint format).
     * MD5 of the DER-encoded public key, colon-separated hex (16 bytes).
     */
    fun md5Fingerprint(publicKey: RSAPublicKey): String {
        val der = publicKey.encoded
        val md5 = MessageDigest.getInstance("MD5").digest(der)
        return md5.joinToString(":") { "%02x".format(it) }
    }

    /**
     * Encode an RSA public key as a JWK (JSON Web Key) for the OAuth2 bootstrap URL.
     * OCI expects the JWK in the `public_key` query parameter.
     *
     * Correct format (matching OCI CLI):
     * {"kty":"RSA","n":"<base64url no padding>","e":"<base64url no padding>","kid":"Ignored"}
     *
     * - NO alg field, NO use field
     * - kid is literal "Ignored"
     * - n and e are base64url WITHOUT padding (strip = characters)
     */
    fun publicKeyToJwk(publicKey: RSAPublicKey): String {
        // Strip leading 0x00 sign byte from modulus if present
        val nBytes = publicKey.modulus.toByteArray()
        val nStripped = if (nBytes.isNotEmpty() && nBytes[0] == 0x00.toByte()) {
            nBytes.copyOfRange(1, nBytes.size)
        } else {
            nBytes
        }
        val eBytes = publicKey.publicExponent.toByteArray()
        val eStripped = if (eBytes.isNotEmpty() && eBytes[0] == 0x00.toByte()) {
            eBytes.copyOfRange(1, eBytes.size)
        } else {
            eBytes
        }

        // base64url WITHOUT padding for n and e
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val n = encoder.encodeToString(nStripped)
        val e = encoder.encodeToString(eStripped)
        return """{"kty":"RSA","n":"$n","e":"$e","kid":"Ignored"}"""
    }

    /**
     * Base64url-encode a string (for the OAuth2 public_key parameter).
     * The outer encoding uses padding (matching the OCI CLI's base64.urlsafe_b64encode).
     */
    fun base64UrlEncode(input: String): String {
        return Base64.getUrlEncoder().encodeToString(input.toByteArray())
    }

    /**
     * Sign a request string with RSA-SHA256.
     */
    fun sign(privateKey: PrivateKey, data: String): String {
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(data.toByteArray())
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    /**
     * Build the OCI Authorization header value for an API request.
     *
     * For security token auth: keyId = "ST-{tenancy}/{user}/{fingerprint}"
     * For API key auth: keyId = "{tenancy}/{user}/{fingerprint}" (no ST- prefix)
     *
     * @param useSecurityToken true for security token auth, false for API key auth
     */
    fun buildAuthHeader(
        tenancyOcid: String,
        userOcid: String,
        fingerprint: String,
        privateKey: PrivateKey,
        method: String,
        path: String,
        host: String,
        headers: Map<String, String> = emptyMap(),
        useSecurityToken: Boolean = true,
    ): String {
        val date = headers["date"] ?: java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC))

        val signingHeaders = listOf(
            "date" to date,
            "(request-target)" to "$method $path",
            "host" to host,
        )

        val signingString = signingHeaders.joinToString("\n") { (k, v) -> "$k: $v" }
        val signature = sign(privateKey, signingString)
        val headerNames = signingHeaders.joinToString(",") { it.first }

        val keyIdPrefix = if (useSecurityToken) "ST-" else ""
        val keyId = "$keyIdPrefix$tenancyOcid/$userOcid/$fingerprint"

        return """Signature version="1",keyId="$keyId",algorithm="rsa-sha256",headers="$headerNames",signature="$signature""""
    }

    /**
     * Build the Oracle OAuth2 authorize URL for the bootstrap flow.
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
     * Decode a JWT and extract claims (without verification — for
     * extracting user/tenancy OCIDs from the security token).
     * Uses org.json.JSONObject for proper JSON parsing.
     */
    fun decodeJwt(jwt: String): Map<String, Any?> {
        val parts = jwt.split(".")
        if (parts.size < 2) return emptyMap()
        val payload = parts[1]
        // Add padding for base64url
        val padded = payload.padEnd((payload.length + 3) / 4 * 4, '=')
        val json = String(Base64.getUrlDecoder().decode(padded))
        val jsonObj = JSONObject(json)
        return mapOf(
            "sub" to jsonObj.optString("sub"),
            "tenant" to jsonObj.optString("tenant"),
            "exp" to jsonObj.optLong("exp", 0L).takeIf { it > 0 },
        )
    }

    /**
     * Map OCI region short names to full names.
     */
    private val regionShortNames = mapOf(
        "uk-london-1" to "uk-london-1",
        "us-ashburn-1" to "us-ashburn-1",
        "us-phoenix-1" to "us-phoenix-1",
        "eu-frankfurt-1" to "eu-frankfurt-1",
        "ap-tokyo-1" to "ap-tokyo-1",
        "ap-sydney-1" to "ap-sydney-1",
        "ap-mumbai-1" to "ap-mumbai-1",
        "ap-seoul-1" to "ap-seoul-1",
        "ap-osaka-1" to "ap-osaka-1",
        "ca-toronto-1" to "ca-toronto-1",
        "sa-saopaulo-1" to "sa-saopaulo-1",
        "me-jeddah-1" to "me-jeddah-1",
        "me-dubai-1" to "me-dubai-1",
        "eu-amsterdam-1" to "eu-amsterdam-1",
        "eu-zurich-1" to "eu-zurich-1",
        "eu-madrid-1" to "eu-madrid-1",
        "eu-milan-1" to "eu-milan-1",
        "eu-stockholm-1" to "eu-stockholm-1",
        "eu-paris-1" to "eu-paris-1",
        "uk-cardiff-1" to "uk-cardiff-1",
        "ap-chuncheon-1" to "ap-chuncheon-1",
        "ap-hyderabad-1" to "ap-hyderabad-1",
        "ap-melbourne-1" to "ap-melbourne-1",
        "il-jerusalem-1" to "il-jerusalem-1",
        "mx-queretaro-1" to "mx-queretaro-1",
        "sa-vinhedo-1" to "sa-vinhedo-1",
        "af-johannesburg-1" to "af-johannesburg-1",
    )

    /**
     * Map region to realm domain.
     */
    private val regionRealms = mapOf(
        "uk-london-1" to "oc1",
        "uk-cardiff-1" to "oc1",
        "us-ashburn-1" to "oc1",
        "us-phoenix-1" to "oc1",
        "ca-toronto-1" to "oc1",
        "eu-frankfurt-1" to "oc1",
        "eu-amsterdam-1" to "oc1",
        "eu-zurich-1" to "oc1",
        "eu-madrid-1" to "oc1",
        "eu-milan-1" to "oc1",
        "eu-stockholm-1" to "oc1",
        "eu-paris-1" to "oc1",
        "ap-tokyo-1" to "oc1",
        "ap-sydney-1" to "oc1",
        "ap-mumbai-1" to "oc1",
        "ap-seoul-1" to "oc1",
        "ap-osaka-1" to "oc1",
        "ap-chuncheon-1" to "oc1",
        "ap-hyderabad-1" to "oc1",
        "ap-melbourne-1" to "oc1",
        "sa-saopaulo-1" to "oc1",
        "sa-vinhedo-1" to "oc1",
        "me-jeddah-1" to "oc1",
        "me-dubai-1" to "oc1",
        "il-jerusalem-1" to "oc1",
        "mx-queretaro-1" to "oc1",
        "af-johannesburg-1" to "oc1",
    )

    fun getRealmForRegion(region: String): String {
        return regionRealms[region] ?: "oc1"
    }
}