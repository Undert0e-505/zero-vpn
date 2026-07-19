package com.zerovpn.app.oci

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.Signature
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/** The exact, immutable bytes used both for signing and transmission. */
data class OciSignedRequest(
    val method: String,
    val host: String,
    val pathAndQuery: String,
    val date: String,
    val body: ByteArray?,
    val headers: LinkedHashMap<String, String>,
    val signedHeaderNames: List<String>,
    val keyId: String,
    val stringToSign: String,
    val stringToSignSha256: String,
    val authorization: String,
)

/** OCI Signature Version 1 implementation shared by every durable caller. */
class OciSignedClient(private val clock: Clock = Clock.systemUTC()) {
    fun sign(
        auth: OciAuthContext,
        method: String,
        host: String,
        pathAndQuery: String,
        body: ByteArray? = null,
        dateOverride: String? = null,
    ): OciSignedRequest {
        require(host == host.trim() && host.isNotEmpty()) { "Invalid OCI host." }
        require(pathAndQuery.startsWith('/')) { "OCI request target must be origin-form." }
        val normalizedMethod = method.uppercase()
        val date = dateOverride ?: DateTimeFormatter.RFC_1123_DATE_TIME
            .format(clock.instant().atZone(ZoneOffset.UTC))
        val hasBody = normalizedMethod in setOf("POST", "PUT", "PATCH")
        val bytes = if (hasBody) body ?: ByteArray(0) else null
        val values = linkedMapOf(
            "date" to date,
            "(request-target)" to "${normalizedMethod.lowercase()} $pathAndQuery",
            "host" to host,
        )
        if (hasBody) {
            values["content-length"] = bytes!!.size.toString()
            values["content-type"] = "application/json"
            values["x-content-sha256"] = Base64.getEncoder().encodeToString(sha256(bytes))
        }
        val stringToSign = values.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        val privateKey = when (auth) {
            is OciAuthContext.ApiKey -> auth.privateKey
            is OciAuthContext.SecurityTokenBootstrap -> auth.privateKey
        }
        val keyId = when (auth) {
            is OciAuthContext.ApiKey -> listOf(auth.tenancyOcid, auth.userOcid, auth.fingerprint)
                .onEach { require(it == it.trim() && it.isNotEmpty()) { "Invalid API-key identity component." } }
                .joinToString("/")
            is OciAuthContext.SecurityTokenBootstrap -> "ST\$${auth.securityToken}"
        }
        val rsa = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(stringToSign.toByteArray(StandardCharsets.UTF_8))
        }
        val signature = Base64.getEncoder().encodeToString(rsa.sign())
        val names = values.keys.toList()
        // Keep Oracle's documented Signature-v1 parameter order. Some OCI auth paths
        // reject the previously emitted algorithm-first form even though token auth accepts it.
        val authorization = "Signature version=\"1\",keyId=\"$keyId\",algorithm=\"rsa-sha256\"," +
            "headers=\"${names.joinToString(" ")}\",signature=\"$signature\""
        val transmitted = linkedMapOf("date" to date)
        if (hasBody) {
            transmitted["content-length"] = values.getValue("content-length")
            transmitted["content-type"] = values.getValue("content-type")
            transmitted["x-content-sha256"] = values.getValue("x-content-sha256")
        }
        transmitted["host"] = host
        transmitted["Authorization"] = authorization
        return OciSignedRequest(
            normalizedMethod, host, pathAndQuery, date, bytes, transmitted, names, keyId,
            stringToSign, hex(sha256(stringToSign.toByteArray(StandardCharsets.UTF_8))), authorization,
        )
    }

    private fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
    private fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it) }
}
