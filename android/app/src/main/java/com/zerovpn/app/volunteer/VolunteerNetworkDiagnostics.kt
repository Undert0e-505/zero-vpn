package com.zerovpn.app.volunteer

import java.text.DateFormat
import java.util.Date

data class VolunteerNetworkDiagnostics(
    val transport: String = "Embedded Tor",
    val socksHost: String = "127.0.0.1",
    val socksPort: Int? = null,
    val socksActive: Boolean = false,
    val bootstrapProgress: Int? = null,
    val bootstrapPhase: String? = null,
    val bootstrapSummary: String? = null,
    val lastBootstrapMessage: String? = null,
    val bootstrapStartedAt: Long? = null,
    val bootstrapCompletedAt: Long? = null,
    val bootstrapDurationMs: Long? = null,
    val coldStart: Boolean? = null,
    val timeoutMs: Long? = null,
    val bootstrapAttempt: Int = 0,
    val lastSuccessfulBootstrapAt: Long? = null,
    val torStateDirectoryExisted: Boolean? = null,
    val torCacheDirectoryExisted: Boolean? = null,
    val lastTestUrl: String? = null,
    val lastTestStatus: String? = null,
    val lastExitIp: String? = null,
    val lastError: String? = null,
    val startedAt: Long? = null,
    val stopRequestedAt: Long? = null,
    val stoppedAt: Long? = null,
    val stopDurationMs: Long? = null,
    val torServiceBound: Boolean? = null,
    val torRunning: Boolean? = null,
    val torServiceStatus: String? = null,
    val socksProbeAfterStop: String? = null,
) {
    fun toDebugText(stateText: String? = null): String = buildString {
        stateText?.let { appendLine("state=$it") }
        appendLine("transport=$transport")
        appendLine("socksHost=$socksHost")
        appendLine("socksPort=${socksPort ?: "N/A"}")
        appendLine("socksActive=$socksActive")
        appendLine("bootstrapProgress=${bootstrapProgress?.let { "$it%" } ?: "N/A"}")
        appendLine("bootstrapPhase=${bootstrapPhase ?: "N/A"}")
        appendLine("bootstrapSummary=${bootstrapSummary ?: "N/A"}")
        appendLine("lastBootstrapMessage=${lastBootstrapMessage ?: "N/A"}")
        appendLine("bootstrapStartedAt=${bootstrapStartedAt?.toDisplayTime() ?: "N/A"}")
        appendLine("bootstrapCompletedAt=${bootstrapCompletedAt?.toDisplayTime() ?: "N/A"}")
        appendLine("bootstrapDurationMs=${bootstrapDurationMs ?: "N/A"}")
        appendLine("coldStart=${coldStart?.toString() ?: "N/A"}")
        appendLine("timeoutMs=${timeoutMs ?: "N/A"}")
        appendLine("bootstrapAttempt=$bootstrapAttempt")
        appendLine("lastSuccessfulBootstrapAt=${lastSuccessfulBootstrapAt?.toDisplayTime() ?: "N/A"}")
        appendLine("torStateDirectoryExisted=${torStateDirectoryExisted?.toString() ?: "N/A"}")
        appendLine("torCacheDirectoryExisted=${torCacheDirectoryExisted?.toString() ?: "N/A"}")
        appendLine("lastTestUrl=${lastTestUrl ?: "N/A"}")
        appendLine("lastTestStatus=${lastTestStatus ?: "N/A"}")
        appendLine("lastExitIp=${lastExitIp ?: "N/A"}")
        appendLine("lastError=${lastError ?: "N/A"}")
        appendLine("startedAt=${startedAt?.toDisplayTime() ?: "N/A"}")
        appendLine("stopRequestedAt=${stopRequestedAt?.toDisplayTime() ?: "N/A"}")
        appendLine("stoppedAt=${stoppedAt?.toDisplayTime() ?: "N/A"}")
        appendLine("stopDurationMs=${stopDurationMs ?: "N/A"}")
        appendLine("torServiceBound=${torServiceBound?.toString() ?: "N/A"}")
        appendLine("torRunning=${torRunning?.toString() ?: "N/A"}")
        appendLine("torServiceStatus=${torServiceStatus ?: "N/A"}")
        appendLine("socksProbeAfterStop=${socksProbeAfterStop ?: "N/A"}")
    }.trimEnd()
}

private fun Long.toDisplayTime(): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(this))
