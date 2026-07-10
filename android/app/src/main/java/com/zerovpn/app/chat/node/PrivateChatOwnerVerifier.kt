package com.zerovpn.app.chat.node

import android.annotation.SuppressLint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

sealed interface PrivateChatOwnerVerificationResult {
    data object Verified : PrivateChatOwnerVerificationResult
    data class Failed(val message: String) : PrivateChatOwnerVerificationResult
}

class PrivateChatOwnerVerifier {
    suspend fun verify(
        node: PrivateChatNodeState,
        credentialsJson: String,
    ): PrivateChatOwnerVerificationResult = withContext(Dispatchers.IO) {
        try {
            val url = node.matrixPrivateUrl
                ?.takeIf { it == "https://10.66.66.1" }
                ?: return@withContext PrivateChatOwnerVerificationResult.Failed(
                    "The private Matrix URL is missing or unexpected.",
                )
            val pin = node.tlsSpkiSha256
                ?.takeIf { it.matches(Regex("^sha256/[A-Za-z0-9+/]{43}=$")) }
                ?: return@withContext PrivateChatOwnerVerificationResult.Failed(
                    "The private TLS fingerprint is missing or invalid.",
                )
            val credentials = JSONObject(credentialsJson)
            val userId = credentials.getString("userId")
            val password = credentials.getString("password")
            if (userId != node.ownerMatrixUserId || password.length < 32) {
                return@withContext PrivateChatOwnerVerificationResult.Failed(
                    "The saved owner Matrix credentials do not match this node.",
                )
            }
            val trustManager = PinnedSpkiTrustManager(pin)
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            }
            val client = OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build()

            val versionsRequest = Request.Builder()
                .url("$url/_matrix/client/versions")
                .get()
                .build()
            client.newCall(versionsRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext PrivateChatOwnerVerificationResult.Failed(
                        "The private Matrix endpoint did not pass its versions check.",
                    )
                }
            }

            val loginBody = JSONObject()
                .put("type", "m.login.password")
                .put(
                    "identifier",
                    JSONObject()
                        .put("type", "m.id.user")
                        .put("user", userId),
                )
                .put("password", password)
                .put("device_id", "ZEROVPN_PHASE1_OWNER_VERIFY")
                .put("initial_device_display_name", "ZeroVPN Phase 1 owner verification")
                .toString()
                .toRequestBody(JSON_MEDIA)
            val loginRequest = Request.Builder()
                .url("$url/_matrix/client/v3/login")
                .post(loginBody)
                .build()
            val accessToken = client.newCall(loginRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext PrivateChatOwnerVerificationResult.Failed(
                        "The owner Matrix account rejected login.",
                    )
                }
                val payload = JSONObject(response.body?.string().orEmpty())
                payload.optString("access_token").takeIf { it.isNotBlank() }
                    ?: return@withContext PrivateChatOwnerVerificationResult.Failed(
                        "The owner Matrix login returned no session token.",
                    )
            }
            val logoutRequest = Request.Builder()
                .url("$url/_matrix/client/v3/logout")
                .header("Authorization", "Bearer $accessToken")
                .post("{}".toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(logoutRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext PrivateChatOwnerVerificationResult.Failed(
                        "Owner login succeeded but the verification session could not be closed.",
                    )
                }
            }
            PrivateChatOwnerVerificationResult.Verified
        } catch (_: Exception) {
            PrivateChatOwnerVerificationResult.Failed(
                "Owner Matrix login verification failed through the active WireGuard route.",
            )
        }
    }

    // A public-CA trust manager cannot validate the provisioned self-signed node.
    // This manager is scoped to one short-lived client, checks validity plus the
    // exact provisioned SPKI, and leaves OkHttp's IP-SAN hostname check enabled.
    @SuppressLint("CustomX509TrustManager")
    private class PinnedSpkiTrustManager(expectedPin: String) : X509TrustManager {
        private val expectedHash = Base64.getDecoder().decode(expectedPin.removePrefix("sha256/"))

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Client certificates are not accepted.")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val certificate = chain?.firstOrNull()
                ?: throw CertificateException("The private Matrix server returned no certificate.")
            certificate.checkValidity()
            val actualHash = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
            if (!MessageDigest.isEqual(expectedHash, actualHash)) {
                throw CertificateException("The private Matrix TLS fingerprint changed.")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
