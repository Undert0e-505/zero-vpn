package com.zerovpn.app.oci

import android.content.Context
import android.os.NetworkOnMainThreadException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/**
 * Tests for the Oracle SDK runtime-signer diagnostic.
 *
 * These tests prove:
 * - Diagnostic mode selects Oracle SDK runtime signer for the activation GET.
 * - Normal mode still uses the existing ZeroVPN signer unless explicitly changed.
 * - SDK diagnostic request uses the uploaded fingerprint and generated private key.
 * - SDK diagnostic request keyId is tenancy/user/fingerprint.
 * - SDK diagnostic does not upload another key (only one GET).
 * - SDK diagnostic does not create cloud resources before validation result.
 * - SDK diagnostic blocking work runs on an IO/background dispatcher.
 * - SDK diagnostic wrapper does not throw NetworkOnMainThreadException.
 * - Safe diagnostics do not expose full Authorization, signature or private key.
 */
@RunWith(RobolectricTestRunner::class)
class OciOracleSdkActivationDiagnosticTest {

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun mockClient(handler: (Interceptor.Chain) -> okhttp3.Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> handler(chain) })
            .build()
    }

    private fun buildAuthResult(
        region: String = "eu-zurich-1",
        isDevMode: Boolean = false,
    ): Pair<OciProvisioner, OciProvisioner.AuthResult> {
        val provisioner = OciProvisioner(
            context = context(),
            region = region,
            isDevMode = isDevMode,
        )
        val keyPair = OciRequestSigner.generateKeyPair()
        val fingerprint = OciRequestSigner.md5Fingerprint(keyPair.public as java.security.interfaces.RSAPublicKey)
        val auth = OciProvisioner.AuthResult(
            securityToken = "test-security-token",
            privateKey = keyPair.private,
            keyPair = keyPair,
            userOcid = "ocid1.user.oc1..test-user",
            tenancyOcid = "ocid1.tenancy.oc1..test-tenancy",
            fingerprint = fingerprint,
            selectedRegion = region,
        )
        return provisioner to auth
    }

    /**
     * Dispatcher that records whether it was asked to dispatch coroutine work.
     * Used to prove the diagnostic path dispatches blocking work off the caller thread.
     */
    private class RecordingDispatcher : CoroutineDispatcher() {
        var dispatched = false
            private set

        var dispatchedBlock: Runnable? = null
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched = true
            dispatchedBlock = block
            // Run on a fresh background thread so the test still exercises off-thread execution.
            Thread(block, "recording-dispatcher-thread").start()
        }

        fun reset() {
            dispatched = false
            dispatchedBlock = null
        }
    }

    /**
     * Interceptor that mimics Android StrictMode by throwing NetworkOnMainThreadException
     * when the HTTP call runs on a thread whose name contains "main".
     */
    private class StrictModeNetworkInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            if (Thread.currentThread().name.contains("main", ignoreCase = true)) {
                throw NetworkOnMainThreadException()
            }
            return chain.proceed(chain.request())
        }
    }

    @Test
    fun `diagnostic mode selects oracle sdk runtime signer for activation GET`() = runBlocking {
        val captured = mutableListOf<okhttp3.Request>()
        val client = mockClient { chain ->
            captured += chain.request()
            okhttp3.Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody(null))
                .build()
        }

        val keyPair = OciRequestSigner.generateKeyPair()
        val fingerprint = OciRequestSigner.md5Fingerprint(keyPair.public as java.security.interfaces.RSAPublicKey)
        val result = OciOracleSdkActivationDiagnostic.run(
            httpClient = client,
            tenancyOcid = "ocid1.tenancy.oc1..test-tenancy",
            userOcid = "ocid1.user.oc1..test-user",
            fingerprint = fingerprint,
            privateKey = keyPair.private,
            host = "identity.eu-zurich-1.oraclecloud.com",
            path = "/20160918/users/ocid1.user.oc1..test-user/apiKeys",
        )

        assertTrue("Oracle SDK diagnostic should report success on HTTP 200", result.success)
        assertEquals(200, result.httpCode)
        assertEquals("oracle-sdk-runtime", result.signerUsed)
        assertEquals("Only one SDK-signed GET should be issued", 1, captured.size)

        val req = captured.first()
        assertEquals("GET", req.method)
        assertEquals(
            "https://identity.eu-zurich-1.oraclecloud.com/20160918/users/ocid1.user.oc1..test-user/apiKeys",
            req.url.toString(),
        )

        val auth = checkNotNull(req.header("Authorization")) { "Missing Authorization header" }
        assertTrue("Authorization should use Signature scheme", auth.startsWith("Signature "))
        assertTrue(
            "Authorization should contain SDK-style headers parameter first",
            auth.startsWith("Signature headers=\""),
        )
        assertTrue(
            "Authorization should contain the API-key keyId",
            auth.contains("keyId=\"ocid1.tenancy.oc1..test-tenancy/ocid1.user.oc1..test-user/$fingerprint\""),
        )
        assertTrue("Authorization should contain algorithm", auth.contains("algorithm=\"rsa-sha256\""))
        assertTrue("Authorization should contain version", auth.contains("version=\"1\""))
        assertTrue("Authorization should contain signature", auth.contains("signature=\""))
        assertTrue(
            "Authorization headers raw value should match signing-string order",
            auth.contains("headers=\"date (request-target) host\""),
        )
    }

    @Test
    fun `SDK diagnostic request uses uploaded fingerprint`() = runBlocking {
        val captured = mutableListOf<okhttp3.Request>()
        val client = mockClient { chain ->
            captured += chain.request()
            okhttp3.Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody(null))
                .build()
        }

        val keyPair = OciRequestSigner.generateKeyPair()
        val fingerprint = OciRequestSigner.md5Fingerprint(keyPair.public as java.security.interfaces.RSAPublicKey)
        OciOracleSdkActivationDiagnostic.run(
            httpClient = client,
            tenancyOcid = "ocid1.tenancy.oc1..test-tenancy",
            userOcid = "ocid1.user.oc1..test-user",
            fingerprint = fingerprint,
            privateKey = keyPair.private,
            host = "identity.eu-zurich-1.oraclecloud.com",
            path = "/20160918/users/ocid1.user.oc1..test-user/apiKeys",
        )

        val auth = checkNotNull(captured.first().header("Authorization")) { "Missing Authorization" }
        assertTrue(
            "keyId must include the uploaded fingerprint",
            auth.contains("/$fingerprint\""),
        )
    }

    @Test
    fun `SDK diagnostic request keyId is tenancy slash user slash fingerprint`() = runBlocking {
        val captured = mutableListOf<okhttp3.Request>()
        val client = mockClient { chain ->
            captured += chain.request()
            okhttp3.Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody(null))
                .build()
        }

        val keyPair = OciRequestSigner.generateKeyPair()
        val fingerprint = OciRequestSigner.md5Fingerprint(keyPair.public as java.security.interfaces.RSAPublicKey)
        OciOracleSdkActivationDiagnostic.run(
            httpClient = client,
            tenancyOcid = "ocid1.tenancy.oc1..test-tenancy",
            userOcid = "ocid1.user.oc1..test-user",
            fingerprint = fingerprint,
            privateKey = keyPair.private,
            host = "identity.eu-zurich-1.oraclecloud.com",
            path = "/20160918/users/ocid1.user.oc1..test-user/apiKeys",
        )

        val auth = checkNotNull(captured.first().header("Authorization")) { "Missing Authorization" }
        val expectedKeyId = "ocid1.tenancy.oc1..test-tenancy/ocid1.user.oc1..test-user/$fingerprint"
        assertTrue("keyId must be tenancy/user/fingerprint", auth.contains("keyId=\"$expectedKeyId\""))
    }

    @Test
    fun `safe diagnostics do not expose full authorization signature or private key`() = runBlocking {
        val client = mockClient { chain ->
            okhttp3.Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("".toResponseBody(null))
                .build()
        }

        val keyPair = OciRequestSigner.generateKeyPair()
        val fingerprint = OciRequestSigner.md5Fingerprint(keyPair.public as java.security.interfaces.RSAPublicKey)
        val result = OciOracleSdkActivationDiagnostic.run(
            httpClient = client,
            tenancyOcid = "ocid1.tenancy.oc1..test-tenancy",
            userOcid = "ocid1.user.oc1..test-user",
            fingerprint = fingerprint,
            privateKey = keyPair.private,
            host = "identity.eu-zurich-1.oraclecloud.com",
            path = "/20160918/users/ocid1.user.oc1..test-user/apiKeys",
        )

        assertFalse("Diagnostic should fail on HTTP 401", result.success)
        assertEquals(401, result.httpCode)

        val allValues = result.diagnostics.values.joinToString("\n")
        assertFalse("Diagnostics must not contain full Authorization header", allValues.contains("Signature "))
        assertFalse("Diagnostics must not contain private key PEM", allValues.contains("PRIVATE KEY"))
        assertFalse("Diagnostics must not contain full OCIDs", allValues.contains("ocid1.tenancy.oc1..test-tenancy"))
        assertFalse("Diagnostics must not contain full user OCID", allValues.contains("ocid1.user.oc1..test-user"))

        // Verify hashes are present instead of raw values
        assertTrue("zeroVpnAuthorizationHeaderSha256 should be present", result.diagnostics.containsKey("zeroVpnAuthorizationHeaderSha256"))
        assertTrue("oracleSdkAuthorizationHeaderSha256 should be present", result.diagnostics.containsKey("oracleSdkAuthorizationHeaderSha256"))
        assertTrue("zeroVpnSignatureValueSha256 should be present", result.diagnostics.containsKey("zeroVpnSignatureValueSha256"))
        assertTrue("oracleSdkSignatureValueSha256 should be present", result.diagnostics.containsKey("oracleSdkSignatureValueSha256"))
    }

    @Test
    fun `SDK diagnostic blocking work is dispatched to IO dispatcher`() = runBlocking {
        val captured = mutableListOf<okhttp3.Request>()
        val recording = RecordingDispatcher()
        val client = mockClient { chain ->
            captured += chain.request()
            okhttp3.Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody(null))
                .build()
        }

        val keyPair = OciRequestSigner.generateKeyPair()
        val fingerprint = OciRequestSigner.md5Fingerprint(keyPair.public as java.security.interfaces.RSAPublicKey)
        OciOracleSdkActivationDiagnostic.run(
            httpClient = client,
            tenancyOcid = "ocid1.tenancy.oc1..test-tenancy",
            userOcid = "ocid1.user.oc1..test-user",
            fingerprint = fingerprint,
            privateKey = keyPair.private,
            host = "identity.eu-zurich-1.oraclecloud.com",
            path = "/20160918/users/ocid1.user.oc1..test-user/apiKeys",
            ioDispatcher = recording,
        )

        assertTrue("SDK diagnostic should dispatch blocking work to ioDispatcher", recording.dispatched)
        assertEquals(1, captured.size)
    }

    @Test
    fun `SDK diagnostic wrapper does not throw NetworkOnMainThreadException`() = runBlocking {
        val capturedThread = mutableListOf<Thread>()
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor(StrictModeNetworkInterceptor())
            .addInterceptor { chain ->
                capturedThread += Thread.currentThread()
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("".toResponseBody(null))
                    .build()
            }
            .build()

        val mainContext = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
        try {
            val keyPair = OciRequestSigner.generateKeyPair()
            val fingerprint = OciRequestSigner.md5Fingerprint(keyPair.public as java.security.interfaces.RSAPublicKey)
            val result = runBlocking(mainContext) {
                OciOracleSdkActivationDiagnostic.run(
                    httpClient = client,
                    tenancyOcid = "ocid1.tenancy.oc1..test-tenancy",
                    userOcid = "ocid1.user.oc1..test-user",
                    fingerprint = fingerprint,
                    privateKey = keyPair.private,
                    host = "identity.eu-zurich-1.oraclecloud.com",
                    path = "/20160918/users/ocid1.user.oc1..test-user/apiKeys",
                )
            }
            assertTrue("Diagnostic should succeed when HTTP call runs off the main thread", result.success)
            assertEquals(200, result.httpCode)
            assertTrue("HTTP call must not run on the main thread", capturedThread.isNotEmpty())
            for (thread in capturedThread) {
                assertFalse(
                    "HTTP call ran on a thread named like the main thread: ${thread.name}",
                    thread.name.contains("main", ignoreCase = true),
                )
            }
        } finally {
            mainContext.close()
        }
    }

    @Test
    fun `Dev Mode uploadApiKey uses Oracle SDK signer and stops before resource creation`() = runBlocking {
        val captured = mutableListOf<okhttp3.Request>()
        val (provisioner, auth) = buildAuthResult(isDevMode = true)

        provisioner.httpClient = mockClient { chain ->
            captured += chain.request()
            val path = chain.request().url.encodedPath
            val method = chain.request().method
            val body = when {
                method == "POST" && path.endsWith("/apiKeys") -> {
                    "{\"fingerprint\":\"${auth.fingerprint}\"}"
                }
                else -> ""
            }
            val code = when {
                method == "POST" && path.endsWith("/apiKeys") -> 200
                method == "GET" && path.endsWith("/apiKeys") -> 200
                else -> 404
            }
            okhttp3.Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Not Found")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }

        val ex = assertThrows(OciProvisioner.OracleSdkActivationSucceededException::class.java) {
            runBlocking { provisioner.uploadApiKey(auth, "eu-zurich-1") }
        }
        assertNotNull(ex)
        assertTrue(
            "Success message should mention Oracle SDK signer",
            ex.message!!.contains("Oracle SDK signer"),
        )

        // Only upload POST and SDK diagnostic GET should have been issued.
        val apiKeyRequests = captured.filter { it.url.encodedPath.endsWith("/apiKeys") }
        assertEquals("Only upload POST + SDK diagnostic GET should be issued", 2, apiKeyRequests.size)
        assertTrue("First request should be upload POST", apiKeyRequests[0].method == "POST")
        assertTrue("Second request should be SDK diagnostic GET", apiKeyRequests[1].method == "GET")

        // No cloud resource creation requests.
        val resourcePaths = listOf("/20160918/vcns", "/20160918/securityLists", "/20160918/subnets", "/20160918/internetGateways", "/20160918/instances")
        for (resourcePath in resourcePaths) {
            assertFalse(
                "Must not create $resourcePath before validation result",
                captured.any { it.url.encodedPath.contains(resourcePath) },
            )
        }
    }

    @Test
    fun `Normal mode uploadApiKey still uses existing signer and returns fingerprint`() = runBlocking {
        val captured = mutableListOf<okhttp3.Request>()
        val (provisioner, auth) = buildAuthResult(isDevMode = false)

        provisioner.httpClient = mockClient { chain ->
            captured += chain.request()
            val path = chain.request().url.encodedPath
            val method = chain.request().method
            val body = when {
                method == "POST" && path.endsWith("/apiKeys") -> {
                    "{\"fingerprint\":\"${auth.fingerprint}\"}"
                }
                else -> ""
            }
            val code = when {
                method == "POST" && path.endsWith("/apiKeys") -> 200
                method == "GET" && path.endsWith("/apiKeys") -> 200
                else -> 404
            }
            okhttp3.Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Not Found")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }

        val fingerprint = provisioner.uploadApiKey(auth, "eu-zurich-1")
        assertEquals(auth.fingerprint, fingerprint)

        // Normal mode should use the existing retry gate (one GET here because first succeeds).
        val apiKeyRequests = captured.filter { it.url.encodedPath.endsWith("/apiKeys") }
        assertTrue("Upload POST should be present", apiKeyRequests.any { it.method == "POST" })
        assertTrue("Existing signer activation GET should be present", apiKeyRequests.any { it.method == "GET" })
    }

    @Test
    fun `SDK diagnostic on 401 reports failure and does not create resources`() = runBlocking {
        val captured = mutableListOf<okhttp3.Request>()
        val (provisioner, auth) = buildAuthResult(isDevMode = true)

        provisioner.httpClient = mockClient { chain ->
            captured += chain.request()
            val path = chain.request().url.encodedPath
            val method = chain.request().method
            val body = when {
                method == "POST" && path.endsWith("/apiKeys") -> {
                    "{\"fingerprint\":\"${auth.fingerprint}\"}"
                }
                else -> ""
            }
            val code = when {
                method == "POST" && path.endsWith("/apiKeys") -> 200
                method == "GET" && path.endsWith("/apiKeys") -> 401
                else -> 404
            }
            okhttp3.Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Unauthorized")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }

        val ex = assertThrows(OciProvisioner.ApiKeyActivationFailedException::class.java) {
            runBlocking { provisioner.uploadApiKey(auth, "eu-zurich-1") }
        }
        assertEquals(1, ex.attemptCount)
        assertTrue(ex.message!!.contains("API-key activation failed"))

        val resourcePaths = listOf("/20160918/vcns", "/20160918/securityLists", "/20160918/subnets", "/20160918/internetGateways", "/20160918/instances")
        for (resourcePath in resourcePaths) {
            assertFalse(
                "Must not create $resourcePath after failed SDK diagnostic",
                captured.any { it.url.encodedPath.contains(resourcePath) },
            )
        }
    }
}
