package com.zerovpn.app.oci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `classifyLaunchResponse returns SUCCESS for 200`() {
        assertEquals(LaunchAttemptResult.SUCCESS, classifyLaunchResponse(200, "{}"))
    }

    @Test
    fun `classifyLaunchResponse returns RETRY_4GB for 500 with capacity error`() {
        assertEquals(
            LaunchAttemptResult.RETRY_4GB,
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
    fun `classifyLaunchResponse returns FAIL_OTHER for 429`() {
        assertEquals(LaunchAttemptResult.FAIL_OTHER, classifyLaunchResponse(429, "{}"))
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
}