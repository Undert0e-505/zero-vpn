package com.zerovpn.app.chat.retry

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

enum class ProvisioningLeaseOperation(val isForegroundProvisioning: Boolean) {
    FOREGROUND_PROVISIONING(true),
    FOREGROUND_MANUAL_RETRY(true),
    FOREGROUND_AUTH_CONTINUATION(true),
    BACKGROUND_CAPACITY_RETRY(false),
    CANDIDATE_RECONCILIATION(false),
    CANDIDATE_SWITCH(false),
    CLEANUP(false),
}

/**
 * A process-aware persisted lease shared by foreground provisioning and capacity work.
 *
 * WorkManager runs in ZeroVPN's application process, so a shared process ID provides
 * an atomic in-process exclusion point while a per-acquisition token prevents one
 * lease instance from releasing another's operation. A different process ID means
 * Android recreated the process; that persisted lease is stale and is cleared rather
 * than blocking background work forever.
 */
class ProvisioningOperationLease(
    private val prefs: SharedPreferences,
    private val processInstanceId: String = ProcessLeaseIdentity.id,
) {
    private var heldLeaseToken: String? = null

    fun tryAcquire(operation: ProvisioningLeaseOperation): Boolean = synchronized(PROCESS_LOCK) {
        clearStaleLocked()
        if (prefs.getBoolean(KEY_ACTIVE, false)) return@synchronized false
        val leaseToken = UUID.randomUUID().toString()
        val committed = prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putBoolean(KEY_FOREGROUND_ACTIVE, operation.isForegroundProvisioning)
            .putString(KEY_PROCESS_INSTANCE, processInstanceId)
            .putString(KEY_LEASE_TOKEN, leaseToken)
            .putString(KEY_OPERATION, operation.name)
            .putLong(KEY_ACQUIRED_AT, System.currentTimeMillis())
            .commit()
        if (committed) heldLeaseToken = leaseToken
        committed
    }

    fun isHeldByLiveProcess(): Boolean = synchronized(PROCESS_LOCK) {
        clearStaleLocked()
        prefs.getBoolean(KEY_ACTIVE, false)
    }

    fun isForegroundProvisioningActive(): Boolean = synchronized(PROCESS_LOCK) {
        clearStaleLocked()
        prefs.getBoolean(KEY_ACTIVE, false) &&
            prefs.getBoolean(KEY_FOREGROUND_ACTIVE, false)
    }

    fun release() = synchronized(PROCESS_LOCK) {
        val leaseToken = heldLeaseToken ?: return@synchronized
        if (prefs.getString(KEY_PROCESS_INSTANCE, null) != processInstanceId) return@synchronized
        if (prefs.getString(KEY_LEASE_TOKEN, null) != leaseToken) return@synchronized
        clearLocked()
        heldLeaseToken = null
    }

    fun clearStaleLease() = synchronized(PROCESS_LOCK) {
        clearStaleLocked()
    }

    private fun clearStaleLocked() {
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return
        if (prefs.getString(KEY_PROCESS_INSTANCE, null) == processInstanceId) return
        clearLocked()
    }

    private fun clearLocked() {
        prefs.edit()
            .putBoolean(KEY_ACTIVE, false)
            .putBoolean(KEY_FOREGROUND_ACTIVE, false)
            .remove(KEY_PROCESS_INSTANCE)
            .remove(KEY_LEASE_TOKEN)
            .remove(LEGACY_KEY_OWNER)
            .remove(KEY_OPERATION)
            .remove(KEY_ACQUIRED_AT)
            .commit()
    }

    companion object {
        private val PROCESS_LOCK = Any()
        private const val KEY_ACTIVE = "provisioning_operation_lease_active"
        private const val KEY_FOREGROUND_ACTIVE = "foreground_provisioning_active"
        private const val KEY_PROCESS_INSTANCE = "provisioning_operation_lease_process_instance"
        private const val KEY_LEASE_TOKEN = "provisioning_operation_lease_token"
        private const val LEGACY_KEY_OWNER = "provisioning_operation_lease_owner"
        private const val KEY_OPERATION = "provisioning_operation_lease_operation"
        private const val KEY_ACQUIRED_AT = "provisioning_operation_lease_acquired_at"

        fun fromContext(context: Context): ProvisioningOperationLease = ProvisioningOperationLease(
            context.applicationContext.getSharedPreferences(
                CapacityRetryRepository.PREFS_NAME,
                Context.MODE_PRIVATE,
            ),
        )
    }
}

private object ProcessLeaseIdentity {
    val id: String = UUID.randomUUID().toString()
}

internal fun shouldEnqueueCapacityRetry(
    session: CapacityRetrySession,
    topLevelProvisioningRunning: Boolean,
    provisioningJobActive: Boolean,
    provisioningLeaseHeld: Boolean,
): Boolean =
    session.state in setOf(CapacityRetryState.WAITING_FOR_RETRY, CapacityRetryState.ACTIVE) &&
        session.instanceOcid.isNullOrBlank() &&
        !session.requiresUserAction &&
        !topLevelProvisioningRunning &&
        !provisioningJobActive &&
        !provisioningLeaseHeld

internal fun shouldRunCapacityRetryWorker(
    session: CapacityRetrySession,
    provisioningLeaseHeld: Boolean,
): Boolean =
    session.state in setOf(CapacityRetryState.WAITING_FOR_RETRY, CapacityRetryState.ACTIVE) &&
        session.instanceOcid.isNullOrBlank() &&
        !session.requiresUserAction &&
        !provisioningLeaseHeld
