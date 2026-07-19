package com.zerovpn.app.oci

import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OciSignedClientGoldenTest {
    private val pair = KeyPairGenerator.getInstance("RSA").run {
        initialize(1024, SecureRandom.getInstance("SHA1PRNG").apply { setSeed("zerovpn-oci-golden-v1".toByteArray()) })
        generateKeyPair()
    }
    private val auth = OciAuthContext.ApiKey(
        "ocid1.tenancy.oc1..golden", "ocid1.user.oc1..golden",
        OciCredentialIdentity.fingerprintOf(pair.public as RSAPublicKey), pair.private, "eu-zurich-1",
    )
    private val signer = OciSignedClient(Clock.fixed(Instant.parse("2026-07-18T08:00:00Z"), ZoneOffset.UTC))

    @Test fun getWithoutQueryGolden() = assertGolden(
        signer.sign(auth, "GET", "identity.eu-zurich-1.oci.oraclecloud.com", "/20160918/users/user/apiKeys"),
        "date: Sat, 18 Jul 2026 08:00:00 GMT\n" +
            "(request-target): get /20160918/users/user/apiKeys\n" +
            "host: identity.eu-zurich-1.oci.oraclecloud.com",
        listOf("date", "(request-target)", "host"),
    )

    @Test fun getQueryPreservesExactTransmittedOrderAndEncoding() = assertGolden(
        signer.sign(auth, "GET", "iaas.eu-zurich-1.oraclecloud.com", "/20160918/instances?displayName=A%20B&limit=10"),
        "date: Sat, 18 Jul 2026 08:00:00 GMT\n" +
            "(request-target): get /20160918/instances?displayName=A%20B&limit=10\n" +
            "host: iaas.eu-zurich-1.oraclecloud.com",
        listOf("date", "(request-target)", "host"),
    )

    @Test fun postJsonGolden() {
        val request = signer.sign(auth, "POST", "iaas.eu-zurich-1.oraclecloud.com", "/20160918/vcns", "{\"a\":1}".toByteArray())
        assertTrue(request.stringToSign.contains("content-length: 7"))
        assertTrue(request.stringToSign.contains("content-type: application/json"))
        assertTrue(request.stringToSign.contains("x-content-sha256: AVq9f1zFei3ZS3WQ8ErYCEJzkF7jPsXOvq5iJ2qX+GI="))
        assertGolden(request, request.stringToSign,
            listOf("date", "(request-target)", "host", "content-length", "content-type", "x-content-sha256"))
    }

    @Test fun foregroundAndBackgroundGetAreByteIdentical() {
        val a = signer.sign(auth, "GET", "identity.eu-zurich-1.oci.oraclecloud.com", "/x?a=1")
        val b = signer.sign(auth, "GET", "identity.eu-zurich-1.oci.oraclecloud.com", "/x?a=1")
        assertEquals(a, b)
    }

    @Test fun activationGetAuthorizationHeadersRawValueIsSpaceSeparated() {
        val request = signer.sign(auth, "GET", "identity.eu-zurich-1.oraclecloud.com", "/20160918/users/user/apiKeys")
        val headersRaw = request.authorization
            .substringAfter("headers=\"").substringBefore("\"")
        assertEquals("date (request-target) host", headersRaw)
    }

    @Test fun activationGetAuthorizationHeadersContainsNoComma() {
        val request = signer.sign(auth, "GET", "identity.eu-zurich-1.oraclecloud.com", "/20160918/users/user/apiKeys")
        val headersRaw = request.authorization
            .substringAfter("headers=\"").substringBefore("\"")
        assertFalse("headers= must not contain a comma", headersRaw.contains(','))
    }

    @Test fun activationGetAuthorizationHeadersContainsNoBrackets() {
        val request = signer.sign(auth, "GET", "identity.eu-zurich-1.oraclecloud.com", "/20160918/users/user/apiKeys")
        val headersRaw = request.authorization
            .substringAfter("headers=\"").substringBefore("\"")
        assertFalse("headers= must not contain '['", headersRaw.contains('['))
        assertFalse("headers= must not contain ']'", headersRaw.contains(']'))
    }

    @Test fun authorizationHeadersMatchesSigningStringLineOrder() {
        val body = "{\"a\":1}".toByteArray()
        val request = signer.sign(auth, "POST", "iaas.eu-zurich-1.oraclecloud.com", "/20160918/vcns", body)
        val namesFromStringToSign = request.stringToSign.split("\n").map { it.substringBefore(":") }
        val headersRaw = request.authorization
            .substringAfter("headers=\"").substringBefore("\"")
        val namesFromAuth = headersRaw.split(" ")
        assertEquals(namesFromStringToSign, namesFromAuth)
    }

    @Test fun postAuthorizationHeadersRawValueIsSpaceSeparated() {
        val body = "{\"a\":1}".toByteArray()
        val request = signer.sign(auth, "POST", "iaas.eu-zurich-1.oraclecloud.com", "/20160918/vcns", body)
        val headersRaw = request.authorization
            .substringAfter("headers=\"").substringBefore("\"")
        assertEquals(
            "date (request-target) host content-length content-type x-content-sha256",
            headersRaw,
        )
    }

    @Test fun regressionHeadersValueMustNotUseDefaultJoinToStringCommaFormatting() {
        // A default Kotlin List.joinToString() would produce "date, (request-target), host".
        // Oracle requires space-separated header names.
        val request = signer.sign(auth, "GET", "identity.eu-zurich-1.oraclecloud.com", "/x")
        val headersRaw = request.authorization
            .substringAfter("headers=\"").substringBefore("\"")
        assertFalse(
            "headers= must not use default comma formatting",
            headersRaw == request.signedHeaderNames.joinToString(),
        )
        assertEquals("date (request-target) host", headersRaw)
    }

    @Test fun foregroundAndBackgroundPostAreByteIdentical() {
        val body = "{\"fixed\":true}".toByteArray()
        val a = signer.sign(auth, "POST", "iaas.eu-zurich-1.oraclecloud.com", "/x", body)
        val b = signer.sign(auth, "POST", "iaas.eu-zurich-1.oraclecloud.com", "/x", body)
        assertEquals(a.authorization, b.authorization)
        assertTrue(a.body!!.contentEquals(b.body!!))
    }

    @Test fun rejectsWhitespaceInDurableKeyIdentity() {
        val bad = auth.copy(tenancyOcid = auth.tenancyOcid + " ")
        val result = runCatching { signer.sign(bad, "GET", "identity.test", "/x") }
        assertTrue(result.isFailure)
    }

    private fun assertGolden(request: OciSignedRequest, expected: String, names: List<String>) {
        assertEquals(expected, request.stringToSign)
        assertEquals(names, request.signedHeaderNames)
        assertEquals("${auth.tenancyOcid}/${auth.userOcid}/${auth.fingerprint}", request.keyId)
        assertTrue(request.authorization.startsWith("Signature version=\"1\",keyId=\"${request.keyId}\",algorithm=\"rsa-sha256\","))
        assertTrue(request.authorization.contains("headers=\"${names.joinToString(" ")}\""))
        assertFalse(request.authorization.contains("ST$"))
        val encoded = request.authorization.substringAfter("signature=\"").substringBefore('"')
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(pair.public)
            update(request.stringToSign.toByteArray(Charsets.UTF_8))
        }
        assertTrue(verifier.verify(Base64.getDecoder().decode(encoded)))
        assertEquals(64, request.stringToSignSha256.length)
    }
}
