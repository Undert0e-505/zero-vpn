package com.zerovpn.app.oci

import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for final-request diagnostics.
 *
 * These use fake OkHttp Request objects where possible and never call Oracle.
 * They verify that the diagnostics detect mismatches between the signed request
 * and what OkHttp actually transmits, and that no secrets are logged.
 */
class OciFinalRequestDiagnosticsTest {

    private val pair = KeyPairGenerator.getInstance("RSA").run {
        initialize(1024, SecureRandom.getInstance("SHA1PRNG").apply { setSeed("zerovpn-oci-final-request".toByteArray()) })
        generateKeyPair()
    }
    private val fingerprint = OciCredentialIdentity.fingerprintOf(pair.public as RSAPublicKey)
    private val auth = OciAuthContext.ApiKey(
        tenancyOcid = "ocid1.tenancy.oc1..final",
        userOcid = "ocid1.user.oc1..final",
        fingerprint = fingerprint,
        privateKey = pair.private,
        region = "eu-zurich-1",
    )
    private val fixedClock = Clock.fixed(Instant.parse("2026-07-19T19:17:00Z"), ZoneOffset.UTC)

    @Test
    fun detectsMissingHostHeader() {
        val signed = sign("/20160918/users/ocid1.user.oc1..final/apiKeys")
        val finalRequest = Request.Builder()
            .url("https://identity.eu-zurich-1.oraclecloud.com/20160918/users/ocid1.user.oc1..final/apiKeys")
            .header("date", signed.date)
            .header("Authorization", signed.authorization)
            .get()
            .build()
        val diag = runDiagnostics(signed, finalRequest)
        assertEquals("false", diag["finalRequestMatchesSignedValues"])
        assertTrue(
            "mismatch should mention host header",
            diag["finalRequestMismatch"]!!.contains("host header", ignoreCase = true),
        )
    }

    @Test
    fun detectsChangedDateHeader() {
        val signed = sign("/20160918/users/ocid1.user.oc1..final/apiKeys")
        val finalRequest = Request.Builder()
            .url("https://identity.eu-zurich-1.oraclecloud.com/20160918/users/ocid1.user.oc1..final/apiKeys")
            .header("date", "Sun, 19 Jul 2026 19:18:00 GMT")
            .header("host", signed.host)
            .header("Authorization", signed.authorization)
            .get()
            .build()
        val diag = runDiagnostics(signed, finalRequest)
        assertEquals("false", diag["finalRequestMatchesSignedValues"])
        assertTrue(
            "mismatch should mention date header",
            diag["finalRequestMismatch"]!!.contains("date header", ignoreCase = true),
        )
    }

    @Test
    fun detectsPathEncodingMismatch() {
        val signed = sign("/20160918/users/ocid1.user.oc1..final/apiKeys")
        val finalRequest = Request.Builder()
            .url("https://identity.eu-zurich-1.oraclecloud.com/20160918/users/ocid1.user.oc1..different/apiKeys")
            .header("date", signed.date)
            .header("host", signed.host)
            .header("Authorization", signed.authorization)
            .get()
            .build()
        val diag = runDiagnostics(signed, finalRequest)
        assertEquals("false", diag["finalRequestMatchesSignedValues"])
        assertTrue(
            "mismatch should mention encoded path",
            diag["finalRequestMismatch"]!!.contains("encoded path", ignoreCase = true),
        )
    }

    @Test
    fun passesWhenFinalRequestEqualsSignedRequest() {
        val signed = sign("/20160918/users/ocid1.user.oc1..final/apiKeys")
        val finalRequest = Request.Builder()
            .url("https://identity.eu-zurich-1.oraclecloud.com${signed.pathAndQuery}")
            .header("date", signed.date)
            .header("host", signed.host)
            .header("Authorization", signed.authorization)
            .get()
            .build()
        val diag = runDiagnostics(signed, finalRequest)
        assertEquals("true", diag["finalRequestMatchesSignedValues"])
        assertEquals("none", diag["finalRequestMismatch"])
    }

    @Test
    fun doesNotLogFullAuthorizationHeader() {
        val signed = sign("/20160918/users/ocid1.user.oc1..final/apiKeys")
        val finalRequest = Request.Builder()
            .url("https://identity.eu-zurich-1.oraclecloud.com${signed.pathAndQuery}")
            .header("date", signed.date)
            .header("host", signed.host)
            .header("Authorization", signed.authorization)
            .get()
            .build()
        val diag = runDiagnostics(signed, finalRequest)
        for ((_, value) in diag) {
            assertFalse(
                "diagnostic must not contain the full Authorization header",
                value.contains(signed.authorization),
            )
        }
    }

    @Test
    fun doesNotLogSignatureValue() {
        val signed = sign("/20160918/users/ocid1.user.oc1..final/apiKeys")
        val signature = signed.authorization
            .substringAfter("signature=\"").substringBefore("\"")
        val finalRequest = Request.Builder()
            .url("https://identity.eu-zurich-1.oraclecloud.com${signed.pathAndQuery}")
            .header("date", signed.date)
            .header("host", signed.host)
            .header("Authorization", signed.authorization)
            .get()
            .build()
        val diag = runDiagnostics(signed, finalRequest)
        for ((_, value) in diag) {
            assertFalse(
                "diagnostic must not contain the signature value",
                value.contains(signature),
            )
        }
    }

    @Test
    fun detectsMissingAuthorizationHeader() {
        val signed = sign("/20160918/users/ocid1.user.oc1..final/apiKeys")
        val finalRequest = Request.Builder()
            .url("https://identity.eu-zurich-1.oraclecloud.com${signed.pathAndQuery}")
            .header("date", signed.date)
            .header("host", signed.host)
            .get()
            .build()
        val diag = runDiagnostics(signed, finalRequest)
        assertEquals("false", diag["finalRequestMatchesSignedValues"])
        assertTrue(
            "mismatch should mention authorization header missing",
            diag["finalRequestMismatch"]!!.contains("authorization header missing", ignoreCase = true),
        )
    }

    @Test
    fun postRequestBodyHeadersAreCompared() {
        val body = """{"key":"test"}""".toByteArray()
        val signed = sign("/20160918/users/ocid1.user.oc1..final/apiKeys", body)
        val finalRequest = Request.Builder()
            .url("https://identity.eu-zurich-1.oraclecloud.com${signed.pathAndQuery}")
            .header("date", signed.date)
            .header("host", signed.host)
            .header("Authorization", signed.authorization)
            .header("content-type", "application/json")
            .header("content-length", body.size.toString())
            .header("x-content-sha256", signed.headers["x-content-sha256"]!!)
            .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        val diag = runDiagnostics(signed, finalRequest)
        assertEquals("true", diag["finalRequestMatchesSignedValues"])
    }

    private fun sign(path: String, body: ByteArray? = null): OciSignedRequest {
        return OciSignedClient(fixedClock).sign(
            auth = auth,
            method = if (body == null) "GET" else "POST",
            host = "identity.eu-zurich-1.oraclecloud.com",
            pathAndQuery = path,
            body = body,
        )
    }

    private fun runDiagnostics(signed: OciSignedRequest, finalRequest: Request): Map<String, String> {
        return OciSignerDiagnostics.buildFinalRequestDiagnostics(
            signedMethod = signed.method,
            signedUrl = "https://${signed.host}${signed.pathAndQuery}",
            signedEncodedPath = signed.pathAndQuery.substringBefore('?'),
            signedQuery = signed.pathAndQuery.substringAfter('?', ""),
            signedHost = signed.host,
            signedDate = signed.date,
            signedContentSha256 = signed.headers["x-content-sha256"],
            signedContentType = signed.headers["content-type"],
            signedContentLength = signed.headers["content-length"],
            signedAuthorization = signed.authorization,
            finalMethod = finalRequest.method,
            finalUrl = finalRequest.url.toString(),
            finalEncodedPath = finalRequest.url.encodedPath,
            finalQuery = finalRequest.url.query,
            finalUrlHost = finalRequest.url.host,
            finalHostHeader = finalRequest.header("host"),
            finalDateHeader = finalRequest.header("date"),
            finalContentSha256Header = finalRequest.header("x-content-sha256"),
            finalContentTypeHeader = finalRequest.header("content-type"),
            finalContentLengthHeader = finalRequest.header("content-length"),
            finalAuthorizationHeader = finalRequest.header("Authorization"),
        )
    }
}
