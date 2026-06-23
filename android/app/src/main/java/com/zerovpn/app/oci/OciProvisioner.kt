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

/**
 * Real OCI provisioner — ports the Python state_machine.py to Kotlin.
 *
 * Flow: browser auth → preflight → API key upload → network → VM → SSH/WireGuard → done
 *
 * All secrets (private keys, tokens, client configs) are kept in memory only.
 * No secrets are written to disk, SharedPreferences, or logs.
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
        val selectedRegion: String,
        val tokenRegion: String? = null,
        val tokenRegionSource: String? = null,
    )

    data class PreflightResult(
        val success: Boolean,
        val homeRegion: String,
        val isUkRegion: Boolean,
        val error: String? = null,
        val initialRegion: String,
        val regionDiscoverySource: String,
        val identityHost: String,
        val iaasHost: String,
    )

    data class ProvisionResult(
        val publicIp: String,
        val wireGuardPort: Int,
        val clientConfig: String,
        val clientPublicKey: String,
        val serverPublicKey: String,
        val serverPeerPublicKey: String,
        val sshUsername: String,
        val sshPrivateKey: String,
    )

    private data class WireGuardClientKeys(
        val privateKey: String,
        val publicKey: String,
    )

    data class ResourceIds(
        var vcnId: String? = null,
        var slId: String? = null,
        var subnetId: String? = null,
        var igwId: String? = null,
        var instanceId: String? = null,
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
            selectedRegion = region,
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

    data class RegionDiscoveryResult(
        val success: Boolean,
        val homeRegionId: String? = null,
        val discoverySource: String,
        val identityEndpointUsed: String? = null,
        val subscribedRegions: List<String> = emptyList(),
        val error: String? = null,
        val attemptedHosts: List<String> = emptyList(),
    )

    suspend fun discoverHomeRegion(
        auth: AuthResult,
        preferredRegion: String? = null,
        preferredRegionSource: String = "persisted",
    ): RegionDiscoveryResult {
        emit(Phase.API_KEY, Status.RUNNING, "Discovering Oracle home region...")
        if (isDevMode) {
            emit(Phase.API_KEY, Status.RUNNING, "Token region source: ${auth.tokenRegionSource ?: "none"}")
        }

        val manualRegion = preferredRegion
            ?.takeIf { preferredRegionSource.startsWith("user-selected") }
        val isManualDiscovery = manualRegion != null
        val candidates = buildRegionCandidates(auth, preferredRegion)
        if (isDevMode) {
            emit(Phase.API_KEY, Status.RUNNING, "Region discovery candidates: ${candidates.joinToString(", ")}")
        }

        var sawDnsSuccess = false
        var authFailure: String? = null
        val attemptedHosts = mutableListOf<String>()
        var lastDnsException: Throwable? = null
        for (candidate in candidates) {
            val idHost = OciEndpoints.identityHost(candidate)
            val identityEndpoint = OciEndpoints.identityEndpoint(candidate)
            attemptedHosts += idHost
            if (isDevMode) {
                emit(Phase.API_KEY, Status.RUNNING, "Trying candidate region: $candidate")
                emit(Phase.API_KEY, Status.RUNNING, "Identity host: $idHost")
                emit(Phase.API_KEY, Status.RUNNING, "Identity URL: $identityEndpoint")
                emit(Phase.API_KEY, Status.RUNNING, "Realm/domain suffix: ${OciEndpoints.realm(candidate)}")
                emit(
                    Phase.API_KEY,
                    Status.RUNNING,
                    "Region trace: tokenRegionId=${auth.tokenRegion ?: "none"} manualRegionId=${manualRegion ?: "none"} " +
                        "discoveryCandidateRegionId=$candidate finalIdentityRegionId=pending finalHomeRegionId=pending " +
                        "finalProvisioningRegionId=pending signerRegionId=$candidate identityHost=$idHost " +
                        "identityUrl=$identityEndpoint realm=${OciEndpoints.realm(candidate)}",
                )
            }

            val dnsResult = kotlinx.coroutines.withTimeoutOrNull(2500L) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    OciEndpoints.resolveHost(idHost)
                }
            } ?: Result.failure(java.net.SocketTimeoutException("DNS preflight timed out"))
            if (dnsResult.isFailure) {
                lastDnsException = dnsResult.exceptionOrNull()
                if (isDevMode) {
                    val e = lastDnsException
                    emit(Phase.API_KEY, Status.WARNING, "DNS preflight failed for $candidate: ${e?.javaClass?.simpleName}: ${e?.message}")
                    emit(
                        Phase.API_KEY,
                        Status.WARNING,
                        "DNS attempted host count=${attemptedHosts.size}; hosts=${attemptedHosts.joinToString(", ")}; " +
                            "lastHost=$idHost; lastDnsException=${e?.javaClass?.simpleName}: ${e?.message}; " +
                            "manualRegionSelected=$isManualDiscovery; selectedManualHostAttempted=${manualRegion?.let { OciEndpoints.identityHost(it) in attemptedHosts } ?: false}",
                    )
                }
                if (isManualDiscovery && candidate == manualRegion) {
                    val error = "Could not resolve Oracle Identity endpoint $idHost. " +
                        "Check network/DNS, try mobile data instead of Wi-Fi, temporarily disable Android Private DNS, " +
                        "adblock/firewall/VPN settings, then retry."
                    emit(Phase.API_KEY, Status.ERROR, error)
                    return RegionDiscoveryResult(
                        success = false,
                        discoverySource = preferredRegionSource,
                        error = error,
                        attemptedHosts = attemptedHosts,
                    )
                }
                continue
            }
            sawDnsSuccess = true
            if (isDevMode) emit(Phase.API_KEY, Status.RUNNING, "DNS preflight OK for $candidate")

            val query = queryRegionSubscriptions(auth, candidate)
            if (isDevMode) emit(Phase.API_KEY, Status.RUNNING, "regionSubscriptions HTTP ${query.code} for $candidate")

            if (query.isSuccessful && query.body != null) {
                val subscriptions = try {
                    JSONArray(if (query.body.isBlank()) "[]" else query.body)
                } catch (e: Exception) {
                    if (isDevMode) emit(Phase.API_KEY, Status.WARNING, "Could not parse subscriptions for $candidate: ${e.message}")
                    continue
                }
                val subscribed = mutableListOf<String>()
                var homeRegion = candidate
                for (i in 0 until subscriptions.length()) {
                    val sub = subscriptions.getJSONObject(i)
                    val name = OciRegions.normalizeRegionHint(
                        sub.optString("region_name")
                        .ifBlank { sub.optString("regionName") }
                        .ifBlank { sub.optString("region_key") }
                    ).orEmpty()
                    if (name.isNotBlank()) subscribed += name
                    if (sub.optBoolean("is_home_region", false) || sub.optBoolean("isHomeRegion", false)) {
                        homeRegion = name.ifBlank { candidate }
                    }
                }
                val source = when {
                    preferredRegion != null && candidate == preferredRegion -> preferredRegionSource
                    auth.tokenRegion != null && candidate == auth.tokenRegion -> auth.tokenRegionSource ?: "token-claim"
                    candidate == auth.selectedRegion -> "auth-bootstrap-probe"
                    else -> "probe-regionSubscriptions"
                }
                emit(Phase.API_KEY, Status.SUCCESS, "Discovered Oracle home region: $homeRegion")
                if (isDevMode) {
                    emit(Phase.API_KEY, Status.RUNNING, "Region discovery source: $source")
                    emit(Phase.API_KEY, Status.RUNNING, "Identity endpoint used: $identityEndpoint")
                    emit(Phase.API_KEY, Status.RUNNING, "Subscribed regions: ${subscribed.joinToString(", ").ifBlank { "unknown" }}")
                    emit(
                        Phase.API_KEY,
                        Status.RUNNING,
                        "Region trace: tokenRegionId=${auth.tokenRegion ?: "none"} manualRegionId=${manualRegion ?: "none"} " +
                            "discoveryCandidateRegionId=$candidate finalIdentityRegionId=$candidate finalHomeRegionId=$homeRegion " +
                            "finalProvisioningRegionId=$homeRegion signerRegionId=$homeRegion identityHost=${OciEndpoints.identityHost(homeRegion)} " +
                            "identityUrl=${OciEndpoints.identityEndpoint(homeRegion)} realm=${OciEndpoints.realm(homeRegion)}",
                    )
                }
                return RegionDiscoveryResult(
                    success = true,
                    homeRegionId = homeRegion,
                    discoverySource = source,
                    identityEndpointUsed = identityEndpoint,
                    subscribedRegions = subscribed,
                    attemptedHosts = attemptedHosts,
                )
            }

            if (query.code == 401 || query.code == 403) {
                authFailure = "Oracle login succeeded, but Oracle rejected the session while discovering your home region. Sign in to Oracle Cloud Console first, then retry."
                if (isDevMode) emit(Phase.API_KEY, Status.WARNING, "Auth failure while probing $candidate: HTTP ${query.code}")
            }
        }

        val error = authFailure ?: if (!sawDnsSuccess) {
            val lastHost = attemptedHosts.lastOrNull() ?: "unknown"
            val dnsDetail = lastDnsException?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "unknown"
            if (isDevMode) {
                emit(
                    Phase.API_KEY,
                    Status.ERROR,
                    "DNS attempted host count=${attemptedHosts.size}; hosts=${attemptedHosts.joinToString(", ")}; " +
                        "lastHost=$lastHost; lastDnsException=$dnsDetail; manualRegionSelected=$isManualDiscovery; " +
                        "selectedManualHostAttempted=${manualRegion?.let { OciEndpoints.identityHost(it) in attemptedHosts } ?: false}",
                )
            }
            "Could not resolve Oracle Identity endpoints after trying ${attemptedHosts.size} host(s). " +
                "Last attempted host: $lastHost. Check network/DNS, try mobile data instead of Wi-Fi, " +
                "temporarily disable Android Private DNS, adblock/firewall/VPN settings, then retry."
        } else {
            "Oracle login succeeded, but ZeroVPN could not discover your home region. Choose your Oracle home region manually and retry."
        }
        emit(Phase.API_KEY, Status.ERROR, error)
        return RegionDiscoveryResult(
            success = false,
            discoverySource = "automatic-failed",
            error = error,
            attemptedHosts = attemptedHosts,
        )
    }

    private fun buildRegionCandidates(auth: AuthResult, preferredRegion: String?): List<String> {
        val ordered = mutableListOf<String>()
        fun add(region: String?) {
            val normalized = OciRegions.normalizeRegionHint(region) ?: return
            if (ordered.none { it.equals(normalized, ignoreCase = true) }) ordered += normalized
        }
        add(preferredRegion)
        add(auth.tokenRegion)
        add(auth.selectedRegion)
        OciRegions.common.forEach { add(it.id) }
        return ordered
    }

    private data class RegionSubscriptionsHttpResult(
        val code: Int,
        val body: String?,
        val isSuccessful: Boolean,
        val error: Exception? = null,
    )

    private suspend fun queryRegionSubscriptions(auth: AuthResult, candidateRegion: String): RegionSubscriptionsHttpResult {
        val idHost = OciEndpoints.identityHost(candidateRegion)
        val path = "/20160918/tenancies/${auth.tenancyOcid}/regionSubscriptions"
        val url = "https://$idHost$path"
        val (authHeader, dateStr, _) = OciRequestSigner.buildAuthHeader(
            tenancyOcid = auth.tenancyOcid,
            userOcid = auth.userOcid,
            fingerprint = auth.fingerprint,
            privateKey = auth.privateKey,
            method = "GET",
            path = path,
            host = idHost,
            useSecurityToken = true,
            securityToken = auth.securityToken,
        )
        val request = Request.Builder()
            .url(url)
            .header("date", dateStr)
            .header("Authorization", authHeader)
            .get()
            .build()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val resp = httpClient.newCall(request).execute()
                RegionSubscriptionsHttpResult(
                    code = resp.code,
                    body = resp.body?.string() ?: "",
                    isSuccessful = resp.isSuccessful,
                )
            } catch (e: Exception) {
                if (isDevMode) {
                    emit(Phase.API_KEY, Status.WARNING, "regionSubscriptions failed for $candidateRegion: ${e.javaClass.simpleName}: ${e.message}")
                }
                RegionSubscriptionsHttpResult(0, null, false, e)
            }
        }
    }

    suspend fun preflight(
        auth: AuthResult,
        preferredRegion: String? = null,
        preferredRegionSource: String = "persisted",
    ): PreflightResult {
        val discovery = discoverHomeRegion(auth, preferredRegion, preferredRegionSource)
        if (!discovery.success || discovery.homeRegionId.isNullOrBlank()) {
            return PreflightResult(
                success = false,
                homeRegion = preferredRegion ?: auth.selectedRegion,
                isUkRegion = false,
                error = discovery.error ?: "Oracle login succeeded, but ZeroVPN could not discover your home region.",
                initialRegion = auth.selectedRegion,
                regionDiscoverySource = discovery.discoverySource,
                identityHost = discovery.homeRegionId?.let { OciEndpoints.identityHost(it) } ?: "",
                iaasHost = discovery.homeRegionId?.let { OciEndpoints.iaasHost(it) } ?: "",
            )
        }

        val homeRegion = discovery.homeRegionId
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
                    return PreflightResult(false, homeRegion, isUk, error, auth.selectedRegion, discovery.discoverySource, idHost, iaasHost)
                }
            } else {
                emit(Phase.API_KEY, Status.WARNING, "API key check: HTTP ${keyResult.first}")
            }
        } catch (e: Exception) {
            emit(Phase.API_KEY, Status.WARNING, "API key check failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        emit(Phase.API_KEY, Status.SUCCESS, "Preflight passed")
        return PreflightResult(
            success = true,
            homeRegion = homeRegion,
            isUkRegion = isUk,
            error = null,
            initialRegion = auth.selectedRegion,
            regionDiscoverySource = discovery.discoverySource,
            identityHost = idHost,
            iaasHost = iaasHost,
        )
    }
    // --- Phase 3: API Key Upload ---

    private suspend fun uploadApiKey(auth: AuthResult, homeRegion: String): String {
        emit(Phase.API_KEY, Status.RUNNING, "Uploading API key...")
        val idHost = OciEndpoints.identityHost(homeRegion)
        val path = "/20160918/users/${auth.userOcid}/apiKeys"
        val url = "https://$idHost$path"

        val publicKey = auth.keyPair.public as RSAPublicKey
        val pubPem = OciRequestSigner.publicKeyToPem(publicKey)
        val jsonBody = JSONObject().put("key", pubPem).toString()

        // Compute body headers � must match what OkHttp actually sends
        val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)
        val contentSha256 = java.util.Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(bodyBytes)
        )
        // Don't set content-length manually � OkHttp computes it from the request body
        // The signing string must use the same value OkHttp will send
        val contentLength = bodyBytes.size.toString()

        val (authHeader, dateStr, signingStr) = OciRequestSigner.buildAuthHeader(
                tenancyOcid = auth.tenancyOcid,
                userOcid = auth.userOcid,
                fingerprint = auth.fingerprint,
                privateKey = auth.privateKey,
                method = "POST",
                path = path,
                host = idHost,
                useSecurityToken = true, securityToken = auth.securityToken,
                body = jsonBody, )

        // DEBUG: Show signing string and auth header on screen (dev mode only)
        if (isDevMode) {
            emit(Phase.API_KEY, Status.RUNNING, "API key signerRegionId=$homeRegion identityHost=$idHost identityUrl=https://$idHost")
            emit(Phase.API_KEY, Status.RUNNING, "POST signing string:")
            signingStr.split("\n").forEachIndexed { i, line ->
                emit(Phase.API_KEY, Status.RUNNING, " [$i] $line")
            }
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
            throw Exception("API key upload failed: ${httpResp.code} ${httpResp.body.take(200)}")
        }

        val uploadedFingerprint = try {
            if (httpResp.body.isBlank()) auth.fingerprint else JSONObject(httpResp.body).optString("fingerprint", auth.fingerprint)
        } catch (e: Exception) {
            auth.fingerprint
        }
        if (uploadedFingerprint != auth.fingerprint) {
            emit(Phase.API_KEY, Status.WARNING, "Uploaded API key fingerprint differs from local fingerprint; using OCI response")
        }

        emit(Phase.API_KEY, Status.RUNNING, "Waiting for propagation...")
        kotlinx.coroutines.delay(5000)
        emit(Phase.API_KEY, Status.SUCCESS, "API key uploaded: $uploadedFingerprint")
        return uploadedFingerprint
    }

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

        // Create subnet (use VCN default DHCP options � no need to fetch them)
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

    private suspend fun launchVm(auth: AuthResult, homeRegion: String, rids: ResourceIds,
                                  sshPublicKey: String): String {
        val cid = auth.tenancyOcid
        val idHost = OciEndpoints.identityHost(homeRegion)
        val iaasHost = OciEndpoints.iaasHost(homeRegion)
        emit(
            Phase.VM_LAUNCH,
            Status.RUNNING,
            "Phase region trace: provisioningRegion=$homeRegion iaasHost=$iaasHost imageRegion=$homeRegion instanceLaunchRegion=$homeRegion",
        )

        // Get availability domain (returns a JSON array, not an object with "items")
        emit(Phase.VM_LAUNCH, Status.RUNNING, "Finding availability domain...")
        val adResp = ociGetArray(auth, idHost, "/20160918/availabilityDomains?compartmentId=$cid")
        val adName = adResp.getJSONObject(0).getString("name")
        emit(Phase.VM_LAUNCH, Status.RUNNING, "Availability domain selected: $adName (region=$homeRegion)")

        // Find Ubuntu image
        emit(Phase.VM_LAUNCH, Status.RUNNING, "Finding Ubuntu 22.04 image...")
        val imgPath = "/20160918/images?compartmentId=${URLEncoder.encode(cid, "UTF-8")}" +
            "&operatingSystem=Canonical+Ubuntu&operatingSystemVersion=22.04" +
            "&shape=VM.Standard.E2.1.Micro&sortBy=TIMECREATED&sortOrder=DESC"
        val imgResp = ociGetArray(auth, iaasHost, imgPath)
        var imageId: String
        if (imgResp.length() > 0) {
            imageId = imgResp.getJSONObject(0).getString("id")
        } else {
            emit(Phase.VM_LAUNCH, Status.RUNNING, "Trying Ubuntu 24.04...")
            val imgPath24 = "/20160918/images?compartmentId=${URLEncoder.encode(cid, "UTF-8")}" +
                "&operatingSystem=Canonical+Ubuntu&operatingSystemVersion=24.04" +
                "&shape=VM.Standard.E2.1.Micro&sortBy=TIMECREATED&sortOrder=DESC"
            val imgResp24 = ociGetArray(auth, iaasHost, imgPath24)
            if (imgResp24.length() == 0) {
                throw Exception("No Ubuntu image found for VM.Standard.E2.1.Micro")
            }
            imageId = imgResp24.getJSONObject(0).getString("id")
        }

        // Launch instance
        emit(Phase.VM_LAUNCH, Status.RUNNING, "Launching instance (VM.Standard.E2.1.Micro)...")
        val launchBody = JSONObject()
            .put("availabilityDomain", adName)
            .put("compartmentId", cid)
            .put("displayName", "zerovpn-exit-01")
            .put("shape", "VM.Standard.E2.1.Micro")
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

        val launchResp = ociPost(auth, iaasHost, "/20160918/instances", launchBody)
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
    ): ProvisionResult {
        val port = 51820

        // Phase 5: Wait for SSH (OCI reports RUNNING before cloud-init/sshd are ready)
        emit(Phase.WAIT_SSH, Status.RUNNING, "Waiting for SSH...")
        emit(Phase.WAIT_SSH, Status.RUNNING, "Phase region trace: provisioningRegion=$homeRegion publicIp=$publicIp")
        val jsch = JSch()
        val keyTempFile = java.io.File(context.cacheDir, "ssh_key_${System.currentTimeMillis()}")
        keyTempFile.writeText(sshPrivateKey)
        keyTempFile.deleteOnExit()
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
        val installResult = runSshCommand(
            sshConnection,
            "sudo apt-get update -y; sudo apt-get install -y wireguard wireguard-tools; which wg || exit 1",
            label = "install WireGuard",
        )
        if (installResult.exitCode != 0) {
            sshConnection.session?.disconnect()
            keyTempFile.delete()
            throw Exception("apt install failed: ${installResult.stderr.takeLast(300)}")
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
        val runResult = runSshCommand(
            sshConnection,
            "CLIENT_PUBLIC_KEY='${clientKeys.publicKey}' bash /tmp/setup-wg.sh",
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

        emit(Phase.WIREGUARD, Status.SUCCESS, "WireGuard configured")
        return ProvisionResult(
            publicIp = publicIp,
            wireGuardPort = port,
            clientConfig = clientConfig,
            clientPublicKey = clientKeys.publicKey,
            serverPublicKey = serverPub,
            serverPeerPublicKey = serverPeerPub,
            sshUsername = "ubuntu",
            sshPrivateKey = sshPrivateKey,
        )
    }

    private data class RemoteCommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
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
            emit(Phase.WAIT_SSH, if (result.exitCode == 0) Status.RUNNING else Status.WARNING, "SSH readiness exit=${result.exitCode}")
            if (result.exitCode == 0 && result.stdout.contains("ZERO_VPN_SSH_READY")) {
                emit(Phase.WAIT_SSH, Status.SUCCESS, "SSH command execution ready")
                return
            }
            emit(Phase.WAIT_SSH, Status.RUNNING, "SSH connected, but the VM closed the SSH command session. Retrying setup...")
            connection.session?.disconnect()
            connection.session = null
            kotlinx.coroutines.delay(10_000)
        }
        throw Exception(
            "WireGuard setup failed because the SSH session dropped during VM setup. " +
                "The VM was created and SSH became reachable, but setup commands could not complete.",
        )
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
                    val (exitCode, stdout, stderr) = sshExec(connection.session!!, command)
                    RemoteCommandResult(exitCode, stdout, stderr)
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
                    emit(Phase.WIREGUARD, Status.RUNNING, "SSH connected, but the VM closed the SSH command session. Retrying setup...")
                    kotlinx.coroutines.delay(5_000)
                }
            }
        }
        return RemoteCommandResult(
            exitCode = 255,
            stdout = "",
            stderr = "${lastError?.javaClass?.simpleName ?: "SshCommandFailed"}: ${lastError?.message ?: "unknown SSH command failure"}",
        )
    }

    // --- Full provisioning pipeline ---

    suspend fun provision(auth: AuthResult, preflight: PreflightResult): Pair<ResourceIds, ProvisionResult> {
        val homeRegion = preflight.homeRegion

        // Upload API key
        try {
            uploadApiKey(auth, homeRegion)
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

        // Create network
        val rids = createNetwork(auth, homeRegion)

        // Launch VM
        val publicIp = launchVm(auth, homeRegion, rids, sshPublicKey)

        // Setup WireGuard via SSH
        val provisionResult = setupWireGuard(auth, homeRegion, publicIp, sshPrivateKey, wireGuardClientKeys)

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

    private suspend fun ociPost(auth: AuthResult, host: String, path: String, body: String): JSONObject {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
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
                useSecurityToken = true, securityToken = auth.securityToken,
                body = body, )
            val req = Request.Builder()
            .url("https://$host$path")
            .header("date", dateStr)
            .header("Content-Type", "application/json")
            .header("x-content-sha256", contentSha256)
            .header("Authorization", authHeader)
            .post(body.toByteArray(Charsets.UTF_8).toRequestBody(jsonMedia))
            .build()
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
                    useSecurityToken = true, securityToken = auth.securityToken, )
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
                    useSecurityToken = true, securityToken = auth.securityToken, )
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
                    useSecurityToken = true, securityToken = auth.securityToken,
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
                    useSecurityToken = true, securityToken = auth.securityToken, )
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

    private fun sshExec(session: Session, command: String): Triple<Int, String, String> {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val bais = java.io.ByteArrayOutputStream()
        val baes = java.io.ByteArrayOutputStream()
        channel.connect(30000)
        val buf = ByteArray(4096)
        // Read from channel output stream and err stream
        val outStream = channel.getInputStream()
        val errStream = channel.getErrStream()
        while (channel.isConnected || outStream.available() > 0 || errStream.available() > 0) {
            while (outStream.available() > 0) {
                val n = outStream.read(buf)
                if (n > 0) bais.write(buf, 0, n)
            }
            while (errStream.available() > 0) {
                val n = errStream.read(buf)
                if (n > 0) baes.write(buf, 0, n)
            }
            if (channel.isClosed) break
            Thread.sleep(50)
        }
        // Final drain
        while (outStream.available() > 0) {
            val n = outStream.read(buf)
            if (n > 0) bais.write(buf, 0, n) else break
        }
        while (errStream.available() > 0) {
            val n = errStream.read(buf)
            if (n > 0) baes.write(buf, 0, n) else break
        }
        val exitCode = channel.exitStatus
        channel.disconnect()
        return Triple(exitCode, bais.toString(), baes.toString())
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
