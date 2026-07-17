package com.zerovpn.app.chat.retry

import com.zerovpn.app.oci.OciRequestSigner
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.KeyPairGenerator

class OciBackgroundLauncherTest {
    @Test fun launcherWithPendingFourGbMakesOneFourGbLaunchAttempt() = runBlocking {
        val pair = OciRequestSigner.generateKeyPair()
        val transport = FakeTransport(
            OciHttpResponse(200, """[{"name":"AD-1"}]"""),
            OciHttpResponse(200, """[{"id":"ocid1.image.oc1..ubuntu"}]"""),
            OciHttpResponse(500, """{"code":"InternalError","message":"Out of host capacity."}"""),
        )
        val result = OciBackgroundLauncher(transport, identityHostOverride = "identity.test", iaasHostOverride = "iaas.test")
            .launchA1Instance(credentials(pair.private), params(), pendingMemoryGb = 4, retryToken = "four-gb-token")

        assertTrue(result is BackgroundLaunchResult.CapacityMiss)
        val posts = transport.requests.filter { it.method == "POST" }
        assertEquals(1, posts.size)
        assertEquals("four-gb-token", posts.single().header("opc-retry-token"))
        assertEquals(4, posts.single().launchMemoryGb())
    }

    @Test fun launcherReturnsSuccessAndInstanceOcid() = runBlocking {
        val pair = OciRequestSigner.generateKeyPair()
        val transport = FakeTransport(
            OciHttpResponse(200, """[{"name":"AD-1"}]"""),
            OciHttpResponse(200, """[{"id":"ocid1.image.oc1..ubuntu"}]"""),
            OciHttpResponse(200, """{"id":"ocid1.instance.oc1..vm","displayName":"zerovpn-exit-01"}"""),
        )
        val result = OciBackgroundLauncher(transport, identityHostOverride = "identity.test", iaasHostOverride = "iaas.test")
            .launchA1Instance(credentials(pair.private), params(), pendingMemoryGb = 6, retryToken = "six-gb-token")

        assertEquals("ocid1.instance.oc1..vm", (result as BackgroundLaunchResult.Success).instanceOcid)
        assertEquals(1, transport.requests.count { it.method == "POST" })
        assertEquals(6, transport.requests.single { it.method == "POST" }.launchMemoryGb())
    }

    @Test fun launcherReturnsRateLimitedAndHonoursIntegerRetryAfter() = runBlocking {
        val pair = OciRequestSigner.generateKeyPair()
        val transport = FakeTransport(
            OciHttpResponse(200, """[{"name":"AD-1"}]"""),
            OciHttpResponse(200, """[{"id":"ocid1.image.oc1..ubuntu"}]"""),
            OciHttpResponse(429, """{"code":"TooManyRequests"}""", mapOf("retry-after" to "1800")),
        )

        val result = OciBackgroundLauncher(transport, identityHostOverride = "identity.test", iaasHostOverride = "iaas.test")
            .launchA1Instance(credentials(pair.private), params(), pendingMemoryGb = 4, retryToken = "same-four-gb-token")

        assertEquals(1, transport.requests.count { it.method == "POST" })
        assertEquals(1_800L, (result as BackgroundLaunchResult.RateLimited).retryAfterSeconds)
        assertEquals(4, transport.requests.single { it.method == "POST" }.launchMemoryGb())
    }

    @Test fun availabilityDomainLookupFailureIsLocalAndDoesNotSendLaunchRequest() = runBlocking {
        val pair = OciRequestSigner.generateKeyPair()
        val transport = FakeTransport(
            OciHttpResponse(403, """{"code":"NotAuthorizedOrNotFound"}"""),
        )

        val result = launcher(transport).launchA1Instance(
            credentials(pair.private),
            params(),
            pendingMemoryGb = 6,
            retryToken = "local-failure-token",
            sessionId = "session:local",
        )

        val failure = result as BackgroundLaunchResult.LocalPreparationFailure
        assertEquals(LaunchProgress.PREPARING_REQUEST.name, failure.progress)
        assertEquals("local-preparation-error", failure.category)
        assertFalse(failure.diagnostics.transmissionStarted == true)
        assertEquals(0, transport.requests.count { it.method == "POST" })
    }

    @Test fun signingFailureIsLocalAndReportsSigningBoundary() = runBlocking {
        val invalidSigningKey = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
            .generateKeyPair()
            .private
        val transport = FakeTransport()

        val result = launcher(transport).launchA1Instance(
            credentials(invalidSigningKey),
            params(),
            pendingMemoryGb = 6,
            retryToken = "signing-failure-token",
            sessionId = "session:signing",
        )

        val failure = result as BackgroundLaunchResult.LocalPreparationFailure
        assertEquals(LaunchProgress.SIGNING_REQUEST.name, failure.progress)
        assertEquals("signing-error", failure.category)
        assertFalse(failure.diagnostics.requestSigningCompleted == true)
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun malformedAvailabilityDomainJsonIsLocalJsonFailure() = runBlocking {
        val pair = OciRequestSigner.generateKeyPair()
        val transport = FakeTransport(OciHttpResponse(200, "not-json"))

        val result = launcher(transport).launchA1Instance(
            credentials(pair.private),
            params(),
            pendingMemoryGb = 6,
            retryToken = "json-failure-token",
            sessionId = "session:json",
        )

        val failure = result as BackgroundLaunchResult.LocalPreparationFailure
        assertEquals("json-construction-error", failure.category)
        assertTrue(failure.exceptionClass.contains("JSONException"))
        assertFalse(failure.diagnostics.transmissionStarted == true)
        assertEquals(0, transport.requests.count { it.method == "POST" })
    }

    @Test fun launchTransportExceptionIsAmbiguousTransmissionFailure() = runBlocking {
        val pair = OciRequestSigner.generateKeyPair()
        val secret = "do-not-record-this-token"
        val transport = FailingPostTransport(
            responses = listOf(
                OciHttpResponse(200, """[{"name":"AD-1"}]"""),
                OciHttpResponse(200, """[{"id":"ocid1.image.oc1..ubuntu"}]"""),
            ),
            error = IOException("socket closed Authorization: Bearer $secret"),
        )

        val result = launcher(transport).launchA1Instance(
            credentials(pair.private),
            params(),
            pendingMemoryGb = 4,
            retryToken = "transmission-failure-token",
            sessionId = "session:transmission",
        )

        val failure = result as BackgroundLaunchResult.TransmissionFailure
        assertEquals("transmission-failure", failure.category)
        assertEquals(LaunchProgress.TRANSMISSION_STARTED.name, failure.diagnostics.progress)
        assertEquals(true, failure.diagnostics.requestConstructionCompleted)
        assertEquals(true, failure.diagnostics.requestSigningCompleted)
        assertEquals(true, failure.diagnostics.transmissionStarted)
        assertEquals(false, failure.diagnostics.responseHeadersReceived)
        assertNull(failure.redactedRequestId)
        assertFalse(failure.diagnostics.fullDiagnosticBlock().contains(secret))
        assertEquals(1, transport.requests.count { it.method == "POST" })
    }

    @Test fun genericFiveHundredResponseIsAmbiguousAndNotCapacity() = runBlocking {
        val pair = OciRequestSigner.generateKeyPair()
        val transport = FakeTransport(
            OciHttpResponse(200, """[{"name":"AD-1"}]"""),
            OciHttpResponse(200, """[{"id":"ocid1.image.oc1..ubuntu"}]"""),
            OciHttpResponse(
                500,
                """{"code":"InternalError","message":"Unexpected service failure"}""",
                mapOf("opc-request-id" to "abcdefghijklmnop"),
            ),
        )

        val result = launcher(transport).launchA1Instance(
            credentials(pair.private),
            params(),
            pendingMemoryGb = 6,
            retryToken = "ambiguous-response-token",
        )

        val failure = result as BackgroundLaunchResult.AmbiguousFailure
        assertEquals(500, failure.status)
        assertEquals("ambiguous-oci", failure.category)
        assertEquals("efghijklmnop", failure.requestId)
    }

    private fun launcher(transport: OciHttpTransport) = OciBackgroundLauncher(
        transport,
        identityHostOverride = "identity.test",
        iaasHostOverride = "iaas.test",
    )

    private fun credentials(privateKey: java.security.PrivateKey) = BackgroundLaunchCredentials(
        securityToken = "test-security-token",
        privateKey = privateKey,
        tenancyOcid = "ocid1.tenancy.oc1..tenancy",
        userOcid = "ocid1.user.oc1..user",
        fingerprint = "aa:bb:cc",
    )

    private fun params() = BackgroundLaunchParams(
        compartmentOcid = "ocid1.tenancy.oc1..tenancy",
        region = "uk-london-1",
        subnetId = "ocid1.subnet.oc1..subnet",
        sshPublicKey = "ssh-rsa AAAA zerovpn-android",
    )

    private fun Request.launchMemoryGb(): Int {
        val buffer = Buffer()
        requireNotNull(body).writeTo(buffer)
        return JSONObject(buffer.readUtf8()).getJSONObject("shapeConfig").getInt("memoryInGBs")
    }

    private class FakeTransport(vararg responses: OciHttpResponse) : OciHttpTransport {
        private val queue = ArrayDeque(responses.toList())
        val requests = mutableListOf<Request>()
        override suspend fun execute(request: Request): OciHttpResponse {
            requests += request
            return queue.removeFirst()
        }
    }

    private class FailingPostTransport(
        responses: List<OciHttpResponse>,
        private val error: IOException,
    ) : OciHttpTransport {
        private val queue = ArrayDeque(responses)
        val requests = mutableListOf<Request>()

        override suspend fun execute(request: Request): OciHttpResponse {
            requests += request
            if (queue.isNotEmpty()) return queue.removeFirst()
            throw error
        }
    }
}

