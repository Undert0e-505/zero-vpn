package com.zerovpn.app.chat.retry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class LaunchFailureDiagnosticsTest {
    @Test fun captureRedactsSecretsFromMessagesAndStackTrace() {
        val bearerSecret = "bearer-secret-that-must-not-survive"
        val privateKeySecret = "private-key-secret-that-must-not-survive"
        val error = IllegalStateException(
            """Authorization: Bearer $bearerSecret
-----BEGIN PRIVATE KEY-----
$privateKeySecret
-----END PRIVATE KEY-----
private_key=another-secret-that-must-not-survive""",
        )

        val diagnostics = LaunchFailureDiagnostics.capture(
            error = error,
            progress = LaunchProgress.TRANSMISSION_STARTED,
            failingOperation = "transmit-launch-request",
            requestConstructionCompleted = true,
            requestSigningCompleted = true,
            transmissionStarted = true,
            responseHeadersReceived = false,
            retryToken = "retry-token-1234567890",
            sessionId = "session-id-1234567890",
        )
        val block = diagnostics.fullDiagnosticBlock()

        listOf(bearerSecret, privateKeySecret, "another-secret-that-must-not-survive").forEach {
            assertFalse(block.contains(it))
        }
        assertTrue(block.contains("[REDACTED]"))
        assertEquals("transmission-failure", diagnostics.safeCategory())
        assertEquals("retry-to...567890", diagnostics.retryTokenAbbreviated)
        assertEquals("session-...567890", diagnostics.sessionIdAbbreviated)
    }

    @Test fun diagnosticLogRoundTripPreservesStructuredRedactedDeveloperRecord() {
        val prefs = FakeSharedPreferences()
        val now = Instant.parse("2026-07-17T12:00:00Z")
        val diagnostics = LaunchFailureDiagnostics.capture(
            error = IllegalStateException(
                "outer launch failure",
                IOException("Bearer root-cause-secret"),
            ),
            progress = LaunchProgress.PREPARING_REQUEST,
            failingOperation = "availability-domain-lookup",
            requestConstructionCompleted = false,
            requestSigningCompleted = false,
            transmissionStarted = false,
            responseHeadersReceived = false,
            retryToken = "retry-token-for-round-trip",
            sessionId = "session-for-round-trip",
        )
        CapacityRetryDiagnosticLog(prefs, Clock.fixed(now, ZoneOffset.UTC))
            .appendFailure("session-for-round-trip", diagnostics)

        val restored = CapacityRetryDiagnosticLog(
            prefs,
            Clock.fixed(now.plusSeconds(1), ZoneOffset.UTC),
        ).entries("session-for-round-trip").single().failureDiagnostics

        assertNotNull(restored)
        assertEquals("IOException", restored!!.rootCauseClass)
        assertEquals("availability-domain-lookup", restored.failingOperation)
        assertEquals(LaunchProgress.PREPARING_REQUEST.name, restored.progress)
        assertFalse(restored.fullDiagnosticBlock().contains("root-cause-secret"))
        assertTrue(restored.safeStackTrace.isNotBlank())
    }
}
