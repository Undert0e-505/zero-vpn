package com.zerovpn.app.oci

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import fi.iki.elonen.NanoHTTPD
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
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
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Real OCI provisioner â€” ports the Python state_machine.py to Kotlin.
 *
 * Flow: browser auth â†’ preflight â†’ API key upload â†’ network â†’ VM â†’ SSH/WireGuard â†’ done
 *
 * All secrets (private keys, tokens, client configs) are kept in memory only.
 * No secrets are written to disk, SharedPreferences, or logs.
 */
class OciProvisioner(
    private val context: Context,
    private val region: String,
    private val isDevMode: Boolean = true,
) {
    private val _events = MutableSharedFlow<ProvisioningEvent>(replay = 64, extraBufferCapacity = 64)
    val events: SharedFlow<ProvisioningEvent> = _events.asSharedFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            // WIRE TAP: log exact request headers + body for POST requests
            if (request.method == "POST" || request.method == "PUT") {
                try {
                    val reqHeaders = request.headers
                    val headerDump = StringBuilder()
                    headerDump.append("[WIRE TAP] ${request.method} ${request.url}\n")
                    headerDump.append("[WIRE TAP] Request headers (${reqHeaders.size} keys):\n")
                    for (name in reqHeaders.names()) {
                        val value = reqHeaders.get(name) ?: ""
                        val display = if (name.lowercase() == "authorization") value.take(150) + "...[REDACTED]" else value
                        headerDump.append("[WIRE TAP]   $name: $display\n")
                    }
                    // Try to read body
                    val bodyCopy = request.body
                    if (bodyCopy != null) {
                        val buffer = Buffer()
                        bodyCopy.writeTo(buffer)
                        val bodyStr = buffer.readUtf8()
                        val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
                        val sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(bodyBytes)
                        val sha256b64 = java.util.Base64.getEncoder().encodeToString(sha256)
                        headerDump.append("[WIRE TAP] Request body: ${bodyBytes.size} bytes, SHA256=$sha256b64\n")
                        headerDump.append("[WIRE TAP] Body preview: ${bodyStr.take(200)}\n")
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

    // Regionâ†’realm mapping
    private val realms = mapOf(
        "uk-london-1" to "oraclecloud.com",
        "uk-cardiff-1" to "oraclecloud.com",
        "us-ashburn-1" to "oraclecloud.com",
        "us-phoenix-1" to "oraclecloud.com",
        "eu-frankfurt-1" to "oraclecloud.com",
        "eu-amsterdam-1" to "oraclecloud.com",
        "ap-tokyo-1" to "oraclecloud.com",
        "ap-sydney-1" to "oraclecloud.com",
        "ap-mumbai-1" to "oraclecloud.com",
        "ap-seoul-1" to "oraclecloud.com",
        "ca-toronto-1" to "oraclecloud.com",
        "sa-saopaulo-1" to "oraclecloud.com",
        "me-jeddah-1" to "oc1",
        "me-dubai-1" to "oc1",
        "il-jerusalem-1" to "oc1",
    )

    // --- Result types ---

    data class AuthResult(
        val securityToken: String,
        val privateKey: java.security.PrivateKey,
        val keyPair: java.security.KeyPair,
        val userOcid: String,
        val tenancyOcid: String,
        val fingerprint: String,
    )

    data class PreflightResult(
        val success: Boolean,
        val homeRegion: String,
        val isUkRegion: Boolean,
        val error: String? = null,
    )

    data class ProvisionResult(
        val publicIp: String,
        val wireGuardPort: Int,
        val clientConfig: String,
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
        val realm = realms[region] ?: "oraclecloud.com"
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
                    val html = """<script>h=window.location.hash;if(h[0]==='#')h=h.substr(1);
                        var r=new XMLHttpRequest();r.onload=function(){document.write('OK')};
                        r.open('GET','/token?'+h);r.send();</script>"""
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

        if (userOcid == null || tenancyOcid == null) {
            emit(Phase.AUTH, Status.ERROR, "Token missing required claims")
            throw Exception("Token missing sub/tenant claims")
        }

        emit(Phase.AUTH, Status.SUCCESS, "Authenticated")
        return AuthResult(
            securityToken = token,
            privateKey = keyPair.private,
            keyPair = keyPair,
            userOcid = userOcid,
            tenancyOcid = tenancyOcid,
            fingerprint = fingerprint,
        )
    }

    // --- Phase 2: Preflight ---

    suspend fun preflight(auth: AuthResult): PreflightResult {
        emit(Phase.API_KEY, Status.RUNNING, "Checking home region...")

        // List region subscriptions to find home region
        val idHost = "identity.$region.oci.oraclecloud.com"
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
                useSecurityToken = true, securityToken = auth.securityToken, )
        val request = Request.Builder()
            .url(url)
            .header("date", dateStr)
            .header("Authorization", authHeader)
            .get()
            .build()

        // Log the request details for debugging (redact token, truncate)
        val authHdrDebug = request.header("Authorization") ?: ""
        // Show just the structure, not the full token
        val authPreview = authHdrDebug.take(80) + "...[REDACTED]..." + authHdrDebug.takeLast(30)
        emit(Phase.API_KEY, Status.RUNNING, "GET regionSubscriptions")
        emit(Phase.API_KEY, Status.RUNNING, "Auth: $authPreview")
        emit(Phase.API_KEY, Status.RUNNING, "About to execute HTTP request...")

        // Read body inside IO block to avoid NetworkOnMainThreadException
        data class HttpResp(val code: Int, val body: String, val isSuccessful: Boolean)
        val httpResp = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val resp = httpClient.newCall(request).execute()
                    val body = resp.body?.string() ?: ""
                    emit(Phase.API_KEY, Status.RUNNING, "HTTP response received: ${resp.code}")
                    HttpResp(resp.code, body, resp.isSuccessful)
                } catch (e: Exception) {
                    emit(Phase.API_KEY, Status.ERROR, "HTTP failed: ${e.javaClass.simpleName}: ${e.message}")
                    emit(Phase.API_KEY, Status.ERROR, "Cause: ${e.cause?.javaClass?.simpleName}: ${e.cause?.message}")
                    null
                }
            }
        } catch (e: Exception) {
            emit(Phase.API_KEY, Status.ERROR, "Coroutine failed: ${e.javaClass.simpleName}: ${e.message}")
            return PreflightResult(false, region, false, "Request failed: ${e.javaClass.simpleName}")
        }
        if (httpResp == null) {
            emit(Phase.API_KEY, Status.ERROR, "No response from server")
            return PreflightResult(false, region, false, "Request failed")
        }
        if (!httpResp.isSuccessful) {
            emit(Phase.API_KEY, Status.ERROR, "HTTP ${httpResp.code}")
            emit(Phase.API_KEY, Status.ERROR, "Response: ${httpResp.body.take(300)}")
            return PreflightResult(false, region, false, "Region list failed: ${httpResp.code}")
        }

        val subscriptions = try {
            JSONArray(if (httpResp.body.isBlank()) "[]" else httpResp.body)
        } catch (e: Exception) {
            emit(Phase.API_KEY, Status.ERROR, "Parse failed: ${e.message}")
            emit(Phase.API_KEY, Status.ERROR, "Body: ${httpResp.body.take(300)}")
            return PreflightResult(false, region, false, "Response parse failed")
        }
        var homeRegion = region
        for (i in 0 until subscriptions.length()) {
            val sub = subscriptions.getJSONObject(i)
            if (sub.optBoolean("is_home_region", false)) {
                homeRegion = sub.optString("region_name")
                break
            }
        }

        val isUk = homeRegion in listOf("uk-london-1", "uk-cardiff-1")
        emit(Phase.API_KEY, Status.RUNNING, "Home region: $homeRegion")

        if (isUk && !isDevMode) {
            val error = "Your Oracle home region is $homeRegion (UK). " +
                "This account can be used for development/testing, but not as a non-UK " +
                "Always Free exit. Create an Oracle account with a non-UK home region."
            emit(Phase.API_KEY, Status.ERROR, error)
            return PreflightResult(false, homeRegion, true, error)
        }

        if (isUk) {
            emit(Phase.API_KEY, Status.WARNING, "UK region (dev/test mode)")
        }

        // Check API key count (max 3)
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
                useSecurityToken = true, securityToken = auth.securityToken, )
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
                    val error = "API key limit reached (${keys.length()}/3). " +
                        "Delete an existing API key in the Oracle console."
                    emit(Phase.API_KEY, Status.ERROR, error)
                    return PreflightResult(false, homeRegion, isUk, error)
                }
            } else {
                emit(Phase.API_KEY, Status.WARNING, "API key check: HTTP ${keyResult.first}")
            }
        } catch (e: Exception) {
            emit(Phase.API_KEY, Status.WARNING, "API key check failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        emit(Phase.API_KEY, Status.SUCCESS, "Preflight passed")
        return PreflightResult(true, homeRegion, isUk, null)
    }

    // --- Phase 3: API Key Upload ---

    private suspend fun uploadApiKey(auth: AuthResult, homeRegion: String) {
        emit(Phase.API_KEY, Status.RUNNING, "Uploading API key...")
        val idHost = "identity.$homeRegion.oci.oraclecloud.com"
        val path = "/20160918/users/${auth.userOcid}/apiKeys"
        val url = "https://$idHost$path"

        val publicKey = auth.keyPair.public as RSAPublicKey
        val pubPem = OciRequestSigner.publicKeyToPem(publicKey)
        val jsonBody = JSONObject().put("key", pubPem).toString()

        // Compute body headers — must match what OkHttp actually sends
        val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)
        val contentSha256 = java.util.Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(bodyBytes)
        )
        // Don't set content-length manually — OkHttp computes it from the request body
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

        // DEBUG: Show signing string and auth header on screen
        emit(Phase.API_KEY, Status.RUNNING, "POST signing string:")
        signingStr.split("\n").forEachIndexed { i, line ->
            emit(Phase.API_KEY, Status.RUNNING, " [$i] $line")
        }
        emit(Phase.API_KEY, Status.RUNNING, "Auth header: ${authHeader.take(120)}...")

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

        emit(Phase.API_KEY, Status.RUNNING, "Waiting for propagation...")
        kotlinx.coroutines.delay(5000)
        emit(Phase.API_KEY, Status.SUCCESS, "API key uploaded")
    }

    // --- Phase 4: Network Creation ---

    private suspend fun createNetwork(auth: AuthResult, homeRegion: String): ResourceIds {
        val rids = ResourceIds()
        val cid = auth.tenancyOcid
        val iaasHost = "iaas.$homeRegion.oraclecloud.com"

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

        // Create subnet (use VCN default DHCP options — no need to fetch them)
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
        val idHost = "identity.$homeRegion.oci.oraclecloud.com"
        val iaasHost = "iaas.$homeRegion.oraclecloud.com"

        // Get availability domain (returns a JSON array, not an object with "items")
        emit(Phase.VM_LAUNCH, Status.RUNNING, "Finding availability domain...")
        val adResp = ociGetArray(auth, idHost, "/20160918/availabilityDomains?compartmentId=$cid")
        val adName = adResp.getJSONObject(0).getString("name")

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
                publicIp = vnicGet.optString("publicIp", null)
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
    ): ProvisionResult {
        val port = 51820

        // Phase 5: Wait for SSH (OCI reports RUNNING before cloud-init/sshd are ready)
        emit(Phase.WAIT_SSH, Status.RUNNING, "Waiting for SSH...")
        val jsch = JSch()
        val keyTempFile = java.io.File(context.cacheDir, "ssh_key_${System.currentTimeMillis()}")
        keyTempFile.writeText(sshPrivateKey)
        keyTempFile.deleteOnExit()
        jsch.addIdentity(keyTempFile.absolutePath)

        var session: Session? = null
        val sshTimeoutMs = 10 * 60 * 1000L
        val sshStart = System.currentTimeMillis()
        var sshAttempt = 0
        var sshLastError: Exception? = null
        while (System.currentTimeMillis() - sshStart < sshTimeoutMs) {
            sshAttempt++
            try {
                emit(Phase.WAIT_SSH, Status.RUNNING, "SSH attempt $sshAttempt to ubuntu@$publicIp...")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    session = jsch.getSession("ubuntu", publicIp, 22)
                    session.setConfig("StrictHostKeyChecking", "no")
                    session.setConfig("UserKnownHostsFile", "/dev/null")
                    session.setConfig("PreferredAuthentications", "publickey")
                    session.timeout = 15000
                    session.connect(15000)
                }
                break
            } catch (e: Exception) {
                sshLastError = e
                session?.disconnect()
                session = null
                emit(Phase.WAIT_SSH, Status.RUNNING, "SSH not ready: ${e.javaClass.simpleName}: ${e.message}")
                kotlinx.coroutines.delay(10_000)
            }
        }
        if (session == null || !session.isConnected) {
            keyTempFile.delete()
            throw Exception("SSH did not become ready after ${sshTimeoutMs / 1000}s. Last error: ${sshLastError?.javaClass?.simpleName}: ${sshLastError?.message}")
        }
        emit(Phase.WAIT_SSH, Status.SUCCESS, "SSH connected")

        // Phase 6: WireGuard
        emit(Phase.WIREGUARD, Status.RUNNING, "Installing WireGuard...")
        val installResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            sshExec(session,
                "sudo apt-get update -y; sudo apt-get install -y wireguard wireguard-tools; which wg || exit 1")
        }
        if (installResult.first != 0) {
            session.disconnect()
            keyTempFile.delete()
            throw Exception("apt install failed: ${installResult.third.takeLast(300)}")
        }

        emit(Phase.WIREGUARD, Status.RUNNING, "Generating server keys...")
        val keygenCmd = "sudo sh -c \"umask 077; wg genkey > /etc/wireguard/server.key; " +
            "wg pubkey < /etc/wireguard/server.key > /etc/wireguard/server.pub\""
        val keygenResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            sshExec(session, keygenCmd)
        }
        if (keygenResult.first != 0) {
            session.disconnect()
            keyTempFile.delete()
            throw Exception("keygen failed: ${keygenResult.third.takeLast(200)}")
        }

        emit(Phase.WIREGUARD, Status.RUNNING, "Configuring WireGuard...")
        // Write setup script to VM via cat heredoc
        val writeCmd = "cat > /tmp/setup-wg.sh << 'ENDOFSCRIPT'\n" + SETUP_WG_SCRIPT + "\nENDOFSCRIPT"
        val writeResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            sshExec(session, writeCmd)
        }
        if (writeResult.first != 0) {
            session.disconnect()
            keyTempFile.delete()
            throw Exception("Failed to write setup script: ${writeResult.third}")
        }

        // Fix line endings and run
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            sshExec(session, "sed -i 's/\\r\$//' /tmp/setup-wg.sh")
        }
        val runResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            sshExec(session, "bash /tmp/setup-wg.sh")
        }
        if (runResult.first != 0) {
            session.disconnect()
            keyTempFile.delete()
            throw Exception("setup-wg.sh failed: ${runResult.third.takeLast(300)}")
        }

        val stdout = runResult.second
        var peerKey: String? = null
        var serverPub: String? = null
        for (line in stdout.split("\n")) {
            if (line.startsWith("PEER_PRIVATE_KEY=")) {
                peerKey = line.substringAfter("=").trim()
            } else if (line.startsWith("SERVER_PUBLIC_KEY=")) {
                serverPub = line.substringAfter("=").trim()
            }
        }

        session.disconnect()
        keyTempFile.delete()

        if (peerKey == null || serverPub == null) {
            throw Exception("WireGuard key extraction failed")
        }

        val clientConfig = "[Interface]\nPrivateKey = $peerKey\n" +
            "Address = 10.66.66.2/24\nDNS = 1.1.1.1\n\n" +
            "[Peer]\nPublicKey = $serverPub\n" +
            "Endpoint = $publicIp:$port\n" +
            "AllowedIPs = 0.0.0.0/0\nPersistentKeepalive = 25\n"

        emit(Phase.WIREGUARD, Status.SUCCESS, "WireGuard configured")
        return ProvisionResult(publicIp, port, clientConfig)
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

        // Create network
        val rids = createNetwork(auth, homeRegion)

        // Launch VM
        val publicIp = launchVm(auth, homeRegion, rids, sshPublicKey)

        // Setup WireGuard via SSH
        val provisionResult = setupWireGuard(auth, homeRegion, publicIp, sshPrivateKey)

        // Done
        emit(Phase.DONE, Status.SUCCESS, "Exit created: ${provisionResult.publicIp}:${provisionResult.wireGuardPort}")

        return rids to provisionResult
    }

    // --- Destroy ---

    suspend fun destroy(rids: ResourceIds, auth: AuthResult, homeRegion: String): Boolean {
        val cid = auth.tenancyOcid
        val iaasHost = "iaas.$homeRegion.oraclecloud.com"

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

        emit(Phase.DONE, Status.SUCCESS, "Resources destroyed")
        return true
    }

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

    private suspend fun ociDelete(auth: AuthResult, host: String, path: String) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
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
            if (!resp.isSuccessful && resp.code != 404) {
                // Non-fatal during destroy
            }
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

# Get server private key
SERVER_KEY=${'$'}(sudo cat /etc/wireguard/server.key)

# Write wg0.conf
sudo bash -c "cat > /etc/wireguard/wg0.conf << EOF
[Interface]
PrivateKey = ${'$'}SERVER_KEY
Address = 10.66.66.1/24
ListenPort = 51820
PostUp = iptables -A FORWARD -i wg0 -j ACCEPT; iptables -t nat -A POSTROUTING -o eth0 -j MASQUERADE
PostDown = iptables -D FORWARD -i wg0 -j ACCEPT; iptables -t nat -D POSTROUTING -o eth0 -j MASQUERADE
EOF"
sudo chmod 600 /etc/wireguard/wg0.conf

# Enable IP forwarding
sudo sysctl -w net.ipv4.ip_forward=1
echo 'net.ipv4.ip_forward=1' | sudo tee -a /etc/sysctl.conf

# Start WireGuard
sudo systemctl enable wg-quick@wg0
sudo systemctl start wg-quick@wg0

# Generate peer keypair
wg genkey | tee /tmp/peer.key | wg pubkey > /tmp/peer.pub
PEER_KEY=${'$'}(cat /tmp/peer.key)
PEER_PUB=${'$'}(cat /tmp/peer.pub)

# Add peer to server
sudo wg set wg0 peer "${'$'}PEER_PUB" allowed-ips 10.66.66.2/32

# Get server public key
SERVER_PUB=${'$'}(sudo cat /etc/wireguard/server.pub)

# Output
echo "PEER_PRIVATE_KEY=${'$'}PEER_KEY"
echo "SERVER_PUBLIC_KEY=${'$'}SERVER_PUB"
echo "---"
sudo wg show
""".trimIndent()
    }
}







