package com.zerovpn.app.oci

import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Independent Oracle-compatible reference tests.
 *
 * These tests do not trust ZeroVPN's own golden output. They reimplement the
 * documented OCI Signature Version 1 algorithm from Oracle's SDK source and docs
 * and compare ZeroVPN's [OciSignedClient] output against that reference.
 */
class OciSignerOracleReferenceTest {

    private val pair = KeyPairGenerator.getInstance("RSA").run {
        initialize(1024, SecureRandom.getInstance("SHA1PRNG").apply { setSeed("zerovpn-oci-reference-v2".toByteArray()) })
        generateKeyPair()
    }
    private val fingerprint = OciCredentialIdentity.fingerprintOf(pair.public as RSAPublicKey)
    private val tenancyOcid = "ocid1.tenancy.oc1..reference"
    private val userOcid = "ocid1.user.oc1..reference"
    private val apiKeyAuth = OciAuthContext.ApiKey(
        tenancyOcid, userOcid, fingerprint, pair.private, "eu-zurich-1",
    )
    private val fixedClock = Clock.fixed(Instant.parse("2026-07-19T18:42:00Z"), ZoneOffset.UTC)
    private val fixedDate = "Sun, 19 Jul 2026 18:42:00 GMT"

    /** Reference signer rebuilt from Oracle SDK source and public docs. */
    private fun oracleReferenceSign(
        auth: OciAuthContext.ApiKey,
        method: String,
        host: String,
        pathAndQuery: String,
        body: ByteArray? = null,
    ): OracleSignedResult {
        val normalizedMethod = method.uppercase()
        val lowerMethod = normalizedMethod.lowercase()
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(fixedClock.instant().atZone(ZoneOffset.UTC))
        val hasBody = normalizedMethod in setOf("POST", "PUT", "PATCH")
        val bytes = if (hasBody) body ?: ByteArray(0) else null

        // Oracle STANDARD strategy default order (from Constants.java):
        // GENERIC_HEADERS_LIST = [date, (request-target), host]
        // BODY_HEADERS_LIST    = [content-length, content-type, x-content-sha256]
        val signedHeaders = linkedMapOf<String, String>()
        signedHeaders["date"] = date
        signedHeaders["(request-target)"] = "$lowerMethod $pathAndQuery"
        signedHeaders["host"] = host
        if (hasBody) {
            signedHeaders["content-length"] = bytes!!.size.toString()
            signedHeaders["content-type"] = "application/json"
            signedHeaders["x-content-sha256"] = base64(sha256(bytes))
        }

        val stringToSign = signedHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        val signature = signRsaSha256(auth.privateKey, stringToSign)
        val keyId = "${auth.tenancyOcid}/${auth.userOcid}/${auth.fingerprint}"
        val authorization = "Signature headers=\"${signedHeaders.keys.joinToString(" ")}\",keyId=\"$keyId\"," +
            "algorithm=\"rsa-sha256\",signature=\"$signature\",version=\"1\""

        return OracleSignedResult(
            method = normalizedMethod,
            host = host,
            pathAndQuery = pathAndQuery,
            date = date,
            stringToSign = stringToSign,
            signedHeaderNames = signedHeaders.keys.toList(),
            keyId = keyId,
            authorization = authorization,
            signature = signature,
            contentSha256 = signedHeaders["x-content-sha256"],
            contentLength = signedHeaders["content-length"],
        )
    }

    data class OracleSignedResult(
        val method: String,
        val host: String,
        val pathAndQuery: String,
        val date: String,
        val stringToSign: String,
        val signedHeaderNames: List<String>,
        val keyId: String,
        val authorization: String,
        val signature: String,
        val contentSha256: String?,
        val contentLength: String?,
    )

    @Test fun getMatchesIndependentOracleReference() {
        val path = "/20160918/users/${userOcid}/apiKeys"
        val ref = oracleReferenceSign(apiKeyAuth, "GET", "identity.eu-zurich-1.oraclecloud.com", path)
        val zero = OciSignedClient(fixedClock).sign(
            apiKeyAuth, "GET", "identity.eu-zurich-1.oraclecloud.com", path,
        )
        assertEquals("GET signing string must match independent Oracle reference", ref.stringToSign, zero.stringToSign)
        assertEquals("GET signed header names must match reference", ref.signedHeaderNames, zero.signedHeaderNames)
        assertEquals("GET keyId must match reference", ref.keyId, zero.keyId)
        assertEquals("GET Authorization header must match reference", ref.authorization, zero.authorization)
        assertTrue("GET signature must verify", verifySignature(zero.stringToSign, zero.authorization))
    }

    @Test fun postMatchesIndependentOracleReference() {
        val path = "/20160918/users/${userOcid}/apiKeys"
        val body = """{"keyValue":"-----BEGIN PUBLIC KEY-----\nTEST\n-----END PUBLIC KEY-----"}""".toByteArray()
        val ref = oracleReferenceSign(apiKeyAuth, "POST", "identity.eu-zurich-1.oraclecloud.com", path, body)
        val zero = OciSignedClient(fixedClock).sign(
            apiKeyAuth, "POST", "identity.eu-zurich-1.oraclecloud.com", path, body,
        )
        assertEquals("POST signing string must match independent Oracle reference", ref.stringToSign, zero.stringToSign)
        assertEquals("POST signed header names must match reference", ref.signedHeaderNames, zero.signedHeaderNames)
        assertEquals("POST content-length must match reference", ref.contentLength, zero.headers["content-length"])
        assertEquals("POST content-type must match reference", "application/json", zero.headers["content-type"])
        assertEquals("POST x-content-sha256 must match reference", ref.contentSha256, zero.headers["x-content-sha256"])
        assertEquals("POST keyId must match reference", ref.keyId, zero.keyId)
        assertEquals("POST Authorization header must match reference", ref.authorization, zero.authorization)
        assertTrue("POST signature must verify", verifySignature(zero.stringToSign, zero.authorization))
    }

    @Test fun activationGetMatchesIndependentOracleReference() {
        // The exact API-key activation probe ZeroVPN uses: GET /20160918/users/{userOcid}/apiKeys
        val path = "/20160918/users/${userOcid}/apiKeys"
        val ref = oracleReferenceSign(apiKeyAuth, "GET", "identity.eu-zurich-1.oraclecloud.com", path)
        val zero = OciSignedClient(fixedClock).sign(
            apiKeyAuth, "GET", "identity.eu-zurich-1.oraclecloud.com", path,
        )
        assertEquals("Activation GET signing string must match reference", ref.stringToSign, zero.stringToSign)
        assertEquals("Activation GET Authorization must match reference", ref.authorization, zero.authorization)
    }

    @Test fun uploadApiKeyPostMatchesIndependentOracleReference() {
        val path = "/20160918/users/${userOcid}/apiKeys"
        val body = """{"key":"test"}""".toByteArray()
        val ref = oracleReferenceSign(apiKeyAuth, "POST", "identity.eu-zurich-1.oraclecloud.com", path, body)
        val zero = OciSignedClient(fixedClock).sign(
            apiKeyAuth, "POST", "identity.eu-zurich-1.oraclecloud.com", path, body,
        )
        assertEquals("Upload POST signing string must match reference", ref.stringToSign, zero.stringToSign)
        assertEquals("Upload POST Authorization must match reference", ref.authorization, zero.authorization)
    }

    @Test fun activationGetAuthorizationHeadersRawValueIsSpaceSeparated() {
        val path = "/20160918/users/${userOcid}/apiKeys"
        val zero = OciSignedClient(fixedClock).sign(
            apiKeyAuth, "GET", "identity.eu-zurich-1.oraclecloud.com", path,
        )
        val headersRaw = zero.authorization
            .substringAfter("headers=\"").substringBefore("\"")
        assertEquals("Activation GET headers= must be space-separated", "date (request-target) host", headersRaw)
        assertFalse("Activation GET headers= must not contain comma", headersRaw.contains(','))
        assertFalse("Activation GET headers= must not contain brackets", headersRaw.contains('[') || headersRaw.contains(']'))
    }

    @Test fun postAuthorizationHeadersRawValueIsSpaceSeparated() {
        val path = "/20160918/users/${userOcid}/apiKeys"
        val body = """{"key":"test"}""".toByteArray()
        val zero = OciSignedClient(fixedClock).sign(
            apiKeyAuth, "POST", "identity.eu-zurich-1.oraclecloud.com", path, body,
        )
        val headersRaw = zero.authorization
            .substringAfter("headers=\"").substringBefore("\"")
        assertEquals(
            "POST headers= must be space-separated and match signing-string order",
            "date (request-target) host content-length content-type x-content-sha256",
            headersRaw,
        )
        assertFalse("POST headers= must not contain comma", headersRaw.contains(','))
    }

    @Test fun regressionDefaultJoinToStringWouldProduceCommasAndFail() {
        val path = "/20160918/users/${userOcid}/apiKeys"
        val zero = OciSignedClient(fixedClock).sign(
            apiKeyAuth, "GET", "identity.eu-zurich-1.oraclecloud.com", path,
        )
        val headersRaw = zero.authorization
            .substringAfter("headers=\"").substringBefore("\"")
        val commaFormatted = zero.signedHeaderNames.joinToString()
        assertFalse(
            "Authorization headers= must not match default joinToString() comma output",
            headersRaw == commaFormatted,
        )
    }

    @Test fun authorizationHeaderStructureIsWellFormed() {
        val path = "/20160918/instances/ocid1.instance.oc1..test"
        val zero = OciSignedClient(fixedClock).sign(
            apiKeyAuth, "GET", "iaas.eu-zurich-1.oraclecloud.com", path,
        )
        val auth = zero.authorization
        assertTrue("Authorization must start with Signature", auth.startsWith("Signature "))
        assertTrue("Authorization must contain version", auth.contains("version=\"1\""))
        assertTrue("Authorization must contain keyId", auth.contains("keyId=\""))
        assertTrue("Authorization must contain algorithm", auth.contains("algorithm=\"rsa-sha256\""))
        assertTrue("Authorization must contain headers", auth.contains("headers=\""))
        assertTrue("Authorization must contain signature", auth.contains("signature=\""))
        // No malformed separators
        assertFalse("Authorization must not contain bare ST$ prefix for API key", auth.contains("keyId=\"ST\$"))
    }

    @Test fun signingAlgorithmIsPkcs1V15RsaSha256() {
        val path = "/x"
        val zero = OciSignedClient(fixedClock).sign(
            apiKeyAuth, "GET", "identity.eu-zurich-1.oraclecloud.com", path,
        )
        assertTrue("Signature must be PKCS#1 v1.5 RSA-SHA256", verifySignature(zero.stringToSign, zero.authorization))
    }

    @Test fun keyIdContainsTenancyNotCompartment() {
        val zero = OciSignedClient(fixedClock).sign(
            apiKeyAuth, "GET", "identity.eu-zurich-1.oraclecloud.com", "/x",
        )
        val expectedKeyId = "${apiKeyAuth.tenancyOcid}/${apiKeyAuth.userOcid}/${apiKeyAuth.fingerprint}"
        assertEquals("keyId must be tenancy/user/fingerprint", expectedKeyId, zero.keyId)
        assertFalse("keyId must not start with compartment OCID", zero.keyId.startsWith("ocid1.compartment"))
    }

    @Test fun privateKeyDerivesUploadedPublicKeyAndFingerprint() {
        val derivedPub = OciCredentialIdentity.publicKeyFrom(pair.private)
        val uploadedPub = pair.public as RSAPublicKey
        assertEquals("Derived modulus must match uploaded modulus", uploadedPub.modulus, derivedPub.modulus)
        assertEquals("Derived exponent must match uploaded exponent", uploadedPub.publicExponent, derivedPub.publicExponent)
        assertArrayEquals("Derived DER must match uploaded DER", uploadedPub.encoded, derivedPub.encoded)
        val fpFromUploaded = OciCredentialIdentity.fingerprintOf(uploadedPub)
        val fpFromDerived = OciCredentialIdentity.fingerprintOf(derivedPub)
        assertEquals("Fingerprint from uploaded public key must equal fingerprint from derived key", fpFromUploaded, fpFromDerived)
    }

    @Test fun fingerprintFormatMatchesOracleListing() {
        // Oracle lists fingerprints as lowercase colon-separated MD5, 47 chars.
        assertEquals(47, fingerprint.length)
        assertTrue("Fingerprint must be lowercase hex with colons", fingerprint.matches(Regex("([0-9a-f]{2}:){15}[0-9a-f]{2}")))
    }

    @Test fun pathEncodingIsPreservedInSigningString() {
        val rawPath = "/20160918/instances?displayName=A%20B&limit=10"
        val zero = OciSignedClient(fixedClock).sign(
            apiKeyAuth, "GET", "iaas.eu-zurich-1.oraclecloud.com", rawPath,
        )
        assertTrue("Signing string must preserve percent-encoded query path", zero.stringToSign.contains(rawPath))
        assertTrue("(request-target) must contain encoded path", zero.stringToSign.contains("(request-target): get $rawPath"))
    }

    @Test fun headersAttributeMatchesSigningStringOrder() {
        val body = "{\"a\":1}".toByteArray()
        val zero = OciSignedClient(fixedClock).sign(
            apiKeyAuth, "POST", "iaas.eu-zurich-1.oraclecloud.com", "/20160918/vcns", body,
        )
        val namesFromStringToSign = zero.stringToSign.split("\n").map { it.substringBefore(":") }
        val namesFromAuth = zero.authorization
            .substringAfter("headers=\"")
            .substringBefore("\"")
            .split(" ")
        assertEquals("Authorization headers= must list names in signing-string order", namesFromStringToSign, namesFromAuth)
    }

    @Test fun transmittedValuesMatchSignedValues() {
        val body = "{\"a\":1}".toByteArray()
        val zero = OciSignedClient(fixedClock).sign(
            apiKeyAuth, "POST", "iaas.eu-zurich-1.oraclecloud.com", "/20160918/vcns", body,
        )
        assertEquals("host transmitted must match signed", "iaas.eu-zurich-1.oraclecloud.com", zero.headers["host"])
        assertEquals("date transmitted must match signed", fixedDate, zero.headers["date"])
        assertEquals("content-type transmitted must match signed", "application/json", zero.headers["content-type"])
        assertEquals("content-length transmitted must match signed", body.size.toString(), zero.headers["content-length"])
        assertEquals("x-content-sha256 transmitted must match signed", base64(sha256(body)), zero.headers["x-content-sha256"])
    }

    @Test fun securityTokenKeyIdIsStPrefixed() {
        val stAuth = OciAuthContext.SecurityTokenBootstrap(
            securityToken = "test-token-123",
            privateKey = pair.private,
            tenancyOcid = tenancyOcid,
            userOcid = userOcid,
            fingerprint = fingerprint,
        )
        val zero = OciSignedClient(fixedClock).sign(
            stAuth, "GET", "identity.eu-zurich-1.oraclecloud.com", "/x",
        )
        assertTrue("Security-token keyId must start with ST\$", zero.keyId.startsWith("ST\$"))
        assertTrue("Authorization must contain ST\$ keyId", zero.authorization.contains("keyId=\"ST\$"))
    }

    private fun verifySignature(stringToSign: String, authorization: String): Boolean {
        val encoded = authorization.substringAfter("signature=\"").substringBefore("\"")
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(pair.public)
            update(stringToSign.toByteArray(StandardCharsets.UTF_8))
        }
        return verifier.verify(Base64.getDecoder().decode(encoded))
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun signRsaSha256(privateKey: java.security.PrivateKey, stringToSign: String): String {
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(stringToSign.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(signer.sign())
    }
}
