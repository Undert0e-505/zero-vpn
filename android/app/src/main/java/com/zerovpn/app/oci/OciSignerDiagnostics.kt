package com.zerovpn.app.oci

/**
 * Redacted, signer-safe diagnostics for the API-key activation GET.
 *
 * This helper never logs the Authorization header value, signature, private key,
 * browser token, or full OCIDs/fingerprints. It is intended for Dev Mode only.
 */
object OciSignerDiagnostics {

    /**
     * Returns a map of safe diagnostic fields for an API-key activation probe.
     *
     * @param method HTTP method, e.g. "GET"
     * @param pathAndQuery full canonical path and query, e.g. "/20160918/users/ocid1.user.oc1..x/apiKeys"
     * @param signedHeaderNames ordered header names from the signing string
     * @param keyId full keyId = tenancy/user/fingerprint
     * @param date RFC-1123 date header value
     * @param host host header value
     * @param stringToSignSha256 hex SHA-256 of the signing string
     * @param authorizationHasVersion true if Authorization contains `version="1"`
     * @param authorizationHasKeyId true if Authorization contains `keyId="..."`
     * @param authorizationHasAlgorithm true if Authorization contains `algorithm="rsa-sha256"`
     * @param authorizationHasHeaders true if Authorization contains `headers="..."`
     * @param authorizationHasSignature true if Authorization contains `signature="..."`
     */
    fun build(
        method: String,
        pathAndQuery: String,
        signedHeaderNames: List<String>,
        keyId: String,
        date: String,
        host: String,
        stringToSignSha256: String,
        authorizationHasVersion: Boolean,
        authorizationHasKeyId: Boolean,
        authorizationHasAlgorithm: Boolean,
        authorizationHasHeaders: Boolean,
        authorizationHasSignature: Boolean,
    ): Map<String, String> {
        return linkedMapOf(
            "method" to method,
            "canonicalPath" to abbreviatePath(pathAndQuery),
            "queryString" to extractQuery(pathAndQuery),
            "signedHeaderNames" to signedHeaderNames.joinToString(", "),
            "keyId" to abbreviateKeyId(keyId),
            "date" to date,
            "host" to host,
            "stringToSignSha256" to stringToSignSha256,
            "authorizationHasVersion" to authorizationHasVersion.toString(),
            "authorizationHasKeyId" to authorizationHasKeyId.toString(),
            "authorizationHasAlgorithm" to authorizationHasAlgorithm.toString(),
            "authorizationHasHeaders" to authorizationHasHeaders.toString(),
            "authorizationHasSignature" to authorizationHasSignature.toString(),
        )
    }

    /** Abbreviate an OCID to `ocid1.kind.ocx..` plus a short suffix. */
    private fun abbreviateOcid(ocid: String): String {
        if (!ocid.startsWith("ocid1.")) return "***"
        val parts = ocid.split(".")
        // ocid1.<type>.<realm-or-service>..<hash>
        val prefix = parts.take(3).joinToString(".")
        val suffix = parts.lastOrNull()?.takeLast(4)?.let { "..$it" } ?: ""
        return "$prefix$suffix"
    }

    /** Abbreviate all OCIDs in a canonical path. */
    private fun abbreviatePath(pathAndQuery: String): String {
        val path = pathAndQuery.substringBefore('?')
        val query = pathAndQuery.substringAfter('?', "")
        val abbreviated = path.replace(Regex("ocid1\\.[a-z]+\\.oc[0-9]?\\.\\.[^/]+")) { abbreviateOcid(it.value) }
        return if (query.isEmpty()) abbreviated else "$abbreviated?$query"
    }

    private fun extractQuery(pathAndQuery: String): String =
        pathAndQuery.substringAfter('?', "")

    /**
     * Safe Authorization-header diagnostics.
     *
     * Parses the actual `headers="..."` value from the real Authorization header
     * and reports whether it is comma- or space-separated, bracketed, etc.
     * Never logs the full Authorization header, signature, or full OCIDs.
     */
    fun buildAuthorizationDiagnostics(authorization: String): Map<String, String> {
        val headersRaw = extractQuoted(authorization, "headers")
        return linkedMapOf(
            "authHeader.scheme" to (if (authorization.startsWith("Signature ")) "Signature" else "UNKNOWN"),
            "authHeader.version" to (extractQuoted(authorization, "version") ?: "missing"),
            "authHeader.algorithm" to (extractQuoted(authorization, "algorithm") ?: "missing"),
            "authHeader.headersRaw" to (headersRaw ?: "missing"),
            "authHeader.headersContainsComma" to (headersRaw?.contains(',')?.toString() ?: "n/a"),
            "authHeader.headersHasBrackets" to ((headersRaw?.contains('[') == true || headersRaw?.contains(']') == true).toString()),
            "authHeader.headersUsesSpaces" to (headersRaw?.contains(' ')?.toString() ?: "n/a"),
            "authHeader.keyIdAbbrev" to abbreviateKeyId(extractQuoted(authorization, "keyId") ?: ""),
            "authHeader.signaturePresent" to authorization.contains("signature=\"").toString(),
        )
    }

    /**
     * Safe final-request diagnostics for the API-key activation GET.
     *
     * Compares the signed request values against the values OkHttp actually transmits.
     * Never logs the full Authorization header, signature value, request body, or full OCIDs.
     */
    fun buildFinalRequestDiagnostics(
        signedMethod: String,
        signedUrl: String,
        signedEncodedPath: String,
        signedQuery: String,
        signedHost: String,
        signedDate: String,
        signedContentSha256: String?,
        signedContentType: String?,
        signedContentLength: String?,
        signedAuthorization: String,
        finalMethod: String,
        finalUrl: String,
        finalEncodedPath: String,
        finalQuery: String?,
        finalUrlHost: String,
        finalHostHeader: String?,
        finalDateHeader: String?,
        finalContentSha256Header: String?,
        finalContentTypeHeader: String?,
        finalContentLengthHeader: String?,
        finalAuthorizationHeader: String?,
    ): Map<String, String> {
        val mismatches = mutableListOf<String>()
        if (signedMethod != finalMethod) mismatches.add("method differs: signed=$signedMethod final=$finalMethod")
        if (signedEncodedPath != finalEncodedPath) mismatches.add("encoded path differs")
        val finalQueryNorm = finalQuery ?: ""
        if (signedQuery != finalQueryNorm) mismatches.add("query string differs: signed='$signedQuery' final='$finalQueryNorm'")
        val finalHostHeaderNorm = finalHostHeader ?: ""
        if (!signedHost.equals(finalUrlHost, ignoreCase = true)) mismatches.add("signed host differs from final URL host")
        if (!signedHost.equals(finalHostHeaderNorm, ignoreCase = true)) mismatches.add("host header missing or differs")
        if (signedDate != finalDateHeader) mismatches.add("date header differs: signed=$signedDate final=$finalDateHeader")
        if (signedContentSha256 != null && signedContentSha256 != finalContentSha256Header) mismatches.add("x-content-sha256 header differs")
        if (signedContentType != null && signedContentType != finalContentTypeHeader) mismatches.add("content-type header differs")
        if (signedContentLength != null && signedContentLength != finalContentLengthHeader) mismatches.add("content-length header differs")
        if (finalAuthorizationHeader == null) mismatches.add("authorization header missing from final request")

        val authDiagnostics = finalAuthorizationHeader?.let { buildAuthorizationDiagnostics(it) } ?: emptyMap()
        val authorizationSha256 = finalAuthorizationHeader?.let { hexSha256(it) } ?: "none"
        val signatureSha256 = finalAuthorizationHeader?.let { auth ->
            extractQuoted(auth, "signature")?.let { hexSha256(it) } ?: "none"
        } ?: "none"

        val authPresent = finalAuthorizationHeader != null
        return linkedMapOf(
            "finalRequestMatchesSignedValues" to (mismatches.isEmpty() && authPresent).toString(),
            "finalRequestMismatch" to if (mismatches.isEmpty()) "none" else mismatches.first(),
            "signedMethod" to signedMethod,
            "finalMethod" to finalMethod,
            "signedUrl" to abbreviateUrl(signedUrl),
            "finalUrl" to abbreviateUrl(finalUrl),
            "signedEncodedPath" to abbreviatePath(signedEncodedPath),
            "finalEncodedPath" to abbreviatePath(finalEncodedPath),
            "signedQuery" to signedQuery,
            "finalQuery" to (finalQuery ?: ""),
            "signedHost" to signedHost,
            "finalUrlHost" to finalUrlHost,
            "finalHostHeader" to (finalHostHeader ?: "missing"),
            "signedDate" to signedDate,
            "finalDateHeader" to (finalDateHeader ?: "missing"),
            "signedContentSha256" to (signedContentSha256 ?: "n/a"),
            "finalContentSha256Header" to (finalContentSha256Header ?: "missing"),
            "signedContentType" to (signedContentType ?: "n/a"),
            "finalContentTypeHeader" to (finalContentTypeHeader ?: "missing"),
            "signedContentLength" to (signedContentLength ?: "n/a"),
            "finalContentLengthHeader" to (finalContentLengthHeader ?: "missing"),
            "authorizationHeaderPresent" to authPresent.toString(),
            "authorizationScheme" to (authDiagnostics["authHeader.scheme"] ?: "missing"),
            "authorizationVersion" to (authDiagnostics["authHeader.version"] ?: "missing"),
            "authorizationHasKeyId" to (authDiagnostics["authHeader.keyIdAbbrev"] != null && authDiagnostics["authHeader.keyIdAbbrev"] != "***").toString(),
            "authorizationHasAlgorithm" to (authDiagnostics["authHeader.algorithm"] ?: "missing"),
            "authorizationHasHeaders" to (authDiagnostics["authHeader.headersRaw"] != null && authDiagnostics["authHeader.headersRaw"] != "missing").toString(),
            "authorizationHasSignature" to (authDiagnostics["authHeader.signaturePresent"] ?: "false"),
            "authorizationHeaderSha256" to authorizationSha256,
            "signatureValueSha256" to signatureSha256,
        )
    }

    /** Abbreviate a URL, keeping scheme, host and redacting OCIDs in the path. */
    private fun abbreviateUrl(url: String): String {
        return try {
            val parsed = java.net.URL(url)
            val redactedPath = abbreviatePath(parsed.path + (parsed.query?.let { "?$it" } ?: ""))
            "${parsed.protocol}://${parsed.host}$redactedPath"
        } catch (e: Exception) {
            abbreviatePath(url)
        }
    }

    private fun hexSha256(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Extract the value inside `name="value"` from a Signature Authorization header. */
    private fun extractQuoted(authorization: String, name: String): String? {
        val prefix = "$name=\""
        val start = authorization.indexOf(prefix)
        if (start < 0) return null
        val valueStart = start + prefix.length
        val end = authorization.indexOf('"', valueStart)
        return if (end >= valueStart) authorization.substring(valueStart, end) else null
    }

    /** Abbreviate an OCI API-key fingerprint to `fp:..last`. */
    fun abbreviateFingerprint(fingerprint: String): String {
        if (fingerprint.length <= 12) return fingerprint
        return "fp:${fingerprint.take(6)}...${fingerprint.takeLast(5)}"
    }

    /** Abbreviate a hex digest to `first8...last8`. */
    fun abbreviateDigest(digest: String): String {
        if (digest.length <= 16) return digest
        return "${digest.take(8)}...${digest.takeLast(8)}"
    }

    /**
     * Safe fingerprint-source diagnostics for API-key activation validation.
     * Never logs full fingerprints or digests.
     */
    fun buildFingerprintDiagnostics(
        authFingerprint: String,
        authContextFingerprint: String,
        uploadedFingerprint: String,
        publicKeyDigest: String,
        persistedPublicKeyDigest: String?,
        signingKeyIdSource: String,
    ): Map<String, String> {
        return linkedMapOf(
            "authFingerprint" to abbreviateFingerprint(authFingerprint),
            "authContextFingerprint" to abbreviateFingerprint(authContextFingerprint),
            "uploadedFingerprint" to abbreviateFingerprint(uploadedFingerprint),
            "authFingerprintMatchesAuthContext" to (authFingerprint == authContextFingerprint).toString(),
            "authContextMatchesUploaded" to (authContextFingerprint == uploadedFingerprint).toString(),
            "publicKeyDigest" to abbreviateDigest(publicKeyDigest),
            "persistedPublicKeyDigest" to (persistedPublicKeyDigest?.let { abbreviateDigest(it) } ?: "none"),
            "signingKeyIdSource" to signingKeyIdSource,
        )
    }

    /** Abbreviate keyId `tenancy/user/fingerprint` to `ocid1.tenancy.../ocid1.user.../fp:..last`. */
    private fun abbreviateKeyId(keyId: String): String {
        val parts = keyId.split('/')
        if (parts.size != 3) return "***"
        return "${abbreviateOcid(parts[0])}/${abbreviateOcid(parts[1])}/fp:${parts[2].takeLast(5)}"
    }
}
