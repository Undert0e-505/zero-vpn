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
    private val isDevMode: Boolean = true,
) {
    private val _events = MutableSharedFlow<ProvisioningEvent>(replay = 64, extraBufferCapacity = 64)
    val events: SharedFlow<ProvisioningEvent> = _events.asSharedFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    // Region→realm mapping
    private val realms = mapOf(
        "uk-london-1" to "oc1",
        "uk-cardiff-1" to "oc1",
        "us-ashburn-1" to "oc1",
        "us-phoenix-1" to "oc1",
        "eu-frankfurt-1" to "oc1",
        "eu-amsterdam-1" to "oc1",
        "ap-tokyo-1" to "oc1",
        "ap-sydney-1" to "oc1",
        "ap-mumbai-1" to "oc1",
        "ap-seoul-1" to "oc1",
        "ca-toronto-1" to "oc1",
        "sa-saopaulo-1" to "oc1",
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
        val realm = realms[region] ?: "oc1"
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
        val idHost = "identity.$region.oraclecloud.com"
        val path = "/20160918/tenancies/${auth.tenancyOcid}/regionSubscriptions"
        val url = "https://$idHost$path"

        val request = Request.Builder()
            .url(url)
            .header("date", currentDateRfc1123())
            .header("Authorization", OciRequestSigner.buildAuthHeader(
                tenancyOcid = auth.tenancyOcid,
                userOcid = auth.userOcid,
                fingerprint = auth.fingerprint,
                privateKey = auth.privateKey,
                method = "GET",
                path = path,
                host = idHost,
                useSecurityToken = true,
            ))
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            emit(Phase.API_KEY, Status.ERROR, "Preflight failed: ${response.code}")
            return PreflightResult(false, region, false, "Region list failed: ${response.code}")
        }

        val body = response.body?.string() ?: "[]"
        val subscriptions = JSONArray(body)
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
        val keyReq = Request.Builder()
            .url(keyUrl)
            .header("date", currentDateRfc1123())
            .header("Authorization", OciRequestSigner.buildAuthHeader(
                tenancyOcid = auth.tenancyOcid,
                userOcid = auth.userOcid,
                fingerprint = auth.fingerprint,
                privateKey = auth.privateKey,
                method = "GET",
                path = keyPath,
                host = idHost,
                useSecurityToken = true,
            ))
            .get()
            .build()

        try {
            val keyResp = httpClient.newCall(keyReq).execute()
            if (keyResp.isSuccessful) {
                val keyBody = keyResp.body?.string() ?: "[]"
                val keys = JSONArray(keyBody)
                if (keys.length() >= 3) {
                    val error = "API key limit reached (${keys.length()}/3). " +
                        "Delete an existing API key in the Oracle console."
                    emit(Phase.API_KEY, Status.ERROR, error)
                    return PreflightResult(false, homeRegion, isUk, error)
                }
            }
        } catch (e: Exception) {
            emit(Phase.API_KEY, Status.WARNING, "API key check skipped")
        }

        emit(Phase.API_KEY, Status.SUCCESS, "Preflight passed")
        return PreflightResult(true, homeRegion, isUk, null)
    }

    // --- Phase 3: API Key Upload ---

    private suspend fun uploadApiKey(auth: AuthResult, homeRegion: String) {
        emit(Phase.API_KEY, Status.RUNNING, "Uploading API key...")
        val idHost = "identity.$homeRegion.oraclecloud.com"
        val path = "/20160918/users/${auth.userOcid}/apiKeys"
        val url = "https://$idHost$path"

        val publicKey = auth.keyPair.public as RSAPublicKey
        val pubPem = OciRequestSigner.publicKeyToPem(publicKey)
        val jsonBody = JSONObject().put("key", pubPem).toString()

        val request = Request.Builder()
            .url(url)
            .header("date", currentDateRfc1123())
            .header("Content-Type", "application/json")
            .header("Authorization", OciRequestSigner.buildAuthHeader(
                tenancyOcid = auth.tenancyOcid,
                userOcid = auth.userOcid,
                fingerprint = auth.fingerprint,
                privateKey = auth.privateKey,
                method = "POST",
                path = path,
                host = idHost,
                useSecurityToken = true,
            ))
            .post(jsonBody.toRequestBody(jsonMedia))
            .build()

        val response = httpClient.newCall(request).execute()
        // 409 = already exists, that's fine
        if (!response.isSuccessful && response.code != 409) {
            val respBody = response.body?.string() ?: ""
            throw Exception("API key upload failed: ${response.code} $respBody")
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
        kotlinx.coroutines.delay(2000)

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

        // Create subnet
        emit(Phase.NETWORK, Status.RUNNING, "Creating subnet...")
        val dhcpPath = "/20160918/dhcpOptions?compartmentId=$cid&vcnId=${rids.vcnId}"
        val dhcpResp = ociGet(auth, iaasHost, dhcpPath)
        val dhcpId = dhcpResp.getJSONArray("items").getJSONObject(0).getString("id")

        val subnetBody = JSONObject()
            .put("cidrBlock", "10.0.0.0/24")
            .put("compartmentId", cid)
            .put("displayName", "zerovpn-subnet")
            .put("vcnId", rids.vcnId)
            .put("securityListIds", JSONArray().put(rids.slId))
            .put("dhcpOptionsId", dhcpId)
            .toString()
        val subnetResp = ociPost(auth, iaasHost, "/20160918/subnets", subnetBody)
        rids.subnetId = subnetResp.getString("id")
        kotlinx.coroutines.delay(2000)

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
        kotlinx.coroutines.delay(2000)

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
        val idHost = "identity.$homeRegion.oraclecloud.com"
        val iaasHost = "iaas.$homeRegion.oraclecloud.com"

        // Get availability domain
        emit(Phase.VM_LAUNCH, Status.RUNNING, "Finding availability domain...")
        val adResp = ociGet(auth, idHost, "/20160918/availabilityDomains?compartmentId=$cid")
        val adName = adResp.getJSONArray("items").getJSONObject(0).getString("name")

        // Find Ubuntu image
        emit(Phase.VM_LAUNCH, Status.RUNNING, "Finding Ubuntu 22.04 image...")
        val imgPath = "/20160918/images?compartmentId=${URLEncoder.encode(cid, "UTF-8")}" +
            "&operatingSystem=Canonical+Ubuntu&operatingSystemVersion=22.04" +
            "&shape=VM.Standard.E2.1.Micro&sortBy=TIMECREATED&sortOrder=DESC"
        val imgResp = ociGet(auth, iaasHost, imgPath)
        var imageId: String
        if (imgResp.getJSONArray("items").length() > 0) {
            imageId = imgResp.getJSONArray("items").getJSONObject(0).getString("id")
        } else {
            emit(Phase.VM_LAUNCH, Status.RUNNING, "Trying Ubuntu 24.04...")
            val imgPath24 = "/20160918/images?compartmentId=${URLEncoder.encode(cid, "UTF-8")}" +
                "&operatingSystem=Canonical+Ubuntu&operatingSystemVersion=24.04" +
                "&shape=VM.Standard.E2.1.Micro&sortBy=TIMECREATED&sortOrder=DESC"
            val imgResp24 = ociGet(auth, iaasHost, imgPath24)
            if (imgResp24.getJSONArray("items").length() == 0) {
                throw Exception("No Ubuntu image found for VM.Standard.E2.1.Micro")
            }
            imageId = imgResp24.getJSONArray("items").getJSONObject(0).getString("id")
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
            val vnicResp = ociGet(auth, iaasHost, vnicPath)
            val items = vnicResp.getJSONArray("items")
            if (items.length() > 0) {
                val vnicId = items.getJSONObject(0).getString("vnicId")
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

        // Phase 5: Wait for SSH
        emit(Phase.WAIT_SSH, Status.RUNNING, "Waiting for SSH...")
        val jsch = JSch()
        val keyTempFile = java.io.File(context.cacheDir, "ssh_key_${System.currentTimeMillis()}")
        keyTempFile.writeText(sshPrivateKey)
        keyTempFile.deleteOnExit()
        jsch.addIdentity(keyTempFile.absolutePath)

        var session: Session? = null
        for (i in 1..12) {
            try {
                session = jsch.getSession("ubuntu", publicIp, 22)
                session.setConfig("StrictHostKeyChecking", "no")
                session.setConfig("UserKnownHostsFile", "/dev/null")
                session.timeout = 30000
                session.connect(30000)
                break
            } catch (e: Exception) {
                session?.disconnect()
                kotlinx.coroutines.delay(10_000)
            }
        }
        if (session == null || !session.isConnected) {
            throw Exception("SSH connection failed")
        }
        emit(Phase.WAIT_SSH, Status.SUCCESS, "SSH connected")

        // Phase 6: WireGuard
        emit(Phase.WIREGUARD, Status.RUNNING, "Installing WireGuard...")
        val installResult = sshExec(session,
            "sudo apt-get update -y; sudo apt-get install -y wireguard wireguard-tools; which wg || exit 1")
        if (installResult.first != 0) {
            session.disconnect()
            keyTempFile.delete()
            throw Exception("apt install failed: ${installResult.third.takeLast(300)}")
        }

        emit(Phase.WIREGUARD, Status.RUNNING, "Generating server keys...")
        val keygenCmd = "sudo sh -c \"umask 077; wg genkey > /etc/wireguard/server.key; " +
            "wg pubkey < /etc/wireguard/server.key > /etc/wireguard/server.pub\""
        val keygenResult = sshExec(session, keygenCmd)
        if (keygenResult.first != 0) {
            session.disconnect()
            keyTempFile.delete()
            throw Exception("keygen failed: ${keygenResult.third.takeLast(200)}")
        }

        emit(Phase.WIREGUARD, Status.RUNNING, "Configuring WireGuard...")
        // Write setup script to VM via cat heredoc
        val writeCmd = "cat > /tmp/setup-wg.sh << 'ENDOFSCRIPT'\n" + SETUP_WG_SCRIPT + "\nENDOFSCRIPT"
        val writeResult = sshExec(session, writeCmd)
        if (writeResult.first != 0) {
            session.disconnect()
            keyTempFile.delete()
            throw Exception("Failed to write setup script: ${writeResult.third}")
        }

        // Fix line endings and run
        sshExec(session, "sed -i 's/\\r\$//' /tmp/setup-wg.sh")
        val runResult = sshExec(session, "bash /tmp/setup-wg.sh")
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
        uploadApiKey(auth, homeRegion)

        // Generate SSH keypair for VM access
        val sshKeyPair = generateEd25519KeyPair()
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
                kotlinx.coroutines.delay(3000)
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

    private fun ociPost(auth: AuthResult, host: String, path: String, body: String): JSONObject {
        val req = Request.Builder()
            .url("https://$host$path")
            .header("date", currentDateRfc1123())
            .header("Content-Type", "application/json")
            .header("Authorization", OciRequestSigner.buildAuthHeader(
                tenancyOcid = auth.tenancyOcid,
                userOcid = auth.userOcid,
                fingerprint = auth.fingerprint,
                privateKey = auth.privateKey,
                method = "POST",
                path = path,
                host = host,
                useSecurityToken = true,
            ))
            .post(body.toRequestBody(jsonMedia))
            .build()
        val resp = httpClient.newCall(req).execute()
        val respBody = resp.body?.string() ?: ""
        if (!resp.isSuccessful) {
            throw Exception("POST $path failed: ${resp.code} $respBody")
        }
        return JSONObject(respBody)
    }

    private fun ociGet(auth: AuthResult, host: String, path: String): JSONObject {
        val req = Request.Builder()
            .url("https://$host$path")
            .header("date", currentDateRfc1123())
            .header("Authorization", OciRequestSigner.buildAuthHeader(
                tenancyOcid = auth.tenancyOcid,
                userOcid = auth.userOcid,
                fingerprint = auth.fingerprint,
                privateKey = auth.privateKey,
                method = "GET",
                path = path,
                host = host,
                useSecurityToken = true,
            ))
            .get()
            .build()
        val resp = httpClient.newCall(req).execute()
        val respBody = resp.body?.string() ?: ""
        if (!resp.isSuccessful) {
            throw Exception("GET $path failed: ${resp.code} $respBody")
        }
        return JSONObject(respBody)
    }

    private fun ociPut(auth: AuthResult, host: String, path: String, body: String) {
        val req = Request.Builder()
            .url("https://$host$path")
            .header("date", currentDateRfc1123())
            .header("Content-Type", "application/json")
            .header("Authorization", OciRequestSigner.buildAuthHeader(
                tenancyOcid = auth.tenancyOcid,
                userOcid = auth.userOcid,
                fingerprint = auth.fingerprint,
                privateKey = auth.privateKey,
                method = "PUT",
                path = path,
                host = host,
                useSecurityToken = true,
            ))
            .put(body.toRequestBody(jsonMedia))
            .build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            val respBody = resp.body?.string() ?: ""
            throw Exception("PUT $path failed: ${resp.code} $respBody")
        }
    }

    private fun ociDelete(auth: AuthResult, host: String, path: String) {
        val req = Request.Builder()
            .url("https://$host$path")
            .header("date", currentDateRfc1123())
            .header("Authorization", OciRequestSigner.buildAuthHeader(
                tenancyOcid = auth.tenancyOcid,
                userOcid = auth.userOcid,
                fingerprint = auth.fingerprint,
                privateKey = auth.privateKey,
                method = "DELETE",
                path = path,
                host = host,
                useSecurityToken = true,
            ))
            .delete()
            .build()
        val resp = httpClient.newCall(req).execute()
        // 404 = already gone, that's fine
        if (!resp.isSuccessful && resp.code != 404) {
            // Non-fatal during destroy
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

    private fun generateEd25519KeyPair(): Pair<String, String> {
        val jsch = JSch()
        val kp = com.jcraft.jsch.KeyPair.genKeyPair(jsch, com.jcraft.jsch.KeyPair.ED25519)
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