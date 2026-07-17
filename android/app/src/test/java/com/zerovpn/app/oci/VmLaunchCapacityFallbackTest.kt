package com.zerovpn.app.oci

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VmLaunchCapacityFallbackTest {
    @Test
    fun `isOutOfHostCapacity matches real OCI response`() {
        assertTrue(isOutOfHostCapacity("""{"code":"InternalError","message":"Out of host capacity."}"""))
    }

    @Test
    fun `isOutOfHostCapacity matches case variation`() {
        assertTrue(isOutOfHostCapacity("""{"code":"internalerror","message":"OUT OF HOST CAPACITY"}"""))
    }

    @Test
    fun `isOutOfHostCapacity matches with trailing period`() {
        assertTrue(isOutOfHostCapacity("""{"code":"InternalError","message":"Out of host capacity."}"""))
    }

    @Test
    fun `isOutOfHostCapacity rejects generic InternalError without capacity message`() {
        assertFalse(isOutOfHostCapacity("""{"code":"InternalError","message":"Something else broke."}"""))
    }

    @Test
    fun `isOutOfHostCapacity rejects generic 500 without InternalError`() {
        assertFalse(isOutOfHostCapacity("""{"code":"QuotaExceeded","message":"Out of host capacity."}"""))
    }

    @Test
    fun `isOutOfHostCapacity rejects a non-exact capacity message`() {
        assertFalse(isOutOfHostCapacity("""{"code":"InternalError","message":"Not out of host capacity anymore."}"""))
    }

    @Test
    fun `classifyLaunchResponse returns SUCCESS for 200`() {
        assertEquals(LaunchAttemptResult.SUCCESS, classifyLaunchResponse(200, "{}"))
    }

    @Test
    fun `classifyLaunchResponse returns FAIL_CAPACITY for 500 with capacity error`() {
        assertEquals(
            LaunchAttemptResult.FAIL_CAPACITY,
            classifyLaunchResponse(500, """{"code":"InternalError","message":"Out of host capacity."}"""),
        )
    }

    @Test
    fun `classifyLaunchResponse returns FAIL_OTHER for 500 without capacity error`() {
        assertEquals(
            LaunchAttemptResult.FAIL_OTHER,
            classifyLaunchResponse(500, """{"code":"InternalError","message":"Something else broke."}"""),
        )
    }

    @Test
    fun `classifyLaunchResponse returns FAIL_OTHER for 401`() {
        assertEquals(LaunchAttemptResult.FAIL_OTHER, classifyLaunchResponse(401, "{}"))
    }

    @Test
    fun `classifyLaunchResponse returns FAIL_OTHER for 403`() {
        assertEquals(LaunchAttemptResult.FAIL_OTHER, classifyLaunchResponse(403, "{}"))
    }

    @Test
    fun `classifyLaunchResponse returns RATE_LIMITED for 429`() {
        assertEquals(LaunchAttemptResult.RATE_LIMITED, classifyLaunchResponse(429, "{}"))
    }

    @Test
    fun `classifyLaunchResponse returns FAIL_OTHER for 400`() {
        assertEquals(LaunchAttemptResult.FAIL_OTHER, classifyLaunchResponse(400, "{}"))
    }

    @Test
    fun `classifyFinalFailure returns FAIL_CAPACITY for 500 with capacity error`() {
        assertEquals(
            LaunchAttemptResult.FAIL_CAPACITY,
            classifyFinalFailure(500, """{"code":"InternalError","message":"Out of host capacity."}"""),
        )
    }

    @Test
    fun `classifyFinalFailure returns FAIL_OTHER for 500 without capacity error`() {
        assertEquals(
            LaunchAttemptResult.FAIL_OTHER,
            classifyFinalFailure(500, """{"code":"InternalError","message":"Something else broke."}"""),
        )
    }

    @Test
    fun `classifyFinalFailure returns FAIL_OTHER for 401`() {
        assertEquals(LaunchAttemptResult.FAIL_OTHER, classifyFinalFailure(401, "{}"))
    }

    @Test
    fun `classifyFinalFailure returns RATE_LIMITED for 429`() {
        assertEquals(LaunchAttemptResult.RATE_LIMITED, classifyFinalFailure(429, "{}"))
    }

    @Test
    fun `single six GB private chat launch throws capacity without four GB request`() = runBlocking {
        val requestedMemory = mutableListOf<Int>()

        val failure = runCatching {
            executeSinglePrivateChatLaunchAttempt(6) { memoryGb ->
                requestedMemory += memoryGb
                VmLaunchHttpResponse(
                    code = 500,
                    body = """{"code":"InternalError","message":"Out of host capacity."}""",
                )
            }
        }.exceptionOrNull() as VmLaunchFailureException

        assertEquals(listOf(6), requestedMemory)
        assertTrue(failure.failure is VmLaunchFailure.OutOfHostCapacity)
    }

    @Test
    fun `single private chat launch maps 429 and Retry-After without fallback`() = runBlocking {
        var requestCount = 0

        val failure = runCatching {
            executeSinglePrivateChatLaunchAttempt(6) {
                requestCount += 1
                VmLaunchHttpResponse(code = 429, body = "{}", retryAfterSeconds = 1_200)
            }
        }.exceptionOrNull() as VmLaunchFailureException
        val rateLimited = failure.failure as VmLaunchFailure.RateLimited

        assertEquals(1, requestCount)
        assertEquals(1_200L, rateLimited.retryAfterSeconds)
        assertEquals(OCI_RATE_LIMIT_MESSAGE, rateLimited.message)
    }

    @Test
    fun `Retry-After parser accepts integer seconds only`() {
        assertEquals(900L, parseRetryAfterSeconds(" 900 "))
        assertNull(parseRetryAfterSeconds("Wed, 16 Jul 2026 12:00:00 GMT"))
        assertNull(parseRetryAfterSeconds(null))
    }
}
