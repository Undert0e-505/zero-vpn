package com.zerovpn.app.chat.retry

import com.zerovpn.app.oci.OciEndpoints
import com.zerovpn.app.oci.OciRequestSigner
import com.zerovpn.app.oci.classifyLaunchResponse
import com.zerovpn.app.oci.LaunchAttemptResult
import com.zerovpn.app.oci.parseRetryAfterSeconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.PrivateKey

/**
 * Durable API-key credentials for the background retry worker.
 * The worker always uses API-key auth (never the short-lived browser security token).
 */
internal data class BackgroundLaunchCredentials(
    val tenancyOcid: String,
    val userOcid: String,
    val fingerprint: String,
    val privateKey: PrivateKey,
    val region: String,
    val publicKeySha256: String? = null,
)

internal data class BackgroundLaunchParams(
    val compartmentOcid: String,
    val region: String,
    val subnetId: String,
    val sshPublicKey: String,
    val displayName: String = "zerovpn-exit-01",
)

internal interface BackgroundLaunchExecutor {
    suspend fun launchA1Instance(
        credentials: BackgroundLaunchCredentials,
        params: BackgroundLaunchParams,
        pendingMemoryGb: Int,
        retryToken: String,
        sessionId: String = "",
    ): BackgroundLaunchResult
}

internal sealed class BackgroundLaunchResult {
    data class Success(val instanceOcid: String, val displayName: String?, val availabilityDomain: String) : BackgroundLaunchResult()
    data class CapacityMiss(val status: Int, val requestId: String?) : BackgroundLaunchResult()
    data class RateLimited(val retryAfterSeconds: Long?) : BackgroundLaunchResult()
    data class TerminalFailure(val status: Int, val code: String?, val category: String, val requestId: String?) : BackgroundLaunchResult()
    data class AmbiguousFailure(
        val status: Int,
        val category: String,
        val requestId: String?,
        val diagnostics: LaunchFailureDiagnostics? = null,
    ) : BackgroundLaunchResult()

    data class LocalPreparationFailure(
        val progress: String,
        val category: String,
        val safeMessage: String,
        val exceptionClass: String,
        val diagnostics: LaunchFailureDiagnostics,
    ) : BackgroundLaunchResult()

    data class TransmissionFailure(
        val category: String,
        val safeMessage: String,
        val exceptionClass: String,
        val rootCauseClass: String?,
        val redactedRequestId: String?,
        val diagnostics: LaunchFailureDiagnostics,
    ) : BackgroundLaunchResult()

    /**
     * OCI returned HTTP 401 — the signed request was transmitted but OCI rejected the authentication.
     * This is NOT a local preparation failure; the request was sent and a response was received.
     */
    data class AuthenticationFailure(
        val category: String,
        val safeMessage: String,
        val httpStatus: Int,
        val redactedRequestId: String?,
        val diagnostics: LaunchFailureDiagnostics,
    ) : BackgroundLaunchResult()
}

internal fun BackgroundLaunchResult.failureDiagnosticsOrNull(): LaunchFailureDiagnostics? = when (this) {
    is BackgroundLaunchResult.LocalPreparationFailure -> diagnostics
    is BackgroundLaunchResult.TransmissionFailure -> diagnostics
    is BackgroundLaunchResult.AmbiguousFailure -> diagnostics
    is BackgroundLaunchResult.AuthenticationFailure -> diagnostics
    else -> null
}

internal data class OciHttpResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
) {
    val isSuccessful: Boolean get() = code in 200..299

    fun header(name: String): String? = headers.entries
        .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
        ?.value
}

internal interface OciHttpTransport {
    suspend fun execute(request: Request): OciHttpResponse
}

private class OciTransportException(
    val responseHeadersReceived: Boolean,
    val responseCode: Int?,
    val redactedRequestId: String?,
    cause: Exception,
) : Exception("OCI transport failed after execution started.", cause)

internal class OkHttpOciTransport(
    private val client: OkHttpClient = OkHttpClient(),
) : OciHttpTransport {
    override suspend fun execute(request: Request): OciHttpResponse = withContext(Dispatchers.IO) {
        var responseCode: Int? = null
        var requestId: String? = null
        try {
            client.newCall(request).execute().use { response ->
                responseCode = response.code
                requestId = response.header("opc-request-id")?.takeLast(12)
                OciHttpResponse(
                    code = response.code,
                    body = response.body?.string() ?: "",
                    headers = response.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw OciTransportException(
                responseHeadersReceived = responseCode != null,
                responseCode = responseCode,
                redactedRequestId = requestId,
                cause = error,
            )
        }
    }
}

private data class LaunchProgressTracker(
    var progress: LaunchProgress = LaunchProgress.PREPARING_REQUEST,
    var failingOperation: String = "validate-launch-input",
    var requestConstructionCompleted: Boolean = false,
    var requestSigningCompleted: Boolean = false,
    var transmissionStarted: Boolean = false,
    var responseHeadersReceived: Boolean = false,
    var responseCode: Int? = null,
    var redactedRequestId: String? = null,
) {
    fun capture(
        error: Throwable,
        retryToken: String,
        sessionId: String,
    ): LaunchFailureDiagnostics = LaunchFailureDiagnostics.capture(
        error = error,
        progress = progress,
        failingOperation = failingOperation,
        requestConstructionCompleted = requestConstructionCompleted,
        requestSigningCompleted = requestSigningCompleted,
        transmissionStarted = transmissionStarted,
        responseHeadersReceived = responseHeadersReceived,
        redactedRequestId = redactedRequestId,
        retryToken = retryToken,
        sessionId = sessionId,
    )
}

/**
 * Thrown when OCI returns HTTP 401 on a signed request.
 * The background launcher catches this and returns [BackgroundLaunchResult.AuthenticationFailure].
 */
internal class OciAuthenticationException(val response: OciHttpResponse) :
    Exception("OCI returned HTTP 401: authentication rejected.")

internal class OciBackgroundLauncher(
    private val transport: OciHttpTransport = OkHttpOciTransport(),
    private val identityHostOverride: String? = null,
    private val iaasHostOverride: String? = null,
    private val scheme: String = "https",
) : BackgroundLaunchExecutor {
    private val jsonMedia = "application/json".toMediaType()

    override suspend fun launchA1Instance(
        credentials: BackgroundLaunchCredentials,
        params: BackgroundLaunchParams,
        pendingMemoryGb: Int,
        retryToken: String,
        sessionId: String,
    ): BackgroundLaunchResult {
        val tracker = LaunchProgressTracker()
        try {
            require(pendingMemoryGb == 4 || pendingMemoryGb == 6) {
                "Private Chat memory must be 4 GB or 6 GB."
            }
            val identityHost = identityHostOverride ?: OciEndpoints.identityHost(params.region)
            val iaasHost = iaasHostOverride ?: OciEndpoints.iaasHost(params.region)
            tracker.failingOperation = "availability-domain-lookup"
            val adName = getArray(
                credentials,
                identityHost,
                "/20160918/availabilityDomains?compartmentId=${encode(params.compartmentOcid)}",
                tracker,
                "availability-domain-lookup",
            ).getJSONObject(0).getString("name")
            val imageId = latestUbuntuImage(credentials, iaasHost, params.compartmentOcid, tracker)
                ?: throw IllegalStateException("No supported Ubuntu A1 image was returned by OCI.")

            tracker.progress = LaunchProgress.PREPARING_REQUEST
            tracker.failingOperation = "construct-launch-request-body"
            val launchBody = launchBody(params, adName, imageId, pendingMemoryGb)
            val response = post(
                credentials,
                iaasHost,
                "/20160918/instances",
                launchBody,
                retryToken,
                tracker,
            )
            tracker.failingOperation = "classify-launch-response"
            return when (classifyLaunchResponse(response.code, response.body)) {
                LaunchAttemptResult.SUCCESS -> {
                    tracker.failingOperation = "parse-successful-launch-response"
                    success(response.body, adName)
                }
                LaunchAttemptResult.FAIL_CAPACITY -> BackgroundLaunchResult.CapacityMiss(
                    status = response.code,
                    requestId = response.redactedRequestId(),
                )
                LaunchAttemptResult.RATE_LIMITED -> BackgroundLaunchResult.RateLimited(
                    retryAfterSeconds = parseRetryAfterSeconds(response.header("Retry-After")),
                )
                LaunchAttemptResult.FAIL_OTHER -> classifyOther(response)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (authFailure: OciAuthenticationException) {
            // HTTP 401 — a transmitted auth failure, NOT a local preparation failure.
            val diagnostics = tracker.capture(authFailure, retryToken, sessionId)
            return BackgroundLaunchResult.AuthenticationFailure(
                category = "oci-authentication-failed",
                safeMessage = "OCI rejected the signed request with HTTP 401.",
                httpStatus = 401,
                redactedRequestId = diagnostics.redactedRequestId,
                diagnostics = diagnostics,
            )
        } catch (error: Exception) {
            val diagnostics = tracker.capture(error, retryToken, sessionId)
            return when {
                tracker.transmissionStarted && !tracker.responseHeadersReceived ->
                    BackgroundLaunchResult.TransmissionFailure(
                        category = diagnostics.safeCategory(),
                        safeMessage = diagnostics.safeExceptionMessage,
                        exceptionClass = diagnostics.exceptionClass,
                        rootCauseClass = diagnostics.rootCauseClass,
                        redactedRequestId = diagnostics.redactedRequestId,
                        diagnostics = diagnostics,
                    )
                tracker.transmissionStarted -> BackgroundLaunchResult.AmbiguousFailure(
                    status = tracker.responseCode ?: 0,
                    category = diagnostics.safeCategory(),
                    requestId = diagnostics.redactedRequestId,
                    diagnostics = diagnostics,
                )
                else -> BackgroundLaunchResult.LocalPreparationFailure(
                    progress = diagnostics.progress,
                    category = diagnostics.safeCategory(),
                    safeMessage = diagnostics.safeExceptionMessage,
                    exceptionClass = diagnostics.exceptionClass,
                    diagnostics = diagnostics,
                )
            }
        }
    }

    private suspend fun latestUbuntuImage(
        credentials: BackgroundLaunchCredentials,
        iaasHost: String,
        compartmentOcid: String,
        tracker: LaunchProgressTracker,
    ): String? {
        val base = "/20160918/images?compartmentId=${encode(compartmentOcid)}" +
            "&operatingSystem=Canonical+Ubuntu&shape=VM.Standard.A1.Flex&sortBy=TIMECREATED&sortOrder=DESC"
        val image22 = getArray(credentials, iaasHost, "$base&operatingSystemVersion=22.04", tracker, "ubuntu-22.04-image-lookup")
        if (image22.length() > 0) return image22.getJSONObject(0).getString("id")
        val image24 = getArray(credentials, iaasHost, "$base&operatingSystemVersion=24.04", tracker, "ubuntu-24.04-image-lookup")
        return if (image24.length() > 0) image24.getJSONObject(0).getString("id") else null
    }

    private fun launchBody(params: BackgroundLaunchParams, availabilityDomain: String, imageId: String, memoryGb: Int): String = JSONObject()
        .put("availabilityDomain", availabilityDomain)
        .put("compartmentId", params.compartmentOcid)
        .put("displayName", params.displayName)
        .put("shape", "VM.Standard.A1.Flex")
        .put("subnetId", params.subnetId)
        .put("sourceDetails", JSONObject().put("imageId", imageId).put("bootVolumeSizeInGBs", 50).put("sourceType", "image"))
        .put("createVnicDetails", JSONObject().put("subnetId", params.subnetId).put("assignPublicIp", true))
        .put("metadata", JSONObject().put("ssh_authorized_keys", params.sshPublicKey))
        .put("shapeConfig", JSONObject().put("ocpus", 1).put("memoryInGBs", memoryGb))
        .toString()

    private suspend fun getArray(
        credentials: BackgroundLaunchCredentials,
        host: String,
        path: String,
        tracker: LaunchProgressTracker,
        operation: String,
    ): JSONArray {
        val response = get(credentials, host, path, tracker, operation)
        tracker.progress = LaunchProgress.PREPARING_REQUEST
        tracker.failingOperation = "validate-$operation-response"
        if (response.code == 401) {
            // HTTP 401 is a transmitted auth failure, NOT a local preparation failure.
            throw OciAuthenticationException(response)
        }
        if (!response.isSuccessful) throw IllegalStateException("OCI GET failed: ${response.code}")
        return JSONArray(response.body.ifBlank { "[]" })
    }

    private suspend fun get(
        credentials: BackgroundLaunchCredentials,
        host: String,
        path: String,
        tracker: LaunchProgressTracker,
        operation: String,
    ): OciHttpResponse {
        tracker.progress = LaunchProgress.SIGNING_REQUEST
        tracker.failingOperation = "sign-$operation"
        val (authHeader, date, _) = OciRequestSigner.buildAuthHeader(
            tenancyOcid = credentials.tenancyOcid,
            userOcid = credentials.userOcid,
            fingerprint = credentials.fingerprint,
            privateKey = credentials.privateKey,
            method = "GET",
            path = path,
            host = host,
            useSecurityToken = false,
            securityToken = null,
        )
        val request = Request.Builder()
            .url("$scheme://$host$path")
            .header("date", date)
            .header("Authorization", authHeader)
            .get()
            .build()
        tracker.progress = LaunchProgress.PREPARING_REQUEST
        tracker.failingOperation = operation
        return try {
            transport.execute(request)
        } catch (wrapped: OciTransportException) {
            throw (wrapped.cause as? Exception ?: wrapped)
        }
    }

    private suspend fun post(
        credentials: BackgroundLaunchCredentials,
        host: String,
        path: String,
        body: String,
        retryToken: String,
        tracker: LaunchProgressTracker,
    ): OciHttpResponse {
        tracker.progress = LaunchProgress.PREPARING_REQUEST
        tracker.failingOperation = "hash-launch-request-body"
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val contentSha256 = java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bodyBytes))
        tracker.progress = LaunchProgress.SIGNING_REQUEST
        tracker.failingOperation = "sign-launch-request"
        val (authHeader, date, _) = OciRequestSigner.buildAuthHeader(
            tenancyOcid = credentials.tenancyOcid,
            userOcid = credentials.userOcid,
            fingerprint = credentials.fingerprint,
            privateKey = credentials.privateKey,
            method = "POST",
            path = path,
            host = host,
            useSecurityToken = false,
            securityToken = null,
            body = body,
        )
        tracker.requestSigningCompleted = true
        tracker.progress = LaunchProgress.PREPARING_REQUEST
        tracker.failingOperation = "build-launch-http-request"
        val request = Request.Builder()
            .url("$scheme://$host$path")
            .header("date", date)
            .header("Content-Type", "application/json")
            .header("x-content-sha256", contentSha256)
            .header("Authorization", authHeader)
            .header("opc-retry-token", retryToken)
            .post(bodyBytes.toRequestBody(jsonMedia))
            .build()
        tracker.requestConstructionCompleted = true
        tracker.progress = LaunchProgress.REQUEST_READY
        tracker.failingOperation = "launch-request-ready"
        tracker.progress = LaunchProgress.TRANSMISSION_STARTED
        tracker.failingOperation = "transmit-launch-request"
        tracker.transmissionStarted = true
        return try {
            transport.execute(request).also { response ->
                tracker.progress = LaunchProgress.RESPONSE_RECEIVED
                tracker.failingOperation = "read-launch-response"
                tracker.responseHeadersReceived = true
                tracker.responseCode = response.code
                tracker.redactedRequestId = response.redactedRequestId()
            }
        } catch (wrapped: OciTransportException) {
            tracker.responseHeadersReceived = wrapped.responseHeadersReceived
            tracker.responseCode = wrapped.responseCode
            tracker.redactedRequestId = wrapped.redactedRequestId
            if (wrapped.responseHeadersReceived) {
                tracker.progress = LaunchProgress.RESPONSE_RECEIVED
                tracker.failingOperation = "read-launch-response-body"
            }
            throw (wrapped.cause as? Exception ?: wrapped)
        }
    }

    private fun success(body: String, availabilityDomain: String): BackgroundLaunchResult.Success {
        val json = JSONObject(body)
        return BackgroundLaunchResult.Success(
            instanceOcid = json.getString("id"),
            displayName = json.optString("displayName").takeIf { it.isNotBlank() },
            availabilityDomain = availabilityDomain,
        )
    }

    private fun classifyOther(response: OciHttpResponse): BackgroundLaunchResult {
        val code = runCatching { JSONObject(response.body).optString("code").takeIf { it.isNotBlank() } }.getOrNull()
        val requestId = response.redactedRequestId()
        return when (response.code) {
            429 -> BackgroundLaunchResult.RateLimited(parseRetryAfterSeconds(response.header("Retry-After")))
            401 -> {
                // Should not reach here normally (OciAuthenticationException handles 401 in getArray/post),
                // but handle defensively in case classifyOther is called directly.
                BackgroundLaunchResult.AuthenticationFailure(
                    category = "oci-authentication-failed",
                    safeMessage = "OCI rejected the signed request with HTTP 401.",
                    httpStatus = 401,
                    redactedRequestId = requestId,
                    diagnostics = LaunchFailureDiagnostics.capture(
                        error = IllegalStateException("HTTP 401 from OCI"),
                        progress = LaunchProgress.RESPONSE_RECEIVED,
                        failingOperation = "classify-launch-response",
                        requestConstructionCompleted = true,
                        requestSigningCompleted = true,
                        transmissionStarted = true,
                        responseHeadersReceived = true,
                        redactedRequestId = requestId,
                        retryToken = "",
                        sessionId = "",
                    ),
                )
            }
            400, 403, 404, 409 -> BackgroundLaunchResult.TerminalFailure(response.code, code, "terminal-oci", requestId)
            408, 500, 502, 503, 504 -> BackgroundLaunchResult.AmbiguousFailure(response.code, "ambiguous-oci", requestId)
            else -> BackgroundLaunchResult.TerminalFailure(response.code, code, "unexpected-oci", requestId)
        }
    }

    private fun OciHttpResponse.redactedRequestId(): String? =
        header("opc-request-id")?.takeLast(12)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
