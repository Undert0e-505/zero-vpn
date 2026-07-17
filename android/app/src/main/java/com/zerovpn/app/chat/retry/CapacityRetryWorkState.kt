package com.zerovpn.app.chat.retry

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.TimeUnit

enum class CapacityRetrySchedulerState {
    CHECKING,
    ENQUEUED,
    RUNNING,
    CANCELLED,
    FAILED,
    SUCCEEDED,
    NOT_FOUND,
}

data class CapacityRetrySchedulerStatus(
    val state: CapacityRetrySchedulerState,
    val detail: String,
    val observedAtUtc: String,
    val workId: String? = null,
    val runAttemptCount: Int? = null,
    val reconciliationMessage: String? = null,
) {
    val displayLabel: String
        get() = when (state) {
            CapacityRetrySchedulerState.CHECKING -> "Checking"
            CapacityRetrySchedulerState.ENQUEUED -> "Enqueued"
            CapacityRetrySchedulerState.RUNNING -> "Running"
            CapacityRetrySchedulerState.CANCELLED -> "Cancelled"
            CapacityRetrySchedulerState.FAILED -> "Failed"
            CapacityRetrySchedulerState.SUCCEEDED -> "Succeeded"
            CapacityRetrySchedulerState.NOT_FOUND -> "Not found"
        }

    val hasScheduledWork: Boolean
        get() = state == CapacityRetrySchedulerState.ENQUEUED ||
            state == CapacityRetrySchedulerState.RUNNING

    companion object {
        fun checking(now: Instant = Instant.now()): CapacityRetrySchedulerStatus =
            CapacityRetrySchedulerStatus(
                state = CapacityRetrySchedulerState.CHECKING,
                detail = "Checking Android's durable background-work database.",
                observedAtUtc = now.toString(),
            )
    }
}

internal fun capacityRetrySchedulerStatus(
    workInfos: List<WorkInfo>,
    observedAt: Instant = Instant.now(),
): CapacityRetrySchedulerStatus {
    val selected = workInfos.firstOrNull { it.state == WorkInfo.State.RUNNING }
        ?: workInfos.firstOrNull {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
        }
        ?: workInfos.firstOrNull { it.state == WorkInfo.State.FAILED }
        ?: workInfos.firstOrNull { it.state == WorkInfo.State.CANCELLED }
        ?: workInfos.firstOrNull { it.state == WorkInfo.State.SUCCEEDED }

    if (selected == null) {
        return CapacityRetrySchedulerStatus(
            state = CapacityRetrySchedulerState.NOT_FOUND,
            detail = "Background work is not present in WorkManager.",
            observedAtUtc = observedAt.toString(),
        )
    }

    val schedulerState = when (selected.state) {
        WorkInfo.State.RUNNING -> CapacityRetrySchedulerState.RUNNING
        WorkInfo.State.ENQUEUED,
        WorkInfo.State.BLOCKED,
        -> CapacityRetrySchedulerState.ENQUEUED
        WorkInfo.State.CANCELLED -> CapacityRetrySchedulerState.CANCELLED
        WorkInfo.State.FAILED -> CapacityRetrySchedulerState.FAILED
        WorkInfo.State.SUCCEEDED -> CapacityRetrySchedulerState.SUCCEEDED
    }
    val detail = when (selected.state) {
        WorkInfo.State.RUNNING -> "Background work is running now."
        WorkInfo.State.ENQUEUED -> "Background work is scheduled by Android."
        WorkInfo.State.BLOCKED -> "Background work is scheduled by Android and is waiting on a prerequisite."
        WorkInfo.State.CANCELLED -> "Background work was cancelled."
        WorkInfo.State.FAILED -> "Background work failed."
        WorkInfo.State.SUCCEEDED -> "Background work completed."
    }
    return CapacityRetrySchedulerStatus(
        state = schedulerState,
        detail = detail,
        observedAtUtc = observedAt.toString(),
        workId = selected.id.toString(),
        runAttemptCount = selected.runAttemptCount,
    )
}

internal enum class CapacityRetryReconciliationAction {
    NONE,
    KEEP,
    REENQUEUE,
    PAUSE_AUTH_REQUIRED,
}

internal fun decideCapacityRetryReconciliation(
    session: CapacityRetrySession,
    schedulerState: CapacityRetrySchedulerState,
    now: Instant,
    schedulingAllowed: Boolean,
    credentialsAvailable: Boolean,
): CapacityRetryReconciliationAction {
    if (session.state !in setOf(CapacityRetryState.WAITING_FOR_RETRY, CapacityRetryState.ACTIVE)) {
        return CapacityRetryReconciliationAction.NONE
    }
    val deadline = runCatching { Instant.parse(session.deadlineUtc) }.getOrNull()
        ?: return CapacityRetryReconciliationAction.NONE
    if (!now.isBefore(deadline)) return CapacityRetryReconciliationAction.NONE
    if (
        schedulerState == CapacityRetrySchedulerState.ENQUEUED ||
        schedulerState == CapacityRetrySchedulerState.RUNNING
    ) {
        return CapacityRetryReconciliationAction.KEEP
    }
    if (!schedulingAllowed) return CapacityRetryReconciliationAction.NONE
    if (!credentialsAvailable) return CapacityRetryReconciliationAction.PAUSE_AUTH_REQUIRED
    return CapacityRetryReconciliationAction.REENQUEUE
}

internal class CapacityRetryWorkMonitor(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
) {
    fun liveData(uniqueWorkName: String): LiveData<List<WorkInfo>> =
        workManager.getWorkInfosForUniqueWorkLiveData(uniqueWorkName)

    suspend fun query(uniqueWorkName: String): List<WorkInfo> = withContext(Dispatchers.IO) {
        workManager.getWorkInfosForUniqueWork(uniqueWorkName).get(10, TimeUnit.SECONDS)
    }
}
