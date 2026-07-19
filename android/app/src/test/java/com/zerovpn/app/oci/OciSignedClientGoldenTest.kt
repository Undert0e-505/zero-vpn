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
    private val fixedDate = "Sat, 18 Jul 2026 08:00:00 GMT"

    @Test fun getWithoutQueryGolden() {
        val request = signer.sign(auth, "GET", "identity.eu-zurich-1.oci.oraclecloud.com", "/20160918/users/user/apiKeys")
        assertEquals(
            "GET signing string must use Oracle order: (request-target), host, date",
            "(request-target): get /20160918/users/user/apiKeys\n" +
                "host: identity.eu-zurich-1.oci.oraclecloud.com\n" +
                "date: $fixedDate",
            request.stringToSign,
        )
        assertEquals(
            "GET signed header names must be exactly (request-target), host, date",
            listOf("(request-target)", "host", "date"),
            request.signedHeaderNames,
        )
        assertGoldenCore(request)
    }

    @Test fun getQueryPreservesExactTransmittedOrderAndEncoding() {
        val request = signer.sign(auth, "GET", "iaas.eu-zurich-1.oraclecloud.com", "/20160918/instances?displayName=A%20B&limit=10")
        assertEquals(
            "GET signing string must use Oracle order: (request-target), host, date",
            "(request-target): get /20160918/instances?displayName=A%20B&limit=10\n" +
                "host: iaas.eu-zurich-1.oraclecloud.com\n" +
                "date: $fixedDate",
            request.stringToSign,
        )
        assertEquals(
            "GET signed header names must be exactly (request-target), host, date",
            listOf("(request-target)", "host", "date"),
            request.signedHeaderNames,
        )
        assertGoldenCore(request)
    }

    @Test fun postJsonGolden() {
        val request = signer.sign(auth, "POST", "iaas.eu-zurich-1.oraclecloud.com", "/20160918/vcns", "{\"a\":1}".toByteArray())
        assertEquals(
            "POST signing string must use Oracle order",
            "(request-target): post /20160918/vcns\n" +
                "host: iaas.eu-zurich-1.oraclecloud.com\n" +
                "date: $fixedDate\n" +
                "x-content-sha256: AVq9f1zFei3ZS3WQ8ErYCEJzkF7jPsXOvq5iJ2qX+GI=\n" +
                "content-type: application/json\n" +
                "content-length: 7",
            request.stringToSign,
        )
        assertEquals(
            "POST signed header names must be exactly (request-target), host, date, x-content-sha256, content-type, content-length",
            listOf("(request-target)", "host", "date", "x-content-sha256", "content-type", "content-length"),
            request.signedHeaderNames,
        )
        assertGoldenCore(request)
    }

    @Test fun putJsonGolden() {
        val request = signer.sign(auth, "PUT", "iaas.eu-zurich-1.oraclecloud.com", "/20160918/vcns/ocid1.vcn.oc1..test", "{\"a\":2}".toByteArray())
        assertEquals(
            "PUT signing string must use Oracle POST/PUT/PATCH order",
            listOf("(request-target)", "host", "date", "x-content-sha256", "content-type", "content-length"),
            request.signedHeaderNames,
        )
        assertTrue(request.stringToSign.contains("x-content-sha256:"))
        assertTrue(request.stringToSign.contains("content-type: application/json"))
        assertTrue(request.stringToSign.contains("content-length: 7"))
        assertGoldenCore(request)
    }

    @Test fun deleteUsesGetOrder() {
        val request = signer.sign(auth, "DELETE", "iaas.eu-zurich-1.oraclecloud.com", "/20160918/instances/ocid1.instance.oc1..test")
        assertEquals(
            "DELETE signing string must use Oracle GET/DELETE order",
            listOf("(request-target)", "host", "date"),
            request.signedHeaderNames,
        )
        assertGoldenCore(request)
    }

    @Test fun apiKeyUploadPostUsesDocumentedPostOrder() {
        // Mirrors OciProvisioner.uploadApiKey: POST to identity API-keys endpoint with a key body.
        val body = """{"keyValue":"-----BEGIN PUBLIC KEY-----\\nMIIB...\\n-----END PUBLIC KEY-----"}""".toByteArray()
        val request = signer.sign(
            auth,
            "POST",
            "identity.eu-zurich-1.oci.oraclecloud.com",
            "/20160918/users/ocid1.user.oc1..golden/apiKeys",
            body,
        )
        assertEquals(
            "API-key upload POST must use documented POST header order",
            listOf("(request-target)", "host", "date", "x-content-sha256", "content-type", "content-length"),
            request.signedHeaderNames,
        )
        assertTrue(request.stringToSign.startsWith("(request-target):"))
        assertTrue(request.stringToSign.contains("host: identity.eu-zurich-1.oci.oraclecloud.com"))
        assertTrue(request.stringToSign.contains("date: $fixedDate"))
        assertTrue(request.stringToSign.contains("x-content-sha256:"))
        assertTrue(request.stringToSign.contains("content-type: application/json"))
        assertTrue(request.stringToSign.contains("content-length: ${body.size}"))
        assertGoldenCore(request)
    }

    @Test fun apiKeyActivationGetUsesDocumentedGetOrder() {
        // Mirrors OciProvisioner.probeApiKeyActivation: GET using API-key auth.
        val request = signer.sign(
            auth,
            "GET",
            "iaas.eu-zurich-1.oraclecloud.com",
            "/20160918/instances/ocid1.instance.oc1..test",
        )
        assertEquals(
            "API-key activation GET must use documented GET header order",
            listOf("(request-target)", "host", "date"),
            request.signedHeaderNames,
        )
        assertEquals(
            "GET signing string must start with (request-target), then host, then date",
            "(request-target): get /20160918/instances/ocid1.instance.oc1..test\n" +
                "host: iaas.eu-zurich-1.oraclecloud.com\n" +
                "date: $fixedDate",
            request.stringToSign,
        )
        assertGoldenCore(request)
    }

    @Test fun authorizationHeadersParameterMatchesSigningStringOrder() {
        val request = signer.sign(auth, "POST", "iaas.eu-zurich-1.oraclecloud.com", "/20160918/vcns", "{\"a\":1}".toByteArray())
        val headersAttr = request.authorization
            .substringAfter("headers=\"")
            .substringBefore("\"")
            .split(" ")
        val signingNames = request.stringToSign.split("\n").map { it.substringBefore(":") }
        assertEquals(
            "Authorization headers= attribute must list names in same order as signing string",
            signingNames,
            headersAttr,
        )
    }

    @Test fun transmittedHeadersMatchSignedValues() {
        val request = signer.sign(auth, "POST", "iaas.eu-zurich-1.oraclecloud.com", "/20160918/vcns", "{\"a\":1}".toByteArray())
        assertEquals("host", "iaas.eu-zurich-1.oraclecloud.com", request.headers["host"])
        assertEquals("date", fixedDate, request.headers["date"])
        assertEquals("content-type", "application/json", request.headers["content-type"])
        assertEquals("content-length", "7", request.headers["content-length"])
        assertEquals("x-content-sha256", "AVq9f1zFei3ZS3WQ8ErYCEJzkF7jPsXOvq5iJ2qX+GI=", request.headers["x-content-sha256"])
    }

    @Test fun foregroundAndBackgroundGetAreByteIdentical() {
        val a = signer.sign(auth, "GET", "identity.eu-zurich-1.oci.oraclecloud.com", "/x?a=1")
        val b = signer.sign(auth, "GET", "identity.eu-zurich-1.oci.oraclecloud.com", "/x?a=1")
        assertEquals(a, b)
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

    private fun assertGoldenCore(request: OciSignedRequest) {
        assertEquals("${auth.tenancyOcid}/${auth.userOcid}/${auth.fingerprint}", request.keyId)
        assertTrue(request.authorization.startsWith("Signature version=\"1\",keyId=\"${request.keyId}\",algorithm=\"rsa-sha256\","))
        assertTrue(request.authorization.contains("headers=\"${request.signedHeaderNames.joinToString(" ")}\""))
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
