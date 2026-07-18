package com.zerovpn.app.oci

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import fi.iki.elonen.NanoHTTPD
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import com.zerovpn.app.ui.provisioning.Phase
import com.zerovpn.app.ui.provisioning.ProvisioningEvent
import com.zerovpn.app.ui.provisioning.Status
import java.net.URLEncoder
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

sealed class VmLaunchFailure {
    data class OutOfHostCapacity(val message: String) : VmLaunchFailure()
    data class RateLimited(val message: String, val retryAfterSeconds: Long?) : VmLaunchFailure()
    data class Other(val message: String) : VmLaunchFailure()
}

class VmLaunchFailureException(val failure: VmLaunchFailure) : Exception(
    when (failure) {
        is VmLaunchFailure.OutOfHostCapacity -> failure.message
        is VmLaunchFailure.RateLimited -> failure.message
        is VmLaunchFailure.Other -> failure.message
    },
)

internal enum class LaunchAttemptResult { SUCCESS, FAIL_CAPACITY, RATE_LIMITED, FAIL_OTHER }

internal const val A1_CAPACITY_MESSAGE =
    "Oracle has no A1 host capacity in your home region right now. ZeroVPN will wait before trying the 4 GB configuration."
internal const val OCI_RATE_LIMIT_MESSAGE = "Oracle is temporarily rate limiting VM requests."

internal data class VmLaunchHttpResponse(
    val code: Int,
    val body: String,
    val retryAfterSeconds: Long? = null,
)

internal fun isOutOfHostCapacity(responseBody: String): Boolean = runCatching {
    val error = JSONObject(responseBody)
    val code = error.optString("code").trim()
    val message = error.optString("message").trim().removeSuffix(".").trim()
    code.equals("InternalError", ignoreCase = true) &&
        message.equals("Out of host capacity", ignoreCase = true)
}.getOrDefault(false)

internal fun classifyLaunchResponse(code: Int, body: String): LaunchAttemptResult {
    return when {
        code in 200..299 -> LaunchAttemptResult.SUCCESS
        code == 429 -> LaunchAttemptResult.RATE_LIMITED
        code == 500 && isOutOfHostCapacity(body) -> LaunchAttemptResult.FAIL_CAPACITY
        else -> LaunchAttemptResult.FAIL_OTHER
    }
}

internal fun classifyFinalFailure(code: Int, body: String): LaunchAttemptResult =
    classifyLaunchResponse(code, body)

internal fun parseRetryAfterSeconds(value: String?): Long? =
    value?.trim()?.toLongOrNull()?.coerceAtLeast(0L)

internal suspend fun executeSinglePrivateChatLaunchAttempt(
    memoryGb: Int,
    request: suspend (memoryGb: Int) -> VmLaunchHttpResponse,
): JSONObject {
    require(memoryGb == 4 || memoryGb == 6) { "Private Chat memory must be 4 GB or 6 GB." }
    val response = request(memoryGb)
    return when (classifyLaunchResponse(response.code, response.body)) {
        LaunchAttemptResult.SUCCESS -> JSONObject(response.body)
        LaunchAttemptResult.FAIL_CAPACITY -> throw VmLaunchFailureException(
            VmLaunchFailure.OutOfHostCapacity(A1_CAPACITY_MESSAGE),
        )
        LaunchAttemptResult.RATE_LIMITED -> throw VmLaunchFailureException(
            VmLaunchFailure.RateLimited(OCI_RATE_LIMIT_MESSAGE, response.retryAfterSeconds),
        )
        LaunchAttemptResult.FAIL_OTHER -> throw VmLaunchFailureException(
            VmLaunchFailure.Other("POST /20160918/instances failed: ${response.code} ${response.body}"),
        )
    }
}
/**
 * Real OCI provisioner â€” ports the Python state_machine.py to Kotlin.
 *
 * Flow: browser auth â†’ preflight â†’ API key upload â†’ network â†’ VM â†’ SSH/WireGuard â†’ done
 *
 * Secrets are never written to ordinary preferences or logs. The owning workflow may
 * copy the generated signing input into Android Keystore-backed storage after the API
 * key upload succeeds so an explicitly enabled capacity retry can reuse the same key.
 */
class OciProvisioner(
    private val context: Context,
    private val region: String,
    private val isDevMode: Boolean = false,
) {
    // When isDevMode is false, wire tap and signing string debug output are suppressed.
    // Only user-facing progress messages are emitted.
    private val _events = MutableSharedFlow<ProvisioningEvent>(replay = 64, extraBufferCapacity = 64)
    val events: SharedFlow<ProvisioningEvent> = _events.asSharedFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            // WIRE TAP: log exact request headers + body for POST requests (dev mode only)
            if (isDevMode && (request.method == "POST" || request.method == "PUT")) {
                try {
                    val reqHeaders = request.headers
                    val headerDump = StringBuilder()
                    headerDump.append("[WIRE TAP] ${request.method} ${request.url}\n")
                    headerDump.append("[WIRE TAP] Request headers (${reqHeaders.size} keys):\n")
                    for (name in reqHeaders.names()) {
                        val value = reqHeaders.get(name) ?: ""
                        if (name.lowercase() != "authorization") {
                            headerDump.append("[WIRE TAP]   $name: $value\n")
                        }
                    }
                    val bodyCopy = request.body
                    if (bodyCopy != null) {
                        val buffer = Buffer()
                        bodyCopy.writeTo(buffer)
                        val bodyStr = buffer.readUtf8()
                        val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
                        val sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(bodyBytes)
                        val sha256b64 = java.util.Base64.getEncoder().encodeToString(sha256)
                        headerDump.append("[WIRE TAP] Request body: ${bodyBytes.size} bytes, SHA256=$sha256b64\n")
                    }
                    headerDump.append("[WIRE TAP] Response: ${response.code}\n")
                    headerDump.append("[WIRE TAP] ---")
                    // Emit each line as a provisioning event
                    headerDump.toString().split("\n").forEach { line ->
                        kotlinx.coroutines.runBlocking {
                            _events.emit(ProvisioningEvent(
                                timestamp = System.currentTimeMillis(),
                                phase = Phase.API_KEY,
                                status = Status.RUNNING,
                                message = line,
                                developerOnly = true,
                            ))
                        }
                    }
                } catch (e: Exception) {
                    kotlinx.coroutines.runBlocking {
                        _events.emit(ProvisioningEvent(
                            timestamp = System.currentTimeMillis(),
                            phase = Phase.API_KEY,
                            status = Status.RUNNING,
                            message = "[WIRE TAP] Error: ${e.message}",
                            developerOnly = true,
                        ))
                    }
                }
            }
            response
        }
        .build()

    private val jsonMedia = "application/json".toMediaType()

    // --- Result types ---

    data class AuthResult(
        val securityToken: String,
        val privateKey: java.security.PrivateKey,
        val keyPair: java.security.KeyPair,
        val userOcid: String,
        val tenancyOcid: String,
        val fingerprint: String,
        val authBootstrapRegion: String,
        val tokenRegion: String? = null,
        val tokenRegionSource: String? = null,
        /**
         * Current auth context. Starts as [OciAuthContext.SecurityTokenBootstrap] during browser login,
         * then switches to [OciAuthContext.ApiKey] after the API key upload succeeds.
         * All post-upload OCI calls use API-key auth (useSecurityToken = false, securityToken = null).
         */
        var authContext: OciAuthContext = OciAuthContext.SecurityTokenBootstrap(
            securityToken = securityToken,
            privateKey = privateKey,
            tenancyOcid = tenancyOcid,
            userOcid = userOcid,
            fingerprint = fingerprint,
        ),
    ) {
        /**
         * Whether the current auth context uses a security token (browser bootstrap).
         * After API key upload, this becomes false and all subsequent calls use API-key auth.
         */
        val usesSecurityToken: Boolean get() = authContext is OciAuthContext.SecurityTokenBootstrap

        /**
         * The security token from the bootstrap phase, or null after switching to API-key auth.
         */
        val activeSecurityToken: String? get() = (authContext as? OciAuthContext.SecurityTokenBootstrap)?.securityToken
    }

    data class PreflightResult(
        val success: Boolean,
        val homeRegion: String,
        val isUkRegion: Boolean,
        val error: String? = null,
        val initialRegion: String,
        val regionDiscoverySource: String,
        val identityHost: String,
        val iaasHost: String,
        val isTransientNetworkFailure: Boolean = false,
    )

    data class ProvisionResult(
        val publicIp: String,
        val wireGuardPort: Int,
        val clientConfig: String,
        val clientPublicKey: String,
        val serverPublicKey: String,
        val serverPeerPublicKey: String,
        val inviteProfiles: List<InvitePeerProvisionResult> = emptyList(),
        val sshUsername: String,
        val sshPrivateKey: String,
    )

    data class InvitePeerProvisionResult(
        val slotIndex: Int,
        val tunnelIp: String,
        val clientConfig: String,
        val clientPublicKey: String,
    )

    private data class WireGuardClientKeys(
        val privateKey: String,
        val publicKey: String,
    )

    private data class FriendInvitePeer(
        val slotIndex: Int,
        val tunnelIp: String,
        val keys: WireGuardClientKeys,
    )

    private data class OciPostResponse(
        val code: Int,
        val body: String,
        val isSuccessful: Boolean,
        val retryAfterSeconds: Long? = null,
    )

    data class ResourceIds(
        var vcnId: String? = null,
        var slId: String? = null,
        var subnetId: String? = null,
        var igwId: String? = null,
        var instanceId: String? = null,
        var availabilityDomain: String? = null,
        var ubuntuImageOcid: String? = null,
    )

    // --- Event helper ---

    private suspend fun emit(phase: Phase, status: Status, message: String) {
        _events.emit(ProvisioningEvent(
            timestamp = System.currentTimeMillis(),
            phase = phase,
            status = status,
            message = message,
        ))
    }

    private suspend fun emitDeveloperOnly(phase: Phase, status: Status, message: String) {
        _events.emit(ProvisioningEvent(
            timestamp = System.currentTimeMillis(),
            phase = phase,
            status = status,
            message = message,
            developerOnly = true,
        ))
    }

    // --- Phase 1: Browser Auth ---

    suspend fun authenticate(): AuthResult {
        emit(Phase.AUTH, Status.RUNNING, "Generating RSA keypair...")
        val keyPair = OciRequestSigner.generateKeyPair()
        val publicKey = keyPair.public as RSAPublicKey
        val fingerprint = OciRequestSigner.md5Fingerprint(publicKey)

        emit(Phase.AUTH, Status.RUNNING, "Building OAuth URL...")
        val jwk = OciRequestSigner.publicKeyToJwk(publicKey)
        val jwkB64 = OciRequestSigner.base64UrlEncode(jwk)
        val realm = OciEndpoints.realm(region)
        val redirectUri = "http://localhost:8181"
        val authorizeUrl = OciRequestSigner.buildAuthorizeUrl(
            region = region,
            realm = realm,
            publicKeyJwkBase64 = jwkB64,
            redirectUri = redirectUri,
        )

        emit(Phase.AUTH, Status.RUNNING, "Opening Oracle login in browser...")
        // Start NanoHTTPD server to catch the redirect
        val tokenDeferred = CompletableDeferred<String?>()
        val server = object : NanoHTTPD(8181) {
            override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
                val uri = session.uri
                val params = session.parameters

                if (uri == "/" || uri.startsWith("/?")) {
                    val html = authCallbackPage()
                    return newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/html", html)
                } else if (uri.startsWith("/token")) {
                    val token = params["security_token"]?.firstOrNull()
                    if (token != null) {
                        tokenDeferred.complete(token)
                    }
                    return newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", "OK")
                }
                return newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "404")
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

        // Open Chrome Custom Tab
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        customTabsIntent.intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK

        // Launch on main thread
        (context as android.app.Activity).runOnUiThread {
            customTabsIntent.launchUrl(context, Uri.parse(authorizeUrl))
        }

        emit(Phase.AUTH, Status.RUNNING, "Waiting for Oracle login...")

        // Wait for token (with 5-min timeout)
        val token = try {
            kotlinx.coroutines.withTimeout(300_000) {
                tokenDeferred.await()
            }
        } catch (e: Exception) {
            server.stop()
            emit(Phase.AUTH, Status.ERROR, "Login timed out")
            throw Exception("Browser auth timed out")
        }

        server.stop()

        if (token == null) {
            emit(Phase.AUTH, Status.ERROR, "No security token received")
            throw Exception("No security token received")
        }

        emit(Phase.AUTH, Status.RUNNING, "Decoding token...")
        val claims = OciRequestSigner.decodeJwt(token)
        val userOcid = claims["sub"] as? String
        val tenancyOcid = claims["tenant"] as? String
        val (tokenRegion, tokenRegionSource) = extractRegionFromClaims(claims)

        if (userOcid == null || tenancyOcid == null) {
            emit(Phase.AUTH, Status.ERROR, "Token missing required claims")
            throw Exception("Token missing sub/tenant claims")
        }

        emit(Phase.AUTH, Status.SUCCESS, "Authenticated")
        if (isDevMode) {
            emit(Phase.AUTH, Status.RUNNING, "Auth bootstrap region: $region")
            emit(Phase.AUTH, Status.RUNNING, "Token region source: ${tokenRegionSource ?: "none"}")
        }
        return AuthResult(
            securityToken = token,
            privateKey = keyPair.private,
            keyPair = keyPair,
            userOcid = userOcid,
            tenancyOcid = tenancyOcid,
            fingerprint = fingerprint,
            authBootstrapRegion = region,
            tokenRegion = tokenRegion,
            tokenRegionSource = tokenRegionSource,
        )
    }

    private fun extractRegionFromClaims(claims: Map<String, Any?>): Pair<String?, String?> {
        val regionClaimKeys = listOf(
            "home_region",
            "homeRegion",
            "region",
            "tenant_region",
            "tenancy_region",
            "res_tenant_region",
        )
        for (key in regionClaimKeys) {
            val value = claims[key]?.toString()?.trim()?.takeIf { it.isNotBlank() && it != "null" }
            val normalized = OciRegions.normalizeRegionHint(value)
            if (normalized != null) return normalized to "token claim: $key"
        }
        return null to null
    }

    private fun authCallbackPage(): String {
        return """
            <!doctype html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Oracle sign-in complete</title>
              <style>
                body { font-family: sans-serif; margin: 0; padding: 24px; color: #102027; background: #f7f9fb; }
                main { max-width: 520px; margin: 40px auto; }
                h1 { font-size: 24px; margin: 0 0 12px; }
                p { font-size: 16px; line-height: 1.45; }
                a.button { display: inline-block; margin-top: 16px; padding: 12px 16px; border-radius: 8px; background: #00D1B2; color: #001312; font-weight: 700; text-decoration: none; }
                .muted { color: #52646d; font-size: 14px; }
              </style>
            </head>
            <body>
              <main>
                <h1>Oracle sign-in is complete.</h1>
                <p>Return to ZeroVPN to continue.</p>
                <a class="button" href="${OciAuthReturn.CALLBACK_URI}">Open ZeroVPN</a>
                <p class="muted">If ZeroVPN does not open, use your recent apps button and return to ZeroVPN.</p>
              </main>
              <script>
                (function() {
                  var h = window.location.hash || "";
                  if (h.charAt(0) === "#") h = h.substring(1);
                  if (h.length > 0) {
                    var r = new XMLHttpRequest();
                    r.onload = function() {
                      setTimeout(function() { window.location.href = "${OciAuthReturn.CALLBACK_URI}"; }, 600);
                    };
                    r.open("GET", "/token?" + h);
                    r.send();
                  }
                })();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    // --- Phase 2: Preflight ---

    suspend fun preflight(
        auth: AuthResult,
        preferredRegion: String? = null,
        preferredRegionSource: String = "persisted",
    ): PreflightResult {
        val authoritativeRegion = preferredRegion?.takeIf { it.isNotBlank() }
            ?: return PreflightResult(
                success = false,
                homeRegion = "",
                isUkRegion = false,
                error = "REGION_SELECTION_REQUIRED",
                initialRegion = auth.authBootstrapRegion,
                regionDiscoverySource = "region-selection-required",
                identityHost = "",
                iaasHost = "",
            )
        // A user-selected or persisted verified region is already authoritative. Do not
        // spend the browser-token window on DNS preflight or candidate-region scanning.
        val homeRegion = authoritativeRegion
        val idHost = OciEndpoints.identityHost(homeRegion)
        val iaasHost = OciEndpoints.iaasHost(homeRegion)
        val isUk = homeRegion in listOf("uk-london-1", "uk-cardiff-1")
        emit(
            Phase.API_KEY,
            Status.RUNNING,
            "Phase region trace: identityRegion=$homeRegion identityHost=$idHost finalHomeRegion=$homeRegion",
        )
        if (isDevMode) {
            emit(Phase.API_KEY, Status.RUNNING, "Final provisioning region: $homeRegion")
            emit(Phase.API_KEY, Status.RUNNING, "Identity host: $idHost")
            emit(Phase.API_KEY, Status.RUNNING, "IaaS host: $iaasHost")
            emit(Phase.API_KEY, Status.RUNNING, "Signer region: $homeRegion")
            emit(Phase.API_KEY, Status.RUNNING, "Realm/domain suffix: ${OciEndpoints.realm(homeRegion)}")
        }

        if (isUk) {
            emit(Phase.API_KEY, Status.WARNING, "UK region (dev/test mode)")
        }

        val keyPath = "/20160918/users/${auth.userOcid}/apiKeys"
        val keyUrl = "https://$idHost$keyPath"
        val (keyAuthHeader, keyDateStr, _) = OciRequestSigner.buildAuthHeader(
            tenancyOcid = auth.tenancyOcid,
            userOcid = auth.userOcid,
            fingerprint = auth.fingerprint,
            privateKey = auth.privateKey,
            method = "GET",
            path = keyPath,
            host = idHost,
            useSecurityToken = true,
            securityToken = auth.securityToken,
        )
        val keyReq = Request.Builder()
            .url(keyUrl)
            .header("date", keyDateStr)
            .header("Authorization", keyAuthHeader)
            .get()
            .build()

        try {
            val keyResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val resp = httpClient.newCall(keyReq).execute()
                val body = resp.body?.string() ?: "[]"
                Triple(resp.code, body, resp.isSuccessful)
            }
            if (keyResult.third) {
                val keys = JSONArray(keyResult.second)
                emit(Phase.API_KEY, Status.RUNNING, "API keys on account: ${keys.length()}/3")
                if (keys.length() >= 3) {
                    val error = "API key limit reached (${keys.length()}/3). Delete an existing API key in the Oracle console."
                    emit(Phase.API_KEY, Status.ERROR, error)
                    return PreflightResult(false, homeRegion, isUk, error, auth.authBootstrapRegion, preferredRegionSource, idHost, iaasHost)
                }
            } else {
                emit(Phase.API_KEY, Status.WARNING, "API key check: HTTP ${keyResult.first}")
            }
        } catch (e: Exception) {
            if (isTransientNetworkFailure(e)) {
                val error = "TRANSIENT_NETWORK_FAILURE: Oracle could not be reached in $homeRegion. The selected region and authenticated session were preserved; retry later."
                emit(Phase.API_KEY, Status.WARNING, error)
                return PreflightResult(
                    success = false,
                    homeRegion = homeRegion,
                    isUkRegion = isUk,
                    error = error,
                    initialRegion = auth.authBootstrapRegion,
                    regionDiscoverySource = preferredRegionSource,
                    identityHost = idHost,
                    iaasHost = iaasHost,
                    isTransientNetworkFailure = true,
                )
            }
            emit(Phase.API_KEY, Status.WARNING, "API key check failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        emit(Phase.API_KEY, Status.SUCCESS, "Preflight passed")
        return PreflightResult(
            success = true,
            homeRegion = homeRegion,
            isUkRegion = isUk,
            error = null,
            initialRegion = auth.authBootstrapRegion,
            regionDiscoverySource = preferredRegionSource,
            identityHost = idHost,
            iaasHost = iaasHost,
        )
    }

    private fun isTransientNetworkFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any {
            it is java.net.UnknownHostException ||
                it is java.net.ConnectException ||
                it is java.net.SocketTimeoutException ||
                it is javax.net.ssl.SSLException
        }
    // --- Phase 3: API Key Upload ---

    private suspend fun uploadApiKey(
        auth: AuthResult,
        homeRegion: String,
        onUploaded: ((String) -> Unit)? = null,
    ): String {
        emit(Phase.API_KEY, Status.RUNNING, "Uploading API key...")
        val idHost = OciEndpoints.identityHost(homeRegion)
        val path = "/20160918/users/${auth.userOcid}/apiKeys"
        val url = "https://$idHost$path"

        val publicKey = auth.keyPair.public as RSAPublicKey
        val pubPem = OciRequestSigner.publicKeyToPem(publicKey)
        val jsonBody = JSONObject().put("key", pubPem).toString()

        // Compute body headers ï¿½ must match what OkHttp actually sends
        val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)
        val contentSha256 = java.util.Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(bodyBytes)
        )
        // Don't set content-length manually ï¿½ OkHttp computes it from the request body
        // The signing string must use the same value OkHttp will send
        val contentLength = bodyBytes.size.toString()

        val (authHeader, dateStr, _) = OciRequestSigner.buildAuthHeader(
                tenancyOcid = auth.tenancyOcid,
                userOcid = auth.userOcid,
                fingerprint = auth.fingerprint,
                privateKey = auth.privateKey,
                method = "POST",
                path = path,
                host = idHost,
                useSecurityToken = true, securityToken = auth.securityToken,
                body = jsonBody, )

        // Developer diagnostics never render signing strings or key material.
        if (isDevMode) {
            emit(Phase.API_KEY, Status.RUNNING, "API key signerRegionId=$homeRegion identityHost=$idHost identityUrl=https://$idHost")
            emit(Phase.API_KEY, Status.RUNNING, "Authorization header generated")
        }

        val request = Request.Builder()
            .url(url)
            .header("date", dateStr)
            .header("Content-Type", "application/json")
            .header("x-content-sha256", contentSha256)
            .header("Authorization", authHeader)
            .post(bodyBytes.toRequestBody(jsonMedia))
            .build()

        data class HttpResp(val code: Int, val body: String, val isSuccessful: Boolean)
        val httpResp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val resp = httpClient.newCall(request).execute()
            val body = resp.body?.string() ?: ""
            HttpResp(resp.code, body, resp.isSuccessful)
        }
        emit(Phase.API_KEY, Status.RUNNING, "Upload response: HTTP ${httpResp.code}")
        if (!httpResp.isSuccessful && httpResp.code != 409) {
            throw Exception("API key upload failed: HTTP ${httpResp.code}")
        }

        val uploadedFingerprint = try {
            if (httpResp.body.isBlank()) auth.fingerprint else JSONObject(httpResp.body).optString("fingerprint", auth.fingerprint)
        } catch (e: Exception) {
            auth.fingerprint
        }
        if (uploadedFingerprint != auth.fingerprint) {
            emit(Phase.API_KEY, Status.WARNING, "Uploaded API key fingerprint differs from local fingerprint; using OCI response")
        }

        // --- Key identity verification (pre-upload already done above) ---
        val publicKeySha256 = runCatching {
            OciCredentialIdentity.sha256Digest(
                OciCredentialIdentity.publicKeyFrom(auth.privateKey)
            )
        }.getOrNull()

        // Verify credential identity: the stored fingerprint must match the signing key.
        OciCredentialIdentity.verify(auth.privateKey, uploadedFingerprint, publicKeySha256)

        onUploaded?.invoke(uploadedFingerprint)

        // --- Stage 1: Verify Oracle registered the key (using browser security-token auth) ---
        // After uploading the public key, use the still-valid browser security token to call
        // ListApiKeys and confirm the fingerprint appears in Oracle's response.
        val registrationProbePath = "/20160918/users/${auth.userOcid}/apiKeys"
        val registrationBackoffMs = longArrayOf(1000, 2000, 4000, 8000, 15000)
        var keyRegistered = false
        var registrationAttempts = 0
        var registrationRequestId: String? = null
        for ((index, delayMs) in registrationBackoffMs.withIndex()) {
            registrationAttempts++
            emit(Phase.API_KEY, Status.RUNNING, "Verifying key registration (attempt ${index + 1}/${registrationBackoffMs.size})...")
            kotlinx.coroutines.delay(delayMs)
            try {
                val keysResponse = securityTokenGetArray(auth, idHost, registrationProbePath)
                registrationRequestId = keysResponse.requestId
                val fingerprints = mutableListOf<String>()
                for (i in 0 until keysResponse.array.length()) {
                    val fp = keysResponse.array.getJSONObject(i).optString("fingerprint", "")
                    if (fp.isNotBlank()) fingerprints.add(fp)
                }
                if (uploadedFingerprint in fingerprints) {
                    keyRegistered = true
                    emit(Phase.API_KEY, Status.RUNNING, "Key registration confirmed: fingerprint present in ListApiKeys response.")
                    break
                } else {
                    emit(Phase.API_KEY, Status.RUNNING, "Fingerprint not yet visible in ListApiKeys, retrying...")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(Phase.API_KEY, Status.WARNING, "Registration lookup error: ${e.javaClass.simpleName}: ${e.message}")
                break
            }
        }
        if (!keyRegistered) {
            val keyId = "${auth.tenancyOcid}/${auth.userOcid}/$uploadedFingerprint"
            emit(Phase.API_KEY, Status.ERROR, "API-key registration could not be confirmed after $registrationAttempts attempt(s).")
            throw ApiKeyVerificationException(
                stage = "KEY_REGISTRATION_PENDING",
                fingerprint = uploadedFingerprint,
                keyId = keyId,
                endpoint = "https://$idHost$registrationProbePath",
                attemptCount = registrationAttempts,
                requestId = registrationRequestId,
            )
        }

        // --- Stage 2: Verify API-key signing ---
        // Only after Stage 1 confirms the key is registered, perform a read-only GET
        // using API-key authentication (useSecurityToken = false).
        // If this returns 401 while Stage 1 confirmed the key exists, the signing
        // implementation is broken — it is NOT activation delay.
        emit(Phase.API_KEY, Status.RUNNING, "Verifying API-key signing...")
        val signingProbePath = "/20160918/users/${auth.userOcid}/apiKeys"
        val signingProbeResult = probeApiKeySigning(auth, uploadedFingerprint, idHost, signingProbePath)
        when (signingProbeResult) {
            is SigningProbeResult.Success -> {
                emit(Phase.API_KEY, Status.RUNNING, "API-key signing verified: Oracle accepted the signed request.")
            }
            is SigningProbeResult.AuthenticationRejected -> {
                val keyId = "${auth.tenancyOcid}/${auth.userOcid}/$uploadedFingerprint"
                emit(Phase.API_KEY, Status.ERROR, "API-key signature validation failed. Oracle registered the key but rejected the signed request.")
                throw ApiKeyVerificationException(
                    stage = "API_KEY_SIGNATURE_VALIDATION_FAILED",
                    fingerprint = uploadedFingerprint,
                    keyId = keyId,
                    endpoint = "https://$idHost$signingProbePath",
                    attemptCount = 1,
                    requestId = signingProbeResult.requestId,
                )
            }
            is SigningProbeResult.Error -> {
                val keyId = "${auth.tenancyOcid}/${auth.userOcid}/$uploadedFingerprint"
                emit(Phase.API_KEY, Status.ERROR, "API-key signing probe error: ${signingProbeResult.exceptionClass}: ${signingProbeResult.message}")
                throw ApiKeyVerificationException(
                    stage = "API_KEY_SIGNATURE_VALIDATION_FAILED",
                    fingerprint = uploadedFingerprint,
                    keyId = keyId,
                    endpoint = "https://$idHost$signingProbePath",
                    attemptCount = 1,
                    requestId = null,
                )
            }
        }

        // --- Both stages passed: switch to API-key auth ---
        auth.authContext = OciAuthContext.ApiKey(
            tenancyOcid = auth.tenancyOcid,
            userOcid = auth.userOcid,
            fingerprint = uploadedFingerprint,
            privateKey = auth.privateKey,
            region = homeRegion,
            publicKeySha256 = publicKeySha256,
        )

        emit(Phase.API_KEY, Status.SUCCESS, "API key uploaded, registered, and signing verified.")
        return uploadedFingerprint
    }

    /**
     * Security-token-authenticated GET that returns the full response (status, body, requestId).
     * Used for Stage 1 registration checks.
     */
    private suspend fun securityTokenGetArray(auth: AuthResult, host: String, path: String): SecurityTokenGetResult {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val (authHeader, dateStr, _) = OciRequestSigner.buildAuthHeader(
                tenancyOcid = auth.tenancyOcid,
                userOcid = auth.userOcid,
                fingerprint = auth.fingerprint,
                privateKey = auth.privateKey,
                method = "GET",
                path = path,
                host = host,
                useSecurityToken = true,
                securityToken = auth.securityToken,
            )
            val req = Request.Builder()
                .url("https://$host$path")
                .header("date", dateStr)
                .header("Authorization", authHeader)
                .get()
                .build()
            val resp = httpClient.newCall(req).execute()
            resp.use { response ->
                val body = response.body?.string() ?: "[]"
                val requestId = response.header("opc-request-id")?.takeLast(12)
                if (!response.isSuccessful) {
                    throw Exception("Security-token GET failed: HTTP ${response.code}")
                }
                SecurityTokenGetResult(
                    array = JSONArray(body.ifBlank { "[]" }),
                    requestId = requestId,
                )
            }
        }
    }

    /**
     * API-key-authenticated GET probe for Stage 2 signing verification.
     * Returns Success, AuthenticationRejected (401), or Error.
     */
    private suspend fun probeApiKeySigning(auth: AuthResult, fingerprint: String, host: String, path: String): SigningProbeResult {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val (authHeader, dateStr, _) = OciRequestSigner.buildAuthHeader(
                    tenancyOcid = auth.tenancyOcid,
                    userOcid = auth.userOcid,
                    fingerprint = fingerprint,
                    privateKey = auth.privateKey,
                    method = "GET",
                    path = path,
                    host = host,
                    useSecurityToken = false,
                    securityToken = null,
                )
                val req = Request.Builder()
                    .url("https://$host$path")
                    .header("date", dateStr)
                    .header("Authorization", authHeader)
                    .get()
                    .build()
                val resp = httpClient.newCall(req).execute()
                resp.use { response ->
                    val requestId = response.header("opc-request-id")?.takeLast(12)
                    when (response.code) {
                        in 200..299 -> SigningProbeResult.Success
                        401 -> SigningProbeResult.AuthenticationRejected(requestId = requestId)
                        else -> SigningProbeResult.Error(
                            exceptionClass = "HttpResponseException",
                            message = "API-key signing probe received HTTP ${response.code}",
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SigningProbeResult.Error(
                    exceptionClass = e.javaClass.simpleName,
                    message = e.message ?: "Unknown error",
                )
            }
        }
    }

    private data class SecurityTokenGetResult(val array: JSONArray, val requestId: String?)

    private sealed class SigningProbeResult {
        data object Success : SigningProbeResult()
        data class AuthenticationRejected(val requestId: String?) : SigningProbeResult()
        data class Error(val exceptionClass: String, val message: String) : SigningProbeResult()
    }

    /**
     * Thrown when API-key verification fails at either stage.
     * Contains only signer-safe diagnostic fields — no private key material or authorization signatures.
     */
    class ApiKeyVerificationException(
        val stage: String,
        val fingerprint: String,
        val keyId: String,
        val endpoint: String,
        val attemptCount: Int,
        val requestId: String?,
    ) : Exception(
        when (stage) {
            "KEY_REGISTRATION_PENDING" ->
                "Key registration pending after $attemptCount attempt(s). " +
                    "The key was uploaded but Oracle did not list it."
            "API_KEY_SIGNATURE_VALIDATION_FAILED" ->
                "Oracle registered the API key, but ZeroVPN could not authenticate with it. " +
                    "No cloud resources were created. Do not create another key automatically."
            else ->
                "API-key verification failed at stage $stage after $attemptCount attempt(s)."
        }
    )

    // --- Phase 4: Network Creation ---

    private suspend fun createNetwork(auth: AuthResult, homeRegion: String): ResourceIds {
        val rids = ResourceIds()
        val cid = auth.tenancyOcid
        val iaasHost = OciEndpoints.iaasHost(homeRegion)
        emit(
            Phase.NETWORK,
            Status.RUNNING,
            "Phase region trace: provisioningRegion=$homeRegion iaasHost=$iaasHost",
        )

        // Create VCN
        emit(Phase.NETWORK, Status.RUNNING, "Creating VCN...")
        val vcnBody = JSONObject()
            .put("cidrBlock", "10.0.0.0/24")
            .put("compartmentId", cid)
            .put("displayName", "zerovpn-vcn")
            .put("dnsLabel", "zerovpn")
            .toString()
        val vcnResp = ociPost(auth, iaasHost, "/20160918/vcns", vcnBody)
        rids.vcnId = vcnResp.getString("id")
        waitForState(auth, iaasHost, "/20160918/vcns/${rids.vcnId}", "AVAILABLE")

        // Create security list
        emit(Phase.NETWORK, Status.RUNNING, "Creating security list...")
        val ingressRules = JSONArray()
        ingressRules.put(JSONObject()
            .put("source", "0.0.0.0/0")
            .put("protocol", "6")
            .put("isStateless", false)
            .put("tcpOptions", JSONObject()
                .put("destinationPortRange", JSONObject()
                    .put("min", 22).put("max", 22))))
        ingressRules.put(JSONObject()
            .put("source", "0.0.0.0/0")
            .put("protocol", "17")
            .put("isStateless", false)
            .put("udpOptions", JSONObject()
                .put("destinationPortRange", JSONObject()
                    .put("min", 51820).put("max", 51820))))

        val egressRules = JSONArray()
        egressRules.put(JSONObject()
            .put("destination", "0.0.0.0/0")
            .put("protocol", "all")
            .put("isStateless", false))

        val slBody = JSONObject()
            .put("compartmentId", cid)
            .put("vcnId", rids.vcnId)
            .put("displayName", "zerovpn-sl")
            .put("egressSecurityRules", egressRules)
            .put("ingressSecurityRules", ingressRules)
            .toString()
        val slResp = ociPost(auth, iaasHost, "/20160918/securityLists", slBody)
        rids.slId = slResp.getString("id")

        // Create subnet (use VCN default DHCP options ï¿½ no need to fetch them)
        emit(Phase.NETWORK, Status.RUNNING, "Creating subnet...")

        val subnetBody = JSONObject()
            .put("cidrBlock", "10.0.0.0/24")
            .put("compartmentId", cid)
            .put("displayName", "zerovpn-subnet")
            .put("vcnId", rids.vcnId)
            .put("securityListIds", JSONArray().put(rids.slId))
            .toString()
        val subnetResp = ociPost(auth, iaasHost, "/20160918/subnets", subnetBody)
        rids.subnetId = subnetResp.getString("id")
        waitForState(auth, iaasHost, "/20160918/subnets/${rids.subnetId}", "AVAILABLE")

        // Create IGW
        emit(Phase.NETWORK, Status.RUNNING, "Creating internet gateway...")
        val igwBody = JSONObject()
            .put("compartmentId", cid)
            .put("displayName", "zerovpn-igw")
            .put("isEnabled", true)
            .put("vcnId", rids.vcnId)
            .toString()
        val igwResp = ociPost(auth, iaasHost, "/20160918/internetGateways", igwBody)
        rids.igwId = igwResp.getString("id")
        waitForState(auth, iaasHost, "/20160918/internetGateways/${rids.igwId}", "AVAILABLE")

        // Update route table
        emit(Phase.NETWORK, Status.RUNNING, "Configuring route table...")
        val vcnGet = ociGet(auth, iaasHost, "/20160918/vcns/${rids.vcnId}")
        val rtId = vcnGet.getString("defaultRouteTableId")

        val routeRules = JSONArray()
        routeRules.put(JSONObject()
            .put("destination", "0.0.0.0/0")
            .put("destinationType", "CIDR_BLOCK")
            .put("networkEntityId", rids.igwId))
        val rtBody = JSONObject().put("routeRules", routeRules).toString()
        ociPut(auth, iaasHost, "/20160918/routeTables/$rtId", rtBody)

        emit(Phase.NETWORK, Status.SUCCESS, "Network ready")
        return rids
    }

    // --- Phase 5: VM Launch ---

    internal suspend fun launchVm(
        auth: AuthResult,
        homeRegion: String,
        rids: ResourceIds,
        sshPublicKey: String,
        privateChatRequested: Boolean,
        privateChatMemoryGb: Int = 6,
    ): String {
        val cid = auth.tenancyOcid
        val idHost = OciEndpoints.identityHost(homeRegion)
        val iaasHost = OciEndpoints.iaasHost(homeRegion)
        val shape = if (privateChatRequested) "VM.Standard.A1.Flex" else "VM.Standard.E2.1.Micro"
        emit(
            Phase.VM_LAUNCH,
            Status.RUNNING,
            "Phase region trace: provisioningRegion=$homeRegion iaasHost=$iaasHost imageRegion=$homeRegion instanceLaunchRegion=$homeRegion",
        )

        // Get availability domain (returns a JSON array, not an object with "items")
        emit(Phase.VM_LAUNCH, Status.RUNNING, "Finding availability domain...")
        val adResp = ociGetArray(auth, idHost, "/20160918/availabilityDomains?compartmentId=$cid")
        val adName = adResp.getJSONObject(0).getString("name")
        rids.availabilityDomain = adName
        emit(Phase.VM_LAUNCH, Status.RUNNING, "Availability domain selected: $adName (region=$homeRegion)")

        // Find Ubuntu image
        emit(Phase.VM_LAUNCH, Status.RUNNING, "Finding Ubuntu 22.04 image...")
        val imgPath = "/20160918/images?compartmentId=${URLEncoder.encode(cid, "UTF-8")}" +
            "&operatingSystem=Canonical+Ubuntu&operatingSystemVersion=22.04" +
            "&shape=$shape&sortBy=TIMECREATED&sortOrder=DESC"
        val imgResp = ociGetArray(auth, iaasHost, imgPath)
        var imageId: String
        if (imgResp.length() > 0) {
            imageId = imgResp.getJSONObject(0).getString("id")
        } else {
            emit(Phase.VM_LAUNCH, Status.RUNNING, "Trying Ubuntu 24.04...")
            val imgPath24 = "/20160918/images?compartmentId=${URLEncoder.encode(cid, "UTF-8")}" +
                "&operatingSystem=Canonical+Ubuntu&operatingSystemVersion=24.04" +
                "&shape=$shape&sortBy=TIMECREATED&sortOrder=DESC"
            val imgResp24 = ociGetArray(auth, iaasHost, imgPath24)
            if (imgResp24.length() == 0) {
                throw Exception("No supported Ubuntu image found for $shape")
            }
            imageId = imgResp24.getJSONObject(0).getString("id")
        }
        rids.ubuntuImageOcid = imageId

        // Launch instance
        val launchPath = "/20160918/instances"
        val launchResp = if (privateChatRequested) {
            require(privateChatMemoryGb == 4 || privateChatMemoryGb == 6) {
                "Private Chat memory must be 4 GB or 6 GB."
            }
            emit(
                Phase.VM_LAUNCH,
                Status.RUNNING,
                "Private Chat requested: using VM.Standard.A1.Flex with 1 OCPU and $privateChatMemoryGb GB RAM. " +
                    "Requested resources appear Free Tier eligible. Oracle, not ZeroVPN, determines actual billing.",
            )

            fun buildLaunchBody(memoryInGBs: Int): String = JSONObject()
                .put("availabilityDomain", adName)
                .put("compartmentId", cid)
                .put("displayName", "zerovpn-exit-01")
                .put("shape", shape)
                .put("subnetId", rids.subnetId)
                .put("sourceDetails", JSONObject()
                    .put("imageId", imageId)
                    .put("bootVolumeSizeInGBs", 50)
                    .put("sourceType", "image"))
                .put("createVnicDetails", JSONObject()
                    .put("subnetId", rids.subnetId)
                    .put("assignPublicIp", true))
                .put("metadata", JSONObject().put("ssh_authorized_keys", sshPublicKey))
                .put(
                    "shapeConfig",
                    JSONObject()
                        .put("ocpus", 1)
                        .put("memoryInGBs", memoryInGBs),
                )
                .toString()

            suspend fun postLaunch(memoryInGBs: Int): OciPostResponse {
                emit(
                    Phase.VM_LAUNCH,
                    Status.RUNNING,
                    "Launching Private Chat Node VM: VM.Standard.A1.Flex â€” 1 OCPU / $memoryInGBs GB",
                )
                return ociPostWithResponse(auth, iaasHost, launchPath, buildLaunchBody(memoryInGBs))
            }

            try {
                executeSinglePrivateChatLaunchAttempt(privateChatMemoryGb) { memoryGb ->
                    val response = postLaunch(memoryGb)
                    VmLaunchHttpResponse(
                        code = response.code,
                        body = response.body,
                        retryAfterSeconds = response.retryAfterSeconds,
                    )
                }
            } catch (error: VmLaunchFailureException) {
                if (isDevMode) {
                    emitDeveloperOnly(
                        Phase.VM_LAUNCH,
                        Status.RUNNING,
                        "OCI launch result ($privateChatMemoryGb GB): ${error.failure::class.simpleName}",
                    )
                }
                when (val failure = error.failure) {
                    is VmLaunchFailure.OutOfHostCapacity -> emit(Phase.VM_LAUNCH, Status.ERROR, failure.message)
                    is VmLaunchFailure.RateLimited -> emit(Phase.VM_LAUNCH, Status.WARNING, failure.message)
                    is VmLaunchFailure.Other -> Unit
                }
                throw error
            }
        } else {
            emit(Phase.VM_LAUNCH, Status.RUNNING, "Launching instance ($shape)...")
            val launchBodyJson = JSONObject()
                .put("availabilityDomain", adName)
                .put("compartmentId", cid)
                .put("displayName", "zerovpn-exit-01")
                .put("shape", shape)
                .put("subnetId", rids.subnetId)
                .put("sourceDetails", JSONObject()
                    .put("imageId", imageId)
                    .put("bootVolumeSizeInGBs", 50)
                    .put("sourceType", "image"))
                .put("createVnicDetails", JSONObject()
                    .put("subnetId", rids.subnetId)
                    .put("assignPublicIp", true))
                .put("metadata", JSONObject().put("ssh_authorized_keys", sshPublicKey))
                .toString()
            ociPost(auth, iaasHost, launchPath, launchBodyJson)
        }
        val instanceId = launchResp.getString("id")
        rids.instanceId = instanceId

        // Wait for RUNNING
        emit(Phase.VM_LAUNCH, Status.RUNNING, "Waiting for instance to be running...")
        var instanceState = ""
        for (i in 1..30) {
            kotlinx.coroutines.delay(10_000)
            val instResp = ociGet(auth, iaasHost, "/20160918/instances/$instanceId")
            instanceState = instResp.optString("lifecycleState", "")
            if (instanceState == "RUNNING") break
            if (instanceState == "TERMINATED" || instanceState == "FAILED") {
                throw Exception("Instance entered $instanceState state")
            }
        }
        if (instanceState != "RUNNING") {
            throw Exception("Instance not running after 5 min (state=$instanceState)")
        }

        // Get public IP
        emit(Phase.VM_LAUNCH, Status.RUNNING, "Allocating public IP...")
        kotlinx.coroutines.delay(5000)
        var publicIp: String? = null
        for (i in 1..10) {
            val vnicPath = "/20160918/vnicAttachments?compartmentId=${URLEncoder.encode(cid, "UTF-8")}" +
                "&instanceId=$instanceId"
            val vnicResp = ociGetArray(auth, iaasHost, vnicPath)
            if (vnicResp.length() > 0) {
                val vnicId = vnicResp.getJSONObject(0).getString("vnicId")
                val vnicGet = ociGet(auth, iaasHost, "/20160918/vnics/$vnicId")
                publicIp = vnicGet.optString("publicIp", "").takeIf { it.isNotBlank() }
                if (publicIp != null) break
            }
            kotlinx.coroutines.delay(10_000)
        }
        if (publicIp == null) {
            throw Exception("Failed to get public IP")
        }

        emit(Phase.VM_LAUNCH, Status.SUCCESS, "Instance running, public IP: $publicIp")
        return publicIp
    }


    // --- Phase 6: SSH + WireGuard ---

    private suspend fun setupWireGuard(
        auth: AuthResult,
        homeRegion: String,
        publicIp: String,
        sshPrivateKey: String,
        clientKeys: WireGuardClientKeys,
        friendInvitePeers: List<FriendInvitePeer>,
    ): ProvisionResult {
        val port = 51820

        // Phase 5: Wait for SSH (OCI reports RUNNING before cloud-init/sshd are ready)
        emit(Phase.WAIT_SSH, Status.RUNNING, "Waiting for SSH...")
        emit(Phase.WAIT_SSH, Status.RUNNING, "Phase region trace: provisioningRegion=$homeRegion publicIp=$publicIp")
        val jsch = JSch()
        val keyTempFile = java.io.File(context.cacheDir, "ssh_key_${System.currentTimeMillis()}")
        try {
            keyTempFile.writeText(sshPrivateKey)
            jsch.addIdentity(keyTempFile.absolutePath)

            val sshConnection = SshConnection(jsch = jsch, username = "ubuntu", host = publicIp)
        val sshTimeoutMs = 10 * 60 * 1000L
        val sshStart = System.currentTimeMillis()
        var sshAttempt = 0
        var sshLastError: Exception? = null
        while (System.currentTimeMillis() - sshStart < sshTimeoutMs) {
            sshAttempt++
            try {
                emit(Phase.WAIT_SSH, Status.RUNNING, "SSH attempt $sshAttempt to ubuntu@$publicIp...")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    sshConnection.session?.disconnect()
                    sshConnection.session = newSshSession(sshConnection)
                    sshConnection.session!!.connect(15000)
                }
                break
            } catch (e: Exception) {
                sshLastError = e
                sshConnection.session?.disconnect()
                sshConnection.session = null
                emit(Phase.WAIT_SSH, Status.RUNNING, "SSH not ready: ${e.javaClass.simpleName}: ${e.message}")
                kotlinx.coroutines.delay(10_000)
            }
        }
        if (sshConnection.session == null || sshConnection.session?.isConnected != true) {
            keyTempFile.delete()
            throw Exception("SSH did not become ready after ${sshTimeoutMs / 1000}s. Last error: ${sshLastError?.javaClass?.simpleName}: ${sshLastError?.message}")
        }
        emit(Phase.WAIT_SSH, Status.SUCCESS, "SSH connected")
        waitForSshCommandReady(sshConnection)

        // Phase 6: WireGuard
        emit(Phase.WIREGUARD, Status.RUNNING, "Installing WireGuard...")
        emit(
            Phase.WIREGUARD,
            Status.RUNNING,
            "Phase region trace: provisioningRegion=$homeRegion publicIp=$publicIp setupHost=ubuntu@$publicIp",
        )
        if (!installWireGuardTools(sshConnection)) {
            sshConnection.session?.disconnect()
            keyTempFile.delete()
            throw Exception(
                "WireGuard tools could not be installed on the Oracle VM. " +
                    "The VM was created and SSH worked, but apt could not find or install WireGuard tools. " +
                    "Check the setup log for apt update, package policy, and sources output.",
            )
        }

        emit(Phase.WIREGUARD, Status.RUNNING, "Generating server keys...")
        val keygenCmd = "sudo sh -c \"umask 077; wg genkey > /etc/wireguard/server.key; " +
            "wg pubkey < /etc/wireguard/server.key > /etc/wireguard/server.pub\""
        val keygenResult = runSshCommand(sshConnection, keygenCmd, label = "generate WireGuard server keys")
        if (keygenResult.exitCode != 0) {
            sshConnection.session?.disconnect()
            keyTempFile.delete()
            throw Exception("keygen failed: ${keygenResult.stderr.takeLast(200)}")
        }

        emit(Phase.WIREGUARD, Status.RUNNING, "Configuring WireGuard...")
        // Write setup script to VM via cat heredoc
        val writeCmd = "cat > /tmp/setup-wg.sh << 'ENDOFSCRIPT'\n" + SETUP_WG_SCRIPT + "\nENDOFSCRIPT"
        val writeResult = runSshCommand(sshConnection, writeCmd, label = "write WireGuard setup script")
        if (writeResult.exitCode != 0) {
            sshConnection.session?.disconnect()
            keyTempFile.delete()
            throw Exception("Failed to write setup script: ${writeResult.stderr}")
        }

        // Fix line endings and run
        runSshCommand(sshConnection, "sed -i 's/\\r\$//' /tmp/setup-wg.sh", label = "normalize WireGuard setup script")
        val friendPeerEnv = friendInvitePeers
            .sortedBy { it.slotIndex }
            .joinToString(" ") { peer ->
                "FRIEND_${peer.slotIndex}_PUBLIC_KEY='${peer.keys.publicKey}'"
            }
        val runResult = runSshCommand(
            sshConnection,
            "CLIENT_PUBLIC_KEY='${clientKeys.publicKey}' $friendPeerEnv bash /tmp/setup-wg.sh",
            label = "run WireGuard setup script",
            maxAttempts = 1,
        )
        if (runResult.exitCode != 0) {
            sshConnection.session?.disconnect()
            keyTempFile.delete()
            throw Exception(
                "WireGuard setup failed because the SSH session dropped during VM setup. " +
                    "The VM was created and SSH became reachable, but setup commands could not complete. " +
                    runResult.stderr.takeLast(300),
            )
        }

        val stdout = runResult.stdout
        var serverPub: String? = null
        var serverPeerPub: String? = null
        for (line in stdout.split("\n")) {
            if (line.startsWith("SERVER_PUBLIC_KEY=")) {
                serverPub = line.substringAfter("=").trim()
            } else if (line.startsWith("SERVER_PEER_PUBLIC_KEY=")) {
                serverPeerPub = line.substringAfter("=").trim()
            }
        }

        sshConnection.session?.disconnect()
        keyTempFile.delete()

        if (serverPub == null || serverPeerPub == null) {
            throw Exception("WireGuard key extraction failed")
        }
        if (serverPeerPub != clientKeys.publicKey) {
            throw Exception("WireGuard peer key mismatch: server installed a different client public key")
        }

        val clientConfig = "[Interface]\nPrivateKey = ${clientKeys.privateKey}\n" +
            "Address = 10.66.66.2/32\nDNS = 1.1.1.1\n\n" +
            "[Peer]\nPublicKey = $serverPub\n" +
            "Endpoint = $publicIp:$port\n" +
            "AllowedIPs = 0.0.0.0/0\nPersistentKeepalive = 25\n"
        val inviteProfiles = friendInvitePeers
            .sortedBy { it.slotIndex }
            .map { peer ->
                InvitePeerProvisionResult(
                    slotIndex = peer.slotIndex,
                    tunnelIp = peer.tunnelIp,
                    clientPublicKey = peer.keys.publicKey,
                    clientConfig = "[Interface]\nPrivateKey = ${peer.keys.privateKey}\n" +
                        "Address = ${peer.tunnelIp}/32\nDNS = 1.1.1.1\n\n" +
                        "[Peer]\nPublicKey = $serverPub\n" +
                        "Endpoint = $publicIp:$port\n" +
                        "AllowedIPs = 0.0.0.0/0\nPersistentKeepalive = 25\n",
                )
            }

        emit(Phase.WIREGUARD, Status.SUCCESS, "WireGuard configured")
            return ProvisionResult(
                publicIp = publicIp,
                wireGuardPort = port,
                clientConfig = clientConfig,
                clientPublicKey = clientKeys.publicKey,
                serverPublicKey = serverPub,
                serverPeerPublicKey = serverPeerPub,
                inviteProfiles = inviteProfiles,
                sshUsername = "ubuntu",
                sshPrivateKey = sshPrivateKey,
            )
        } finally {
            keyTempFile.delete()
        }
    }

    private data class RemoteCommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val commandStarted: Boolean = false,
        val channelClosedAfterExit: Boolean = false,
        val sessionConnectedAfterExit: Boolean = false,
    )

    private data class SshConnection(
        val jsch: JSch,
        val username: String,
        val host: String,
        var session: Session? = null,
    )

    private fun newSshSession(connection: SshConnection): Session {
        val session = connection.jsch.getSession(connection.username, connection.host, 22)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("UserKnownHostsFile", "/dev/null")
        session.setConfig("PreferredAuthentications", "publickey")
        session.timeout = 15000
        session.setServerAliveInterval(15_000)
        session.setServerAliveCountMax(4)
        return session
    }

    private suspend fun reconnectSsh(connection: SshConnection, reason: String) {
        emit(Phase.WAIT_SSH, Status.RUNNING, "Reconnecting SSH after $reason...")
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            connection.session?.disconnect()
            connection.session = newSshSession(connection)
            connection.session!!.connect(15000)
        }
    }

    private suspend fun waitForSshCommandReady(connection: SshConnection) {
        val maxAttempts = 12
        repeat(maxAttempts) { index ->
            val attempt = index + 1
            emit(
                Phase.WAIT_SSH,
                Status.RUNNING,
                "SSH command readiness attempt $attempt/$maxAttempts; sessionConnected=${connection.session?.isConnected == true}",
            )
            val result = runSshCommand(
                connection = connection,
                command = "echo ZERO_VPN_SSH_READY && whoami && uname -a",
                label = "SSH readiness",
                maxAttempts = 1,
            )
            val hasReadyMarker = result.stdout.contains("ZERO_VPN_SSH_READY")
            emit(
                Phase.WAIT_SSH,
                if (result.exitCode == 0 && hasReadyMarker) Status.RUNNING else Status.WARNING,
                "SSH readiness commandStarted=${result.commandStarted} exitStatus=${result.exitCode} " +
                    "stdoutContainsReadyMarker=$hasReadyMarker channelClosedAfterExit=${result.channelClosedAfterExit} " +
                    "sessionConnectedAfterExit=${result.sessionConnectedAfterExit}",
            )
            if (result.exitCode == 0 && hasReadyMarker) {
                emit(Phase.WAIT_SSH, Status.SUCCESS, "SSH command execution ready")
                return
            }
            if (result.exitCode == 0) {
                emit(Phase.WAIT_SSH, Status.WARNING, "SSH readiness command exited 0 but did not print ZERO_VPN_SSH_READY. Retrying...")
            } else {
                emit(Phase.WAIT_SSH, Status.RUNNING, "SSH readiness command did not complete successfully. Reconnecting and retrying...")
            }
            connection.session?.disconnect()
            connection.session = null
            kotlinx.coroutines.delay(10_000)
        }
        throw Exception(
            "WireGuard setup failed because the SSH session dropped during VM setup. " +
                "The VM was created and SSH became reachable, but setup commands could not complete.",
        )
    }

    private suspend fun installWireGuardTools(connection: SshConnection): Boolean {
        runLoggedRemoteCommand(connection, "cloud-init wait", "sudo cloud-init status --wait || true")
        val diagnostics = listOf(
            "lsb_release -a || cat /etc/os-release",
            "uname -a",
            "id",
            "ip route",
            "cat /etc/apt/sources.list || true",
            "ls -la /etc/apt/sources.list.d || true",
            "find /etc/apt/sources.list.d -maxdepth 1 -type f -print -exec sed -n '1,160p' {} \\; || true",
            "apt-cache policy || true",
            "apt-cache policy wireguard wireguard-tools || true",
            "apt-cache search '^wireguard' || true",
            "getent hosts archive.ubuntu.com || true",
            "getent hosts security.ubuntu.com || true",
        )
        diagnostics.forEach { command ->
            runLoggedRemoteCommand(connection, "preinstall diagnostic", command)
        }

        val updateCommand = "sudo DEBIAN_FRONTEND=noninteractive apt-get update -y -o Acquire::Retries=3"
        var updateResult = runAptCommandWithRetries(connection, "apt update", updateCommand, attempts = 3)
        runLoggedRemoteCommand(connection, "package policy after update", "apt-cache policy wireguard wireguard-tools || true")
        runLoggedRemoteCommand(connection, "package search after update", "apt-cache search '^wireguard' || true")

        val policyAfterUpdate = runLoggedRemoteCommand(
            connection,
            "wireguard-tools candidate after update",
            "apt-cache policy wireguard-tools || true",
        )
        if (!hasAptCandidate(policyAfterUpdate.stdout) && isUbuntuVm(connection)) {
            emit(Phase.WIREGUARD, Status.WARNING, "wireguard-tools has no apt candidate; enabling Ubuntu universe repository")
            enableUbuntuUniverse(connection)
            updateResult = runAptCommandWithRetries(connection, "apt update after universe", updateCommand, attempts = 3)
            runLoggedRemoteCommand(connection, "package policy after universe", "apt-cache policy wireguard wireguard-tools || true")
            runLoggedRemoteCommand(connection, "package search after universe", "apt-cache search '^wireguard' || true")
        } else if (!hasAptCandidate(policyAfterUpdate.stdout)) {
            emit(Phase.WIREGUARD, Status.WARNING, "wireguard-tools has no apt candidate and VM is not Ubuntu; universe fallback skipped")
        }

        val prereqCommand = "sudo DEBIAN_FRONTEND=noninteractive apt-get install -y " +
            "software-properties-common apt-transport-https ca-certificates curl"
        runAptCommandWithRetries(connection, "apt install prerequisites", prereqCommand, attempts = 2)
        if (isUbuntuVm(connection)) {
            enableUbuntuUniverse(connection)
            runAptCommandWithRetries(connection, "apt update after prerequisite universe", updateCommand, attempts = 2)
        }

        val installWithMeta = "sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends " +
            "wireguard wireguard-tools iptables iproute2 curl ca-certificates"
        var installResult = runAptCommandWithRetries(connection, "apt install wireguard packages", installWithMeta, attempts = 3)
        if (installResult.exitCode != 0) {
            emit(Phase.WIREGUARD, Status.WARNING, "wireguard meta-package install failed; retrying with wireguard-tools only")
            runLoggedRemoteCommand(connection, "package policy before tools-only retry", "apt-cache policy wireguard wireguard-tools || true")
            val installToolsOnly = "sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends " +
                "wireguard-tools iptables iproute2 curl ca-certificates"
            installResult = runAptCommandWithRetries(connection, "apt install wireguard-tools only", installToolsOnly, attempts = 3)
        }

        runLoggedRemoteCommand(connection, "kernel module check", "sudo modprobe wireguard || true")
        runLoggedRemoteCommand(connection, "loaded module check", "lsmod | grep wireguard || true")
        runLoggedRemoteCommand(connection, "verify wg", "command -v wg && wg --version || true")
        runLoggedRemoteCommand(connection, "verify wg-quick", "command -v wg-quick && wg-quick --version || true")
        runLoggedRemoteCommand(connection, "verify iptables", "command -v iptables && iptables --version || true")
        runLoggedRemoteCommand(connection, "verify ip", "command -v ip && ip -V || true")

        val wgCheck = runLoggedRemoteCommand(connection, "required wg check", "command -v wg")
        val wgQuickCheck = runLoggedRemoteCommand(connection, "required wg-quick check", "command -v wg-quick")
        if (installResult.exitCode != 0 || updateResult.exitCode != 0 || wgCheck.exitCode != 0 || wgQuickCheck.exitCode != 0) {
            runLoggedRemoteCommand(connection, "final package policy", "apt-cache policy wireguard wireguard-tools || true")
            emit(Phase.WIREGUARD, Status.ERROR, "WireGuard tools were not installed. See apt source and package policy output above.")
            return false
        }
        return true
    }

    private suspend fun enableUbuntuUniverse(connection: SshConnection) {
        runLoggedRemoteCommand(connection, "Ubuntu release info", ". /etc/os-release; echo \"${'$'}ID ${'$'}VERSION_CODENAME\"")
        runLoggedRemoteCommand(
            connection,
            "enable Ubuntu universe",
            "if command -v add-apt-repository >/dev/null 2>&1; then sudo add-apt-repository -y universe; else echo 'add-apt-repository not available'; exit 1; fi",
        )
    }

    private suspend fun runAptCommandWithRetries(
        connection: SshConnection,
        label: String,
        command: String,
        attempts: Int,
    ): RemoteCommandResult {
        var result = RemoteCommandResult(255, "", "$label was not attempted")
        repeat(attempts) { index ->
            val attempt = index + 1
            emit(Phase.WIREGUARD, Status.RUNNING, "$label attempt $attempt/$attempts")
            result = runLoggedRemoteCommand(connection, "$label attempt $attempt", command)
            if (result.exitCode == 0) return result
            if (attempt < attempts) {
                kotlinx.coroutines.delay(10_000)
            }
        }
        return result
    }

    private fun hasAptCandidate(policyOutput: String): Boolean =
        policyOutput.lineSequence().any { line ->
            val trimmed = line.trim()
            trimmed.startsWith("Candidate:") &&
                !trimmed.endsWith("(none)") &&
                !trimmed.endsWith("none")
        }

    private suspend fun isUbuntuVm(connection: SshConnection): Boolean {
        val result = runLoggedRemoteCommand(
            connection,
            "detect Ubuntu",
            "grep -E '^ID=ubuntu$|^ID=\"ubuntu\"$' /etc/os-release",
        )
        return result.exitCode == 0
    }

    private suspend fun runLoggedRemoteCommand(
        connection: SshConnection,
        label: String,
        command: String,
    ): RemoteCommandResult {
        emit(Phase.WIREGUARD, Status.RUNNING, "VM $label command: $command")
        val result = runSshCommand(connection, command, label, maxAttempts = 1)
        emit(Phase.WIREGUARD, if (result.exitCode == 0) Status.RUNNING else Status.WARNING, "VM $label exit=${result.exitCode}")
        emitRemoteOutput(label, "stdout", result.stdout)
        emitRemoteOutput(label, "stderr", result.stderr)
        return result
    }

    private suspend fun emitRemoteOutput(label: String, stream: String, output: String) {
        val lines = output.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(40)
        lines.forEach { line ->
            emit(Phase.WIREGUARD, Status.RUNNING, "VM $label $stream: $line")
        }
    }

    private suspend fun runSshCommand(
        connection: SshConnection,
        command: String,
        label: String,
        maxAttempts: Int = 3,
    ): RemoteCommandResult {
        var lastError: Exception? = null
        repeat(maxAttempts) { index ->
            val attempt = index + 1
            try {
                if (connection.session?.isConnected != true) {
                    reconnectSsh(connection, "$label command start")
                }
                emit(
                    Phase.WIREGUARD,
                    Status.RUNNING,
                    "SSH command '$label' attempt $attempt/$maxAttempts; sessionConnected=${connection.session?.isConnected == true}",
                )
                return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    sshExec(connection.session!!, command).also { result ->
                        val status = if (result.exitCode == 0) Status.RUNNING else Status.WARNING
                        emit(
                            Phase.WIREGUARD,
                            status,
                            "SSH command '$label' completed: commandStarted=${result.commandStarted} " +
                                "exitStatus=${result.exitCode} channelClosedAfterExit=${result.channelClosedAfterExit} " +
                                "sessionConnectedAfterExit=${result.sessionConnectedAfterExit}",
                        )
                        if (result.exitCode == 0) {
                            emit(Phase.WIREGUARD, Status.RUNNING, "SSH command '$label' succeeded")
                        }
                    }
                }
            } catch (e: Exception) {
                lastError = e
                emit(
                    Phase.WIREGUARD,
                    Status.WARNING,
                    "SSH command '$label' failed: ${e.javaClass.simpleName}: ${e.message}",
                )
                connection.session?.disconnect()
                connection.session = null
                if (attempt < maxAttempts) {
                    emit(Phase.WIREGUARD, Status.RUNNING, "SSH command '$label' did not complete; reconnecting and retrying...")
                    kotlinx.coroutines.delay(5_000)
                }
            }
        }
        return RemoteCommandResult(
            exitCode = 255,
            stdout = "",
            stderr = "${lastError?.javaClass?.simpleName ?: "SshCommandFailed"}: ${lastError?.message ?: "unknown SSH command failure"}",
            commandStarted = false,
        )
    }

    // --- Full provisioning pipeline ---

    suspend fun provision(
        auth: AuthResult,
        preflight: PreflightResult,
        privateChatRequested: Boolean = false,
        onApiKeyUploaded: ((String) -> Unit)? = null,
        onLaunchContextReady: ((ResourceIds, String) -> Unit)? = null,
    ): Pair<ResourceIds, ProvisionResult> {
        val homeRegion = preflight.homeRegion

        // Upload API key and verify activation
        try {
            uploadApiKey(auth, homeRegion, onApiKeyUploaded)
        } catch (e: ApiKeyVerificationException) {
            // Verification failed — distinct from upload failure
            when (e.stage) {
                "KEY_REGISTRATION_PENDING" -> emit(Phase.API_KEY, Status.ERROR, "Key registration pending: ${e.message}")
                "API_KEY_SIGNATURE_VALIDATION_FAILED" -> emit(Phase.API_KEY, Status.ERROR, "API-key signature validation failed: ${e.message}")
                else -> emit(Phase.API_KEY, Status.ERROR, "API-key verification failed: ${e.message}")
            }
            throw e
        } catch (e: Exception) {
            emit(Phase.API_KEY, Status.ERROR, "Upload failed: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }

        // Generate SSH keypair for VM access
        emit(Phase.NETWORK, Status.RUNNING, "Generating VM SSH keypair...")
        val sshKeyPair = try {
            generateSshKeyPair()
        } catch (e: Exception) {
            emit(Phase.NETWORK, Status.ERROR, "SSH key generation failed: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        val sshPublicKey = sshKeyPair.first
        val sshPrivateKey = sshKeyPair.second
        val wireGuardClientKeys = generateWireGuardClientKeys()
        val friendInvitePeers = listOf(
            FriendInvitePeer(slotIndex = 1, tunnelIp = "10.66.66.3", keys = generateWireGuardClientKeys()),
            FriendInvitePeer(slotIndex = 2, tunnelIp = "10.66.66.4", keys = generateWireGuardClientKeys()),
            FriendInvitePeer(slotIndex = 3, tunnelIp = "10.66.66.5", keys = generateWireGuardClientKeys()),
        )

        // Create network
        val rids = createNetwork(auth, homeRegion)

        // Launch VM
        onLaunchContextReady?.invoke(rids, sshPublicKey)
        val publicIp = launchVm(auth, homeRegion, rids, sshPublicKey, privateChatRequested)

        // Setup WireGuard via SSH
        val provisionResult = setupWireGuard(
            auth = auth,
            homeRegion = homeRegion,
            publicIp = publicIp,
            sshPrivateKey = sshPrivateKey,
            clientKeys = wireGuardClientKeys,
            friendInvitePeers = friendInvitePeers,
        )

        // Done
        emit(Phase.DONE, Status.SUCCESS, "Exit created: ${provisionResult.publicIp}:${provisionResult.wireGuardPort}")

        return rids to provisionResult
    }

    // --- Destroy ---

    suspend fun destroy(
        rids: ResourceIds,
        auth: AuthResult,
        homeRegion: String,
        apiKeyUserOcid: String?,
        apiKeyFingerprint: String?,
    ): Boolean {
        val iaasHost = OciEndpoints.iaasHost(homeRegion)
        val idHost = OciEndpoints.identityHost(homeRegion)
        emit(
            Phase.DONE,
            Status.RUNNING,
            "Phase region trace: cleanupRegion=$homeRegion storedExitRegion=$homeRegion iaasHost=$iaasHost",
        )

        // Terminate instance
        if (rids.instanceId != null) {
            emit(Phase.DONE, Status.RUNNING, "Terminating instance...")
            val instPath = "/20160918/instances/${rids.instanceId}?preserveBootVolume=false"
            ociDelete(auth, iaasHost, instPath)
            for (i in 1..12) {
                kotlinx.coroutines.delay(10_000)
                try {
                    val resp = ociGet(auth, iaasHost, "/20160918/instances/${rids.instanceId}")
                    val state = resp.optString("lifecycleState", "")
                    if (state == "TERMINATED") break
                } catch (e: Exception) { break }
            }
            kotlinx.coroutines.delay(5000)
        }

        // Clear routes
        if (rids.vcnId != null) {
            emit(Phase.DONE, Status.RUNNING, "Clearing routes...")
            try {
                val vcnResp = ociGet(auth, iaasHost, "/20160918/vcns/${rids.vcnId}")
                val rtId = vcnResp.getString("defaultRouteTableId")
                val rtBody = JSONObject().put("routeRules", JSONArray()).toString()
                ociPut(auth, iaasHost, "/20160918/routeTables/$rtId", rtBody)
            } catch (e: Exception) { }
        }

        // Delete IGW
        if (rids.igwId != null) {
            emit(Phase.DONE, Status.RUNNING, "Deleting internet gateway...")
            try { ociDelete(auth, iaasHost, "/20160918/internetGateways/${rids.igwId}") } catch (e: Exception) { }
        }

        // Delete subnet
        if (rids.subnetId != null) {
            emit(Phase.DONE, Status.RUNNING, "Deleting subnet...")
            try {
                ociDelete(auth, iaasHost, "/20160918/subnets/${rids.subnetId}")
                waitForState(auth, iaasHost, "/20160918/subnets/${rids.subnetId}", "TERMINATED", maxAttempts = 15)
            } catch (e: Exception) { }
        }

        // Delete security list
        if (rids.slId != null) {
            emit(Phase.DONE, Status.RUNNING, "Deleting security list...")
            try { ociDelete(auth, iaasHost, "/20160918/securityLists/${rids.slId}") } catch (e: Exception) { }
        }

        // Delete VCN
        if (rids.vcnId != null) {
            emit(Phase.DONE, Status.RUNNING, "Deleting VCN...")
            try { ociDelete(auth, iaasHost, "/20160918/vcns/${rids.vcnId}") } catch (e: Exception) { }
        }

        deleteApiSigningKey(
            auth = auth,
            idHost = idHost,
            apiKeyUserOcid = apiKeyUserOcid,
            apiKeyFingerprint = apiKeyFingerprint,
        )

        emit(Phase.DONE, Status.SUCCESS, "Resources destroyed")
        return true
    }

    private suspend fun deleteApiSigningKey(
        auth: AuthResult,
        idHost: String,
        apiKeyUserOcid: String?,
        apiKeyFingerprint: String?,
    ) {
        emit(Phase.DONE, Status.RUNNING, "Deleting API signing key...")

        if (apiKeyUserOcid.isNullOrBlank()) {
            emit(Phase.DONE, Status.ERROR, "API signing key deletion skipped: missing user OCID")
            throw Exception("API signing key deletion skipped: missing user OCID")
        }
        if (apiKeyFingerprint.isNullOrBlank()) {
            emit(Phase.DONE, Status.ERROR, "API signing key deletion skipped: missing fingerprint")
            throw Exception("API signing key deletion skipped: missing fingerprint")
        }
        if (apiKeyUserOcid != auth.userOcid) {
            val message = "API signing key deletion skipped: authenticated user does not match saved key owner"
            emit(Phase.DONE, Status.ERROR, message)
            throw Exception(message)
        }

        val keyPath = "/20160918/users/$apiKeyUserOcid/apiKeys"
        val keysBefore = ociGetArray(auth, idHost, keyPath)
        var found = false
        for (i in 0 until keysBefore.length()) {
            val key = keysBefore.getJSONObject(i)
            if (key.optString("fingerprint") == apiKeyFingerprint) {
                found = true
                break
            }
        }
        if (!found) {
            emit(Phase.DONE, Status.SUCCESS, "API signing key already absent")
            return
        }

        val deletePath = "$keyPath/$apiKeyFingerprint"
        val deleteResult = ociDelete(auth, idHost, deletePath)
        if (deleteResult.code == 404) {
            emit(Phase.DONE, Status.SUCCESS, "API signing key already absent")
            return
        }
        emit(Phase.DONE, Status.RUNNING, "API signing key delete returned HTTP ${deleteResult.code}")

        val keysAfter = ociGetArray(auth, idHost, keyPath)
        for (i in 0 until keysAfter.length()) {
            val key = keysAfter.getJSONObject(i)
            if (key.optString("fingerprint") == apiKeyFingerprint) {
                val message = "API signing key deletion unverified: key still listed after delete"
                emit(Phase.DONE, Status.ERROR, message)
                throw Exception(message)
            }
        }
        emit(Phase.DONE, Status.SUCCESS, "API signing key deleted and verified absent")
    }

    private data class DeleteResult(val code: Int, val body: String)

    // --- OCI HTTP helpers ---

    private fun buildSignedPostRequest(auth: AuthResult, host: String, path: String, body: String): Request {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val contentSha256 = java.util.Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(bodyBytes)
        )
        val (authHeader, dateStr, _) = OciRequestSigner.buildAuthHeader(
            tenancyOcid = auth.tenancyOcid,
            userOcid = auth.userOcid,
            fingerprint = auth.fingerprint,
            privateKey = auth.privateKey,
            method = "POST",
            path = path,
            host = host,
            useSecurityToken = auth.usesSecurityToken,
            securityToken = auth.activeSecurityToken,
            body = body,
        )
        return Request.Builder()
            .url("https://$host$path")
            .header("date", dateStr)
            .header("Content-Type", "application/json")
            .header("x-content-sha256", contentSha256)
            .header("Authorization", authHeader)
            .post(bodyBytes.toRequestBody(jsonMedia))
            .build()
    }

    private suspend fun ociPostWithResponse(auth: AuthResult, host: String, path: String, body: String): OciPostResponse {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val req = buildSignedPostRequest(auth, host, path, body)
            val resp = httpClient.newCall(req).execute()
            OciPostResponse(
                code = resp.code,
                body = resp.body?.string() ?: "",
                isSuccessful = resp.isSuccessful,
                retryAfterSeconds = parseRetryAfterSeconds(resp.header("Retry-After")),
            )
        }
    }

    private suspend fun ociPost(auth: AuthResult, host: String, path: String, body: String): JSONObject {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val req = buildSignedPostRequest(auth, host, path, body)
            val resp = httpClient.newCall(req).execute()
            val respBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw Exception("POST $path failed: ${resp.code} $respBody")
            }
            JSONObject(respBody)
        }
    }

    private suspend fun ociGetArray(auth: AuthResult, host: String, path: String): JSONArray {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val (authHeader, dateStr, _) = OciRequestSigner.buildAuthHeader(
                    tenancyOcid = auth.tenancyOcid,
                    userOcid = auth.userOcid,
                    fingerprint = auth.fingerprint,
                    privateKey = auth.privateKey,
                    method = "GET",
                    path = path,
                    host = host,
                    useSecurityToken = auth.usesSecurityToken, securityToken = auth.activeSecurityToken, )
            val req = Request.Builder()
                .url("https://$host$path")
                .header("date", dateStr)
                .header("Authorization", authHeader)
                .get()
                .build()
            val resp = httpClient.newCall(req).execute()
            val respBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw Exception("GET $path failed: ${resp.code} $respBody")
            }
            JSONArray(respBody)
        }
    }

    private suspend fun ociGet(auth: AuthResult, host: String, path: String): JSONObject {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val (authHeader, dateStr, _) = OciRequestSigner.buildAuthHeader(
                    tenancyOcid = auth.tenancyOcid,
                    userOcid = auth.userOcid,
                    fingerprint = auth.fingerprint,
                    privateKey = auth.privateKey,
                    method = "GET",
                    path = path,
                    host = host,
                    useSecurityToken = auth.usesSecurityToken, securityToken = auth.activeSecurityToken, )
            val req = Request.Builder()
                .url("https://$host$path")
                .header("date", dateStr)
                .header("Authorization", authHeader)
                .get()
                .build()
            val resp = httpClient.newCall(req).execute()
            val respBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw Exception("GET $path failed: ${resp.code} $respBody")
            }
            JSONObject(respBody)
        }
    }

    private suspend fun ociPut(auth: AuthResult, host: String, path: String, body: String) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            val contentSha256 = java.util.Base64.getEncoder().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(bodyBytes)
            )
            val (authHeader, dateStr, _) = OciRequestSigner.buildAuthHeader(
                    tenancyOcid = auth.tenancyOcid,
                    userOcid = auth.userOcid,
                    fingerprint = auth.fingerprint,
                    privateKey = auth.privateKey,
                    method = "PUT",
                    path = path,
                    host = host,
                    useSecurityToken = auth.usesSecurityToken, securityToken = auth.activeSecurityToken,
                    body = body, )
            val req = Request.Builder()
                .url("https://$host$path")
                .header("date", dateStr)
                .header("Content-Type", "application/json")
                .header("x-content-sha256", contentSha256)
                .header("Authorization", authHeader)
                .put(body.toByteArray(Charsets.UTF_8).toRequestBody(jsonMedia))
                .build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                val respBody = resp.body?.string() ?: ""
                throw Exception("PUT $path failed: ${resp.code} $respBody")
            }
        }
    }

    private suspend fun ociDelete(auth: AuthResult, host: String, path: String): DeleteResult {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val (authHeader, dateStr, _) = OciRequestSigner.buildAuthHeader(
                    tenancyOcid = auth.tenancyOcid,
                    userOcid = auth.userOcid,
                    fingerprint = auth.fingerprint,
                    privateKey = auth.privateKey,
                    method = "DELETE",
                    path = path,
                    host = host,
                    useSecurityToken = auth.usesSecurityToken, securityToken = auth.activeSecurityToken, )
            val req = Request.Builder()
                .url("https://$host$path")
                .header("date", dateStr)
                .header("Authorization", authHeader)
                .delete()
                .build()
            val resp = httpClient.newCall(req).execute()
            val respBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful && resp.code != 404) {
                throw Exception("DELETE $path failed: ${resp.code} $respBody")
            }
            DeleteResult(resp.code, respBody)
        }
    }

    // --- Wait for resource state ---

    private suspend fun waitForState(auth: AuthResult, host: String, path: String, targetState: String, maxAttempts: Int = 30) {
        for (i in 1..maxAttempts) {
            kotlinx.coroutines.delay(2000)
            try {
                val resp = ociGet(auth, host, path)
                val state = resp.optString("lifecycleState", "")
                if (state == targetState) return
                if (state in listOf("TERMINATED", "FAILED", "FAULTY")) return
            } catch (e: Exception) {
                // Resource may not be queryable yet
            }
        }
    }

    // --- SSH exec helper ---

    private fun sshExec(session: Session, command: String): RemoteCommandResult {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val bais = java.io.ByteArrayOutputStream()
        val baes = java.io.ByteArrayOutputStream()
        val outStream = channel.getInputStream()
        val errStream = channel.getErrStream()
        var commandStarted = false
        return try {
            channel.connect(30000)
            commandStarted = true
            val buf = ByteArray(4096)
            while (!channel.isClosed || outStream.available() > 0 || errStream.available() > 0) {
                while (outStream.available() > 0) {
                    val n = outStream.read(buf)
                    if (n > 0) bais.write(buf, 0, n)
                }
                while (errStream.available() > 0) {
                    val n = errStream.read(buf)
                    if (n > 0) baes.write(buf, 0, n)
                }
                if (channel.isClosed && outStream.available() == 0 && errStream.available() == 0) break
                Thread.sleep(50)
            }
            while (outStream.available() > 0) {
                val n = outStream.read(buf)
                if (n > 0) bais.write(buf, 0, n) else break
            }
            while (errStream.available() > 0) {
                val n = errStream.read(buf)
                if (n > 0) baes.write(buf, 0, n) else break
            }
            RemoteCommandResult(
                exitCode = channel.exitStatus,
                stdout = bais.toString(),
                stderr = baes.toString(),
                commandStarted = commandStarted,
                channelClosedAfterExit = channel.isClosed,
                sessionConnectedAfterExit = session.isConnected,
            )
        } finally {
            channel.disconnect()
        }
    }

    // --- Ed25519 SSH key generation ---

    private fun generateSshKeyPair(): Pair<String, String> {
        val jsch = JSch()
        val kp = com.jcraft.jsch.KeyPair.genKeyPair(jsch, com.jcraft.jsch.KeyPair.RSA)
        val baos = java.io.ByteArrayOutputStream()
        kp.writePrivateKey(baos)
        val privateKey = baos.toString()
        val baos2 = java.io.ByteArrayOutputStream()
        kp.writePublicKey(baos2, "zerovpn-android")
        val publicKeyLine = baos2.toString().trim()
        kp.dispose()
        return publicKeyLine to privateKey
    }

    private fun generateWireGuardClientKeys(): WireGuardClientKeys {
        val keyPair = KeyPair()
        return WireGuardClientKeys(
            privateKey = keyPair.privateKey.toBase64(),
            publicKey = keyPair.publicKey.toBase64(),
        )
    }

    // --- Date helper ---

    private fun currentDateRfc1123(): String {
        return java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC))
    }

    companion object {
        fun classifyLaunchFailure(error: Throwable): VmLaunchFailure? {
            var current: Throwable? = error
            while (current != null) {
                if (current is VmLaunchFailureException) return current.failure
                current = current.cause
            }
            return null
        }
        /**
         * The setup-wg.sh script content (embedded as a string constant).
         * Same as D:/dev/zero-vpn/harness/setup-wg.sh
         * Uses ${'$'} to produce literal $ in the bash script (Kotlin string template escaping).
         */
        val SETUP_WG_SCRIPT = """
#!/bin/bash
set -e

if [ -z "${'$'}CLIENT_PUBLIC_KEY" ]; then
  echo "ERROR: CLIENT_PUBLIC_KEY is required" >&2
  exit 1
fi
if [ -z "${'$'}FRIEND_1_PUBLIC_KEY" ] || [ -z "${'$'}FRIEND_2_PUBLIC_KEY" ] || [ -z "${'$'}FRIEND_3_PUBLIC_KEY" ]; then
  echo "ERROR: FRIEND_1_PUBLIC_KEY, FRIEND_2_PUBLIC_KEY, and FRIEND_3_PUBLIC_KEY are required" >&2
  exit 1
fi

PUBLIC_IF=${'$'}(ip route show default | awk '{print ${'$'}5; exit}')
if [ -z "${'$'}PUBLIC_IF" ]; then
  echo "ERROR: Could not detect default route interface" >&2
  exit 1
fi
echo "PUBLIC_INTERFACE=${'$'}PUBLIC_IF"

# Get server private key
SERVER_KEY=${'$'}(sudo cat /etc/wireguard/server.key)

# Write wg0.conf
sudo bash -c "cat > /etc/wireguard/wg0.conf << EOF
[Interface]
PrivateKey = ${'$'}SERVER_KEY
Address = 10.66.66.1/24
ListenPort = 51820
PostUp = iptables -D INPUT -p udp --dport 51820 -j ACCEPT 2>/dev/null || true; iptables -D FORWARD -i wg0 -o ${'$'}PUBLIC_IF -j ACCEPT 2>/dev/null || true; iptables -D FORWARD -i ${'$'}PUBLIC_IF -o wg0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || true; iptables -t nat -D POSTROUTING -s 10.66.66.0/24 -o ${'$'}PUBLIC_IF -j MASQUERADE 2>/dev/null || true; iptables -I INPUT 1 -p udp --dport 51820 -j ACCEPT; iptables -I FORWARD 1 -i wg0 -o ${'$'}PUBLIC_IF -j ACCEPT; iptables -I FORWARD 2 -i ${'$'}PUBLIC_IF -o wg0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT; iptables -t nat -A POSTROUTING -s 10.66.66.0/24 -o ${'$'}PUBLIC_IF -j MASQUERADE
PostDown = iptables -D INPUT -p udp --dport 51820 -j ACCEPT 2>/dev/null || true; iptables -D FORWARD -i wg0 -o ${'$'}PUBLIC_IF -j ACCEPT 2>/dev/null || true; iptables -D FORWARD -i ${'$'}PUBLIC_IF -o wg0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || true; iptables -t nat -D POSTROUTING -s 10.66.66.0/24 -o ${'$'}PUBLIC_IF -j MASQUERADE 2>/dev/null || true

[Peer]
PublicKey = ${'$'}CLIENT_PUBLIC_KEY
AllowedIPs = 10.66.66.2/32

[Peer]
PublicKey = ${'$'}FRIEND_1_PUBLIC_KEY
AllowedIPs = 10.66.66.3/32

[Peer]
PublicKey = ${'$'}FRIEND_2_PUBLIC_KEY
AllowedIPs = 10.66.66.4/32

[Peer]
PublicKey = ${'$'}FRIEND_3_PUBLIC_KEY
AllowedIPs = 10.66.66.5/32
EOF"
sudo chmod 600 /etc/wireguard/wg0.conf

# Enable IP forwarding
sudo sysctl -w net.ipv4.ip_forward=1
echo 'net.ipv4.ip_forward=1' | sudo tee /etc/sysctl.d/99-zerovpn-forward.conf
sudo sysctl --system

# Start WireGuard from the persisted config, including the peer.
sudo systemctl enable wg-quick@wg0
sudo systemctl restart wg-quick@wg0

# Get server public key
SERVER_PUB=${'$'}(sudo cat /etc/wireguard/server.pub)

# Output
echo "SERVER_PUBLIC_KEY=${'$'}SERVER_PUB"
echo "SERVER_PEER_PUBLIC_KEY=${'$'}CLIENT_PUBLIC_KEY"
echo "---"
sudo wg show
""".trimIndent()
    }
}
