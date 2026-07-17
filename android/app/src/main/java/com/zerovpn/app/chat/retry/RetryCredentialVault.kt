package com.zerovpn.app.chat.retry

import com.zerovpn.app.storage.SecureSecretStore
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

interface RetrySecretStore {
    fun putSecret(key: String, value: String)
    fun getSecret(key: String): String?
    fun removeSecret(key: String)

    fun putSecrets(secrets: Map<String, String>): Boolean {
        secrets.forEach { (key, value) -> putSecret(key, value) }
        return true
    }
}

class SecureRetrySecretStore(private val delegate: SecureSecretStore) : RetrySecretStore {
    override fun putSecret(key: String, value: String) = delegate.putSecret(key, value)
    override fun getSecret(key: String): String? = delegate.getSecret(key)
    override fun removeSecret(key: String) = delegate.removeSecret(key)
    override fun putSecrets(secrets: Map<String, String>): Boolean = delegate.putSecrets(secrets)
}

data class RetryCredentials(
    val securityToken: String,
    val privateKeyPem: String,
)

/**
 * Durable API-key credentials persisted after the API key upload succeeds.
 * The background retry worker loads these instead of the short-lived browser token.
 */
data class DurableApiKeyCredentials(
    val tenancyOcid: String,
    val userOcid: String,
    val fingerprint: String,
    val privateKeyPem: String,
    val region: String,
    val publicKeySha256: String?,
)

class RetryCredentialVault(
    private val secretStore: RetrySecretStore,
) {
    constructor(secureSecretStore: SecureSecretStore) : this(SecureRetrySecretStore(secureSecretStore))

    /**
     * @deprecated Use [storeApiKeyCredentials] for durable post-upload API-key auth.
     * Kept for backward compatibility with existing provisioning flow.
     */
    @Deprecated("Use storeApiKeyCredentials for durable API-key auth", ReplaceWith("storeApiKeyCredentials"))
    fun storeCredentials(sessionId: String, securityToken: String, privateKeyPem: String): Boolean {
        if (sessionId.isBlank() || securityToken.isBlank() || privateKeyPem.isBlank()) return false
        val expected = RetryCredentials(securityToken, privateKeyPem)
        val committed = secretStore.putSecrets(
            mapOf(
                SecureSecretStore.oracleRetrySecurityToken(sessionId) to securityToken,
                SecureSecretStore.oracleApiSigningPrivateKey(sessionId) to privateKeyPem,
            ),
        )
        return committed && loadCredentials(sessionId) == expected
    }

    fun loadCredentials(sessionId: String): RetryCredentials? {
        if (sessionId.isBlank()) return null
        val token = secretStore.getSecret(SecureSecretStore.oracleRetrySecurityToken(sessionId))?.takeIf { it.isNotBlank() }
            ?: return null
        val key = secretStore.getSecret(SecureSecretStore.oracleApiSigningPrivateKey(sessionId))?.takeIf { it.isNotBlank() }
            ?: return null
        return RetryCredentials(token, key)
    }

    fun clearCredentials(sessionId: String) {
        if (sessionId.isBlank()) return
        secretStore.removeSecret(SecureSecretStore.oracleRetrySecurityToken(sessionId))
        secretStore.removeSecret(SecureSecretStore.oracleApiSigningPrivateKey(sessionId))
    }

    // --- Durable API-key credential storage ---

    /**
     * Persist durable API-key credentials for the background retry worker.
     * All fields are stored atomically via [RetrySecretStore.putSecrets].
     */
    fun storeApiKeyCredentials(sessionId: String, creds: DurableApiKeyCredentials): Boolean {
        if (sessionId.isBlank()) return false
        if (creds.tenancyOcid.isBlank() || creds.userOcid.isBlank() ||
            creds.fingerprint.isBlank() || creds.privateKeyPem.isBlank() ||
            creds.region.isBlank()
        ) return false
        val secrets = buildMap {
            put(SecureSecretStore.oracleApiKeyTenancy(sessionId), creds.tenancyOcid)
            put(SecureSecretStore.oracleApiKeyUser(sessionId), creds.userOcid)
            put(SecureSecretStore.oracleApiKeyFingerprint(sessionId), creds.fingerprint)
            put(SecureSecretStore.oracleApiKeyPrivateKey(sessionId), creds.privateKeyPem)
            put(SecureSecretStore.oracleApiKeyRegion(sessionId), creds.region)
            creds.publicKeySha256?.let { put(SecureSecretStore.oracleApiKeyPublicKeyDigest(sessionId), it) }
        }
        val committed = secretStore.putSecrets(secrets)
        if (!committed) return false
        // Verify round-trip
        val loaded = loadApiKeyCredentials(sessionId) ?: return false
        return loaded.tenancyOcid == creds.tenancyOcid &&
            loaded.userOcid == creds.userOcid &&
            loaded.fingerprint == creds.fingerprint &&
            loaded.privateKeyPem == creds.privateKeyPem &&
            loaded.region == creds.region
    }

    /**
     * Load durable API-key credentials for the background retry worker.
     * Returns null if any required field is missing.
     */
    fun loadApiKeyCredentials(sessionId: String): DurableApiKeyCredentials? {
        if (sessionId.isBlank()) return null
        val tenancy = secretStore.getSecret(SecureSecretStore.oracleApiKeyTenancy(sessionId))?.takeIf { it.isNotBlank() }
            ?: return null
        val user = secretStore.getSecret(SecureSecretStore.oracleApiKeyUser(sessionId))?.takeIf { it.isNotBlank() }
            ?: return null
        val fingerprint = secretStore.getSecret(SecureSecretStore.oracleApiKeyFingerprint(sessionId))?.takeIf { it.isNotBlank() }
            ?: return null
        val privateKeyPem = secretStore.getSecret(SecureSecretStore.oracleApiKeyPrivateKey(sessionId))?.takeIf { it.isNotBlank() }
            ?: return null
        val region = secretStore.getSecret(SecureSecretStore.oracleApiKeyRegion(sessionId))?.takeIf { it.isNotBlank() }
            ?: return null
        val publicKeySha256 = secretStore.getSecret(SecureSecretStore.oracleApiKeyPublicKeyDigest(sessionId))?.takeIf { it.isNotBlank() }
        return DurableApiKeyCredentials(
            tenancyOcid = tenancy,
            userOcid = user,
            fingerprint = fingerprint,
            privateKeyPem = privateKeyPem,
            region = region,
            publicKeySha256 = publicKeySha256,
        )
    }

    /**
     * Remove all durable API-key credentials for a session.
     */
    fun clearApiKeyCredentials(sessionId: String) {
        if (sessionId.isBlank()) return
        secretStore.removeSecret(SecureSecretStore.oracleApiKeyTenancy(sessionId))
        secretStore.removeSecret(SecureSecretStore.oracleApiKeyUser(sessionId))
        secretStore.removeSecret(SecureSecretStore.oracleApiKeyFingerprint(sessionId))
        secretStore.removeSecret(SecureSecretStore.oracleApiKeyPrivateKey(sessionId))
        secretStore.removeSecret(SecureSecretStore.oracleApiKeyRegion(sessionId))
        secretStore.removeSecret(SecureSecretStore.oracleApiKeyPublicKeyDigest(sessionId))
    }

    fun storeProvisioningCredentials(
        provisioningId: String,
        securityToken: String,
        privateKeyPem: String,
    ): Boolean = storeCredentials(provisioningScopeId(provisioningId), securityToken, privateKeyPem)

    /**
     * Store durable API-key credentials staged during provisioning.
     * The worker promotes these to the session scope when the retry session starts.
     */
    fun storeProvisioningApiKeyCredentials(
        provisioningId: String,
        creds: DurableApiKeyCredentials,
    ): Boolean = storeApiKeyCredentials(provisioningScopeId(provisioningId), creds)

    fun loadProvisioningApiKeyCredentials(provisioningId: String): DurableApiKeyCredentials? =
        loadApiKeyCredentials(provisioningScopeId(provisioningId))

    fun storeProvisioningCredentials(
        provisioningId: String,
        securityToken: String,
        privateKey: PrivateKey,
    ): Boolean =
        storeProvisioningCredentials(
            provisioningId = provisioningId,
            securityToken = securityToken,
            privateKeyPem = privateKeyToPkcs8Pem(privateKey),
        )

    fun loadProvisioningCredentials(provisioningId: String): RetryCredentials? =
        loadCredentials(provisioningScopeId(provisioningId))

    fun promoteProvisioningCredentials(provisioningId: String, sessionId: String): Boolean {
        // Promote durable API-key credentials (new path)
        val stagedApiKey = loadProvisioningApiKeyCredentials(provisioningId)
        if (stagedApiKey != null) {
            if (!storeApiKeyCredentials(sessionId, stagedApiKey)) return false
            if (loadApiKeyCredentials(sessionId) == null) return false
            clearProvisioningApiKeyCredentials(provisioningId)
            // Also clear legacy credentials if present
            clearProvisioningCredentials(provisioningId)
            return true
        }
        // Fall back to legacy security-token credentials
        if (loadCredentials(sessionId) != null) {
            clearProvisioningCredentials(provisioningId)
            return true
        }
        val staged = loadProvisioningCredentials(provisioningId) ?: return false
        if (!storeCredentials(sessionId, staged.securityToken, staged.privateKeyPem)) return false
        if (loadCredentials(sessionId) != staged) return false
        clearProvisioningCredentials(provisioningId)
        return true
    }

    fun clearProvisioningCredentials(provisioningId: String) {
        clearCredentials(provisioningScopeId(provisioningId))
    }

    fun clearProvisioningApiKeyCredentials(provisioningId: String) {
        clearApiKeyCredentials(provisioningScopeId(provisioningId))
    }

    fun loadPrivateKey(privateKeyPem: String): PrivateKey = privateKeyFromPkcs8Pem(privateKeyPem)

    companion object {
        internal fun provisioningScopeId(provisioningId: String): String =
            provisioningId.takeIf { it.isNotBlank() }?.let { "provisioning:$it" }.orEmpty()

        fun privateKeyToPkcs8Pem(privateKey: PrivateKey): String {
            val body = Base64.getMimeEncoder(64, "\n".toByteArray())
                .encodeToString(privateKey.encoded)
            return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----"
        }

        fun privateKeyFromPkcs8Pem(privateKeyPem: String): PrivateKey {
            val base64 = privateKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace(Regex("\\s"), "")
            val der = Base64.getDecoder().decode(base64)
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        }
    }
}

data class CapacityRetryStartContext(
    val provisioningId: String,
    val candidateId: String,
    val mode: CapacityRetryMode,
    val sourceExitId: String?,
    val compartmentOcid: String?,
    val userOcid: String?,
    val tenancyOcid: String?,
    val fingerprint: String?,
    val selectedRegion: String?,
    val tokenRegion: String?,
    val tokenRegionSource: String?,
    val subnetId: String?,
    val sshPublicKey: String?,
    val initialLaunchAttemptFinishedAtUtc: String? = null,
    val initialNextEligibleAttemptAtUtc: String? = null,
    val pendingMemoryGb: Int = 6,
    val initialAttemptMemoryGb: Int? = null,
    val initialHttpStatus: Int? = null,
    val initialOciErrorCode: String? = null,
    val initialLastResult: String? = null,
    val availabilityDomain: String? = null,
    val ubuntuImageOcid: String? = null,
    val vcnOcid: String? = null,
    val identityHost: String? = null,
    val iaasHost: String? = null,
)

sealed interface CapacityRetryStartResult {
    data class Started(val session: CapacityRetrySession) : CapacityRetryStartResult
    data object MissingStoredCredentials : CapacityRetryStartResult
}

/**
 * Creates a retry session only from credentials staged during foreground provisioning.
 * No browser-auth result or newly generated key is accepted at retry-start time.
 */
class CapacityRetrySessionStarter(
    private val repository: CapacityRetryRepository,
    private val vault: RetryCredentialVault,
) {
    fun start(context: CapacityRetryStartContext): CapacityRetryStartResult {
        val existing = repository.sessions().firstOrNull {
            it.candidateId == context.candidateId && it.blocksReplacementLaunchSession()
        }
        if (existing?.state == CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED) {
            return CapacityRetryStartResult.Started(existing)
        }
        if (
            existing == null &&
            vault.loadProvisioningCredentials(context.provisioningId) == null &&
            vault.loadProvisioningApiKeyCredentials(context.provisioningId) == null
        ) {
            return CapacityRetryStartResult.MissingStoredCredentials
        }

        val session = existing ?: repository.createSession(
            candidateId = context.candidateId,
            mode = context.mode,
            sourceExitId = context.sourceExitId,
            compartmentOcid = context.compartmentOcid,
            userOcid = context.userOcid,
            tenancyOcid = context.tenancyOcid,
            fingerprint = context.fingerprint,
            selectedRegion = context.selectedRegion,
            tokenRegion = context.tokenRegion,
            tokenRegionSource = context.tokenRegionSource,
            subnetId = context.subnetId,
            sshPublicKey = context.sshPublicKey,
            initialLaunchAttemptFinishedAtUtc = context.initialLaunchAttemptFinishedAtUtc,
            initialNextEligibleAttemptAtUtc = context.initialNextEligibleAttemptAtUtc,
            pendingMemoryGb = context.pendingMemoryGb,
            initialAttemptMemoryGb = context.initialAttemptMemoryGb,
            initialHttpStatus = context.initialHttpStatus,
            initialOciErrorCode = context.initialOciErrorCode,
            initialLastResult = context.initialLastResult,
            ubuntuImageOcid = context.ubuntuImageOcid,
            vcnOcid = context.vcnOcid,
            identityHost = context.identityHost,
            iaasHost = context.iaasHost,
        )
        if (!vault.promoteProvisioningCredentials(context.provisioningId, session.sessionId)) {
            vault.clearCredentials(session.sessionId)
            repository.updateSession(session.sessionId) {
                it.copy(
                    state = CapacityRetryState.PAUSED_AUTH_REQUIRED,
                    lastResult = "STORED_CREDENTIALS_UNAVAILABLE",
                    lastSafeErrorCategory = "auth-required",
                    requiresUserAction = true,
                    terminalReason = "The original Oracle signing credentials are missing or unreadable.",
                )
            }
            return CapacityRetryStartResult.MissingStoredCredentials
        }
        return CapacityRetryStartResult.Started(session)
    }
}
