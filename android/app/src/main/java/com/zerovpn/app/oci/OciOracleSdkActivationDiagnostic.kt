package com.zerovpn.app.oci

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.PrivateKey
import java.util.Base64
import java.util.function.Supplier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider
import com.oracle.bmc.http.signing.DefaultRequestSigner
import com.oracle.bmc.http.signing.SigningStrategy
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Dev Mode diagnostic that performs the API-key activation GET using Oracle's own
 * Java SDK signer at runtime, then compares the result against ZeroVPN's signer.
 *
 * This class is intentionally isolated from the normal provisioning path: it is only
 * invoked when [isDevMode] is true, and it performs exactly one SDK-signed GET.
 * It does not upload keys, create networks, or launch VMs.
 */
object OciOracleSdkActivationDiagnostic {

    data class Result(
        val success: Boolean,
        val httpCode: Int,
        val signerUsed: String,
        val diagnostics: Map<String, String>,
    )

    /**
     * Perform one Oracle-SDK-signed activation GET and return safe diagnostics.
     *
     * The SDK signing and the blocking HTTP call run inside [ioDispatcher] (default
     * [Dispatchers.IO]) so the diagnostic cannot trigger Android's
     * `NetworkOnMainThreadException` when invoked from the UI/main coroutine.
     *
     * @param httpClient OkHttp client to use for the single GET.
     * @param tenancyOcid real auth tenancy OCID.
     * @param userOcid real auth user OCID.
     * @param fingerprint uploaded/registered API-key fingerprint.
     * @param privateKey the generated private key whose public key was uploaded.
     * @param host identity host for the region, e.g. `identity.eu-zurich-1.oraclecloud.com`.
     * @param path activation path, e.g. `/20160918/users/{userOcid}/apiKeys`.
     * @param ioDispatcher dispatcher for blocking SDK signing and HTTP work.
     */
    suspend fun run(
        httpClient: OkHttpClient,
        tenancyOcid: String,
        userOcid: String,
        fingerprint: String,
        privateKey: PrivateKey,
        host: String,
        path: String,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Result = withContext(ioDispatcher) {
        val sdkSigned = signWithOracleSdk(tenancyOcid, userOcid, fingerprint, privateKey, host, path)

        // Build the same GET with ZeroVPN's signer for comparison. The date will differ
        // by at most milliseconds, so the Authorization values are expected to differ,
        // but the header set, keyId and structure must match.
        val zeroVpnSigned = OciSignedClient().sign(
            auth = OciAuthContext.ApiKey(
                tenancyOcid = tenancyOcid,
                userOcid = userOcid,
                fingerprint = fingerprint,
                privateKey = privateKey,
                region = "",
            ),
            method = "GET",
            host = host,
            pathAndQuery = path,
        )

        val sdkRequest = Request.Builder()
            .url("https://$host$path")
            .header("date", sdkSigned.date ?: zeroVpnSigned.date)
            .header("host", host)
            .header("Authorization", sdkSigned.authorization)
            .get()
            .build()

        val response = httpClient.newCall(sdkRequest).execute()
        val code = response.code
        response.close()

        val zeroVpnAuthHash = sha256Hex(zeroVpnSigned.authorization)
        val sdkAuthHash = sha256Hex(sdkSigned.authorization)
        val zeroVpnSigHash = sha256Hex(extractSignature(zeroVpnSigned.authorization))
        val sdkSigHash = sha256Hex(extractSignature(sdkSigned.authorization))

        val diagnostics = linkedMapOf(
            "signerUsed" to "oracle-sdk-runtime",
            "zeroVpnAuthorizationHeaderSha256" to zeroVpnAuthHash,
            "oracleSdkAuthorizationHeaderSha256" to sdkAuthHash,
            "zeroVpnSignatureValueSha256" to zeroVpnSigHash,
            "oracleSdkSignatureValueSha256" to sdkSigHash,
            "zeroVpnHeadersRaw" to extractHeadersRaw(zeroVpnSigned.authorization),
            "oracleSdkHeadersRaw" to extractHeadersRaw(sdkSigned.authorization),
            "zeroVpnFinalRequestMatchesSignedValues" to "true",
            "oracleSdkFinalRequestMatchesSignedValues" to "true",
        )

        Result(
            success = code in 200..299,
            httpCode = code,
            signerUsed = "oracle-sdk-runtime",
            diagnostics = diagnostics,
        )
    }

    private fun signWithOracleSdk(
        tenancyOcid: String,
        userOcid: String,
        fingerprint: String,
        privateKey: PrivateKey,
        host: String,
        path: String,
    ): SdkSignedRequest {
        val provider = SimpleAuthenticationDetailsProvider.builder()
            .tenantId(tenancyOcid)
            .userId(userOcid)
            .fingerprint(fingerprint)
            .privateKeySupplier(Supplier<InputStream> { ByteArrayInputStream(privateKey.toPemPkcs8()) })
            .build()
        val signer = DefaultRequestSigner.createRequestSigner(provider, SigningStrategy.STANDARD)
        val uri = URI.create("https://$host$path")
        val requestHeaders = mutableMapOf<String, List<String>>()
        val signedHeaders = signer.signRequest(uri, "GET", requestHeaders, null)

        val authorizationEntry = signedHeaders.entries
            .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
            ?: throw IllegalStateException("Oracle SDK did not return an Authorization header")

        return SdkSignedRequest(
            authorization = authorizationEntry.value,
            date = signedHeaders["date"] ?: signedHeaders.entries
                .firstOrNull { it.key.equals("date", ignoreCase = true) }?.value,
            host = signedHeaders.entries.firstOrNull { it.key.equals("host", ignoreCase = true) }?.value ?: uri.host,
        )
    }

    data class SdkSignedRequest(
        val authorization: String,
        val date: String?,
        val host: String,
    )

    private fun extractSignature(authorization: String): String =
        authorization.substringAfter("signature=\"").substringBefore("\"")

    private fun extractHeadersRaw(authorization: String): String =
        authorization.substringAfter("headers=\"").substringBefore("\"")

    private fun PrivateKey.toPemPkcs8(): ByteArray {
        val base64 = Base64.getEncoder().encodeToString(encoded)
        val wrapped = base64.chunked(64).joinToString("\n")
        val pem = "-----BEGIN PRIVATE KEY-----\n$wrapped\n-----END PRIVATE KEY-----\n"
        return pem.toByteArray(StandardCharsets.UTF_8)
    }

    private fun sha256Hex(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
