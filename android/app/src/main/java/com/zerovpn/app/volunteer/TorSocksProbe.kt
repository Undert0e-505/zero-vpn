package com.zerovpn.app.volunteer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class TorSocksProbe {
    suspend fun run(
        socksHost: String,
        socksPort: Int,
        url: String = DEFAULT_TEST_URL,
        timeoutSeconds: Long = SOCKS_TEST_TIMEOUT_SECONDS,
    ): Result = withContext(Dispatchers.IO) {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort))
        val client = OkHttpClient.Builder()
            .proxy(proxy)
            .dns(NoLocalDns)
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val status = "HTTP ${response.code}"
            if (!response.isSuccessful) {
                error("SOCKS test failed: $status")
            }
            val json = runCatching { JSONObject(body) }.getOrNull()
            Result(
                url = url,
                status = json?.optBoolean("IsTor")?.let { "$status IsTor=$it" } ?: status,
                exitIp = json?.optString("IP")?.takeIf { it.isNotBlank() }
                    ?: json?.optString("ip")?.takeIf { it.isNotBlank() },
                isTor = if (json?.has("IsTor") == true) json.optBoolean("IsTor") else null,
            )
        }
    }

    data class Result(
        val url: String,
        val status: String,
        val exitIp: String?,
        val isTor: Boolean?,
    )

    private object NoLocalDns : Dns {
        override fun lookup(hostname: String) =
            throw UnknownHostException("Local DNS disabled for SOCKS proof of concept: $hostname")
    }

    companion object {
        const val DEFAULT_TEST_URL = "https://check.torproject.org/api/ip"
        const val SOCKS_TEST_TIMEOUT_SECONDS = 20L
    }
}
