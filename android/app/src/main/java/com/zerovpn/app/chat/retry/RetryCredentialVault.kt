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

class RetryCredentialVault(
    private val secretStore: RetrySecretStore,
) {
    constructor(secureSecretStore: SecureSecretStore) : this(SecureRetrySecretStore(secureSecretStore))

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

    fun storeProvisioningCredentials(
        provisioningId: String,
        securityToken: String,
        privateKeyPem: String,
    ): Boolean = storeCredentials(provisioningScopeId(provisioningId), securityToken, privateKeyPem)

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
            vault.loadProvisioningCredentials(context.provisioningId) == null
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
