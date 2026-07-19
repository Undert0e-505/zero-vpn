package com.zerovpn.app.oci

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.function.Supplier
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider
import com.oracle.bmc.http.signing.DefaultRequestSigner
import com.oracle.bmc.http.signing.SigningStrategy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Oracle official SDK signer comparison for the API-key activation GET.
 *
 * Uses the same deterministic RSA key, tenancy, user, fingerprint, host and date
 * as ZeroVPN's golden tests, and signs the same activation GET path with
 * Oracle's own Java SDK [DefaultRequestSigner]. Then compares both Authorization
 * headers field-by-field. This test does not call Oracle cloud services.
 */
class OciSdkSignerComparisonTest {

    private val pair = KeyPairGenerator.getInstance("RSA").run {
        initialize(
            1024,
            SecureRandom.getInstance("SHA1PRNG").apply { setSeed("zerovpn-oci-reference-v2".toByteArray()) },
        )
        generateKeyPair()
    }
    private val fingerprint = OciCredentialIdentity.fingerprintOf(pair.public as RSAPublicKey)
    private val tenancyOcid = "ocid1.tenancy.oc1..reference"
    private val userOcid = "ocid1.user.oc1..reference"
    private val fixedClock = Clock.fixed(Instant.parse("2026-07-19T18:42:00Z"), ZoneOffset.UTC)
    private val fixedDate = "Sun, 19 Jul 2026 18:42:00 GMT"

    private val apiKeyAuth = OciAuthContext.ApiKey(
        tenancyOcid, userOcid, fingerprint, pair.private, "eu-zurich-1",
    )

    /** ZeroVPN signer for the activation GET. */
    private fun zeroVpnSign(): OciSignedRequest =
        OciSignedClient(fixedClock).sign(
            apiKeyAuth,
            "GET",
            "identity.eu-zurich-1.oraclecloud.com",
            "/20160918/users/${userOcid}/apiKeys",
        )

    /** Oracle SDK signer for the same activation GET. */
    private fun oracleSdkSign(): OracleSdkResult {
        val provider = SimpleAuthenticationDetailsProvider.builder()
            .tenantId(tenancyOcid)
            .userId(userOcid)
            .fingerprint(fingerprint)
            .privateKeySupplier(Supplier<InputStream> { ByteArrayInputStream(pair.private.toPemPkcs8()) })
            .build()
        val signer = DefaultRequestSigner.createRequestSigner(provider, SigningStrategy.STANDARD)
        val uri = java.net.URI.create(
            "https://identity.eu-zurich-1.oraclecloud.com/20160918/users/${userOcid}/apiKeys",
        )
        val requestHeaders = mutableMapOf<String, List<String>>()
        requestHeaders["date"] = listOf(fixedDate)
        // Force the SDK to use the exact date rather than the current time.
        val signedHeaders = signer.signRequest(uri, "GET", requestHeaders, null)

        // SDK may return header names in any case. Search case-insensitively.
        val authorizationEntry = signedHeaders.entries
            .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
        val authorization = authorizationEntry?.value
            ?: throw AssertionError(
                "Oracle SDK did not return an Authorization header. Returned keys: ${signedHeaders.keys}"
            )
        val date = signedHeaders["date"] ?: fixedDate
        val host = signedHeaders["host"] ?: uri.host
        return OracleSdkResult(
            uri = uri,
            httpMethod = "GET",
            date = date,
            host = host,
            authorization = authorization,
            allHeaders = signedHeaders,
        )
    }

    data class OracleSdkResult(
        val uri: java.net.URI,
        val httpMethod: String,
        val date: String,
        val host: String,
        val authorization: String,
        val allHeaders: Map<String, String>,
    )

    private fun parseAuthorization(authorization: String): AuthorizationParts {
        require(authorization.startsWith("Signature ")) { "Expected 'Signature ' prefix" }
        val params = authorization.removePrefix("Signature ")
            .split(",")
            .associate { part ->
                val key = part.substringBefore("=").trim()
                val value = part.substringAfter("=").trim().removeSurrounding("\"")
                key to value
            }
        return AuthorizationParts(
            version = params["version"],
            keyId = params["keyId"],
            algorithm = params["algorithm"],
            headers = params["headers"],
            signature = params["signature"],
        )
    }

    data class AuthorizationParts(
        val version: String?,
        val keyId: String?,
        val algorithm: String?,
        val headers: String?,
        val signature: String?,
    )

    @Test
    fun oracleSdkDependencyIsAvailable() {
        val result = oracleSdkSign()
        assertTrue("Oracle SDK must produce non-empty Authorization header", result.authorization.isNotBlank())
    }

    @Test
    fun activationGetAuthorizationMatchesOracleSdk() {
        val zero = zeroVpnSign()
        val oracle = oracleSdkSign()

        // Full Authorization header must now match byte-for-byte.
        assertEquals(
            "Activation GET Authorization header must match Oracle SDK exactly",
            oracle.authorization,
            zero.authorization,
        )

        val zeroParts = parseAuthorization(zero.authorization)
        val oracleParts = parseAuthorization(oracle.authorization)

        assertEquals("version must match", oracleParts.version, zeroParts.version)
        assertEquals("keyId must match", oracleParts.keyId, zeroParts.keyId)
        assertEquals("algorithm must match", oracleParts.algorithm, zeroParts.algorithm)
        assertEquals(
            "headers raw value must match",
            oracleParts.headers,
            zeroParts.headers,
        )

        // Compare signed header sets independently of order string formatting.
        val oracleHeaderNames = oracleParts.headers?.split(" ") ?: emptyList()
        val zeroHeaderNames = zeroParts.headers?.split(" ") ?: emptyList()
        assertArrayEquals(
            "Signed header set must match Oracle SDK",
            oracleHeaderNames.toTypedArray(),
            zeroHeaderNames.toTypedArray(),
        )

        // Both signatures should verify against their own signing strings.
        assertTrue(
            "ZeroVPN signature must verify",
            verifySignature(zero.stringToSign, zero.authorization),
        )
    }

    @Test
    fun requestTargetMatchesOracleSdk() {
        val zero = zeroVpnSign()
        val oracle = oracleSdkSign()
        // Oracle SDK request-target is implicit from URI + method.
        val expectedTarget = "get ${oracle.uri.rawPath}${oracle.uri.rawQuery?.let { "?$it" } ?: ""}"
        assertTrue(
            "ZeroVPN signing string must contain the same request-target as the Oracle SDK URI",
            zero.stringToSign.contains("(request-target): $expectedTarget"),
        )
    }

    @Test
    fun hostInSigningStringMatchesOracleSdk() {
        val zero = zeroVpnSign()
        val oracle = oracleSdkSign()
        assertEquals("host header must match Oracle SDK", oracle.host, zero.host)
        assertTrue(
            "ZeroVPN signing string must contain Oracle SDK host",
            zero.stringToSign.contains("host: ${oracle.host}"),
        )
    }

    @Test
    fun dateInSigningStringMatchesOracleSdk() {
        val zero = zeroVpnSign()
        val oracle = oracleSdkSign()
        assertEquals("date header must match Oracle SDK", oracle.date, zero.date)
        assertTrue(
            "ZeroVPN signing string must contain Oracle SDK date",
            zero.stringToSign.contains("date: ${oracle.date}"),
        )
    }

    @Test
    fun authorizationParameterFormattingMatchesOracleSdk() {
        val zero = zeroVpnSign()
        val oracle = oracleSdkSign()

        val zeroOrder = zero.authorization.removePrefix("Signature ").split(",").map { it.substringBefore("=").trim() }
        val oracleOrder = oracle.authorization.removePrefix("Signature ").split(",").map { it.substringBefore("=").trim() }

        assertEquals(
            "ZeroVPN Authorization parameter order must equal Oracle SDK order",
            listOf("headers", "keyId", "algorithm", "signature", "version"),
            zeroOrder,
        )
        assertEquals(
            "Oracle SDK parameter order",
            listOf("headers", "keyId", "algorithm", "signature", "version"),
            oracleOrder,
        )
    }

    private fun PrivateKey.toPemPkcs8(): ByteArray {
        val base64 = Base64.getEncoder().encodeToString(encoded)
        val wrapped = base64.chunked(64).joinToString("\n")
        val pem = "-----BEGIN PRIVATE KEY-----\n$wrapped\n-----END PRIVATE KEY-----\n"
        return pem.toByteArray(StandardCharsets.UTF_8)
    }

    private fun verifySignature(stringToSign: String, authorization: String): Boolean {
        val encoded = authorization.substringAfter("signature=\"").substringBefore("\"")
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(pair.public)
            update(stringToSign.toByteArray(StandardCharsets.UTF_8))
        }
        return verifier.verify(Base64.getDecoder().decode(encoded))
    }
}
