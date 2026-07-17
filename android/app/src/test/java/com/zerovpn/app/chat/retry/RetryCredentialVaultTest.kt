package com.zerovpn.app.chat.retry

import com.zerovpn.app.oci.OciRequestSigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RetryCredentialVaultTest {
    @Test fun storeLoadClearCycleUsesSessionScopedKeys() {
        val store = FakeRetrySecretStore()
        val vault = RetryCredentialVault(store)
        vault.storeCredentials("session-1", "token", "pem")
        assertEquals(RetryCredentials("token", "pem"), vault.loadCredentials("session-1"))
        assertNull(vault.loadCredentials("session-2"))
        vault.clearCredentials("session-1")
        assertNull(vault.loadCredentials("session-1"))
    }

    @Test fun privateKeyPemRoundTripCanSignAndVerify() {
        val pair = OciRequestSigner.generateKeyPair()
        val pem = RetryCredentialVault.privateKeyToPkcs8Pem(pair.private)
        assertTrue(pem.startsWith("-----BEGIN PRIVATE KEY-----"))
        val decoded = RetryCredentialVault.privateKeyFromPkcs8Pem(pem)

        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(decoded)
        signature.update("hello".toByteArray())
        val bytes = signature.sign()

        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(pair.public)
        verifier.update("hello".toByteArray())
        assertTrue(verifier.verify(bytes))
    }

    @Test fun credentialsStoredDuringProvisioningArePromotedAtRetryStart() {
        val store = FakeRetrySecretStore()
        val vault = RetryCredentialVault(store)
        val provisioningId = "oci:pending-exit"
        val signingKey = OciRequestSigner.generateKeyPair().private
        val expectedPem = RetryCredentialVault.privateKeyToPkcs8Pem(signingKey)
        assertTrue(vault.storeProvisioningCredentials(provisioningId, "original-token", signingKey))
        assertNull(vault.loadCredentials(provisioningId))
        assertEquals(1, store.bulkWrites.size)
        assertTrue(store.bulkWrites.single().keys.all { it.contains("provisioning:$provisioningId") })

        val repository = CapacityRetryRepository(
            FakeSharedPreferences(),
            Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC),
        )
        val result = CapacityRetrySessionStarter(repository, vault).start(startContext(provisioningId))

        val session = (result as CapacityRetryStartResult.Started).session
        assertEquals(CapacityRetryState.WAITING_FOR_RETRY, session.state)
        assertEquals(
            RetryCredentials("original-token", expectedPem),
            vault.loadCredentials(session.sessionId),
        )
        assertNull(vault.loadProvisioningCredentials(provisioningId))
        assertEquals(2, store.bulkWrites.size)
    }

    @Test fun retryStartUsesPersistedCredentialsWithoutAnAuthResult() {
        val store = FakeRetrySecretStore()
        val vault = RetryCredentialVault(store)
        val provisioningId = "oci:pending-exit"
        vault.storeProvisioningCredentials(provisioningId, "token", "pem")
        val starter = CapacityRetrySessionStarter(
            CapacityRetryRepository(FakeSharedPreferences()),
            vault,
        )

        val result = starter.start(startContext(provisioningId))

        assertTrue(result is CapacityRetryStartResult.Started)
    }

    @Test fun retryStartDoesNotCreateSessionWhenProvisioningCredentialsAreMissing() {
        val repository = CapacityRetryRepository(FakeSharedPreferences())
        val result = CapacityRetrySessionStarter(
            repository,
            RetryCredentialVault(FakeRetrySecretStore()),
        ).start(startContext("oci:missing"))

        assertEquals(CapacityRetryStartResult.MissingStoredCredentials, result)
        assertTrue(repository.sessions().isEmpty())
    }

    @Test fun retryStartPausesSessionWhenDurableCredentialPromotionFails() {
        val store = FakeRetrySecretStore()
        val vault = RetryCredentialVault(store)
        val provisioningId = "oci:pending-exit"
        assertTrue(vault.storeProvisioningCredentials(provisioningId, "original-token", "pem"))
        store.rejectBulkWrites = true
        val repository = CapacityRetryRepository(FakeSharedPreferences())

        val result = CapacityRetrySessionStarter(repository, vault).start(startContext(provisioningId))

        assertEquals(CapacityRetryStartResult.MissingStoredCredentials, result)
        val paused = repository.sessions().single()
        assertEquals(CapacityRetryState.PAUSED_AUTH_REQUIRED, paused.state)
        assertEquals("STORED_CREDENTIALS_UNAVAILABLE", paused.lastResult)
        assertTrue(paused.requiresUserAction)
        assertNull(vault.loadCredentials(paused.sessionId))
    }

    @Test fun retryStartCannotDowngradeOrClearAnAmbiguousReconciliationBlocker() {
        val store = FakeRetrySecretStore()
        val vault = RetryCredentialVault(store)
        val provisioningId = "oci:ambiguous-candidate"
        val repository = CapacityRetryRepository(FakeSharedPreferences())
        val session = repository.createSession(
            candidateId = provisioningId,
            mode = CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            sourceExitId = null,
            compartmentOcid = "tenancy",
        ).copy(
            state = CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED,
            lastResult = "AMBIGUOUS_EXCEPTION_RECONCILIATION_REQUIRED",
        )
        repository.replaceSession(session)
        assertTrue(vault.storeCredentials(session.sessionId, "original-token", "original-pem"))
        val before = vault.loadCredentials(session.sessionId)

        val result = CapacityRetrySessionStarter(repository, vault).start(startContext(provisioningId))

        assertTrue(result is CapacityRetryStartResult.Started)
        assertEquals(session.sessionId, (result as CapacityRetryStartResult.Started).session.sessionId)
        assertEquals(
            CapacityRetryState.FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED,
            repository.session(session.sessionId)?.state,
        )
        assertEquals(before, vault.loadCredentials(session.sessionId))
    }

    @Test fun provisioningCredentialStageRejectsIncompleteSigningInput() {
        val store = FakeRetrySecretStore()
        val vault = RetryCredentialVault(store)

        assertFalse(vault.storeProvisioningCredentials("oci:pending-exit", "", "pem"))
        assertFalse(vault.storeProvisioningCredentials("oci:pending-exit", "token", ""))
        assertTrue(store.bulkWrites.isEmpty())
    }

    private fun startContext(provisioningId: String) = CapacityRetryStartContext(
        provisioningId = provisioningId,
        candidateId = provisioningId,
        mode = CapacityRetryMode.INITIAL_PRIVATE_CHAT,
        sourceExitId = null,
        compartmentOcid = "ocid1.tenancy.oc1..tenancy",
        userOcid = "ocid1.user.oc1..user",
        tenancyOcid = "ocid1.tenancy.oc1..tenancy",
        fingerprint = "aa:bb:cc",
        selectedRegion = "uk-london-1",
        tokenRegion = "uk-london-1",
        tokenRegionSource = "token-claim",
        subnetId = "ocid1.subnet.oc1..subnet",
        sshPublicKey = "ssh-rsa AAAA zerovpn-android",
    )

    private class FakeRetrySecretStore : RetrySecretStore {
        private val values = mutableMapOf<String, String>()
        val bulkWrites = mutableListOf<Map<String, String>>()
        var rejectBulkWrites = false
        override fun putSecret(key: String, value: String) { values[key] = value }
        override fun getSecret(key: String): String? = values[key]
        override fun removeSecret(key: String) { values.remove(key) }
        override fun putSecrets(secrets: Map<String, String>): Boolean {
            bulkWrites += secrets.toMap()
            if (rejectBulkWrites) return false
            values.putAll(secrets)
            return true
        }
    }
}
