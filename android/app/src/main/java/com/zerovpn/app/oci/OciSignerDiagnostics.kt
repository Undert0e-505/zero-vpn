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
