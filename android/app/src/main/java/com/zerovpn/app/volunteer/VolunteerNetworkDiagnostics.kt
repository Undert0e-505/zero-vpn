package com.zerovpn.app.volunteer

import java.text.DateFormat
import java.util.Date

data class VolunteerNetworkDiagnostics(
    val transport: String = "Embedded Tor",
    val socksHost: String = "127.0.0.1",
    val socksPort: Int? = null,
    val bootstrapProgress: Int? = null,
    val lastTestUrl: String? = null,
    val lastTestStatus: String? = null,
    val lastExitIp: String? = null,
    val lastError: String? = null,
    val startedAt: Long? = null,
    val stoppedAt: Long? = null,
) {
    fun toDebugText(): String = buildString {
        appendLine("transport=$transport")
        appendLine("socksHost=$socksHost")
        appendLine("socksPort=${socksPort ?: "N/A"}")
        appendLine("bootstrapProgress=${bootstrapProgress?.let { "$it%" } ?: "N/A"}")
        appendLine("lastTestUrl=${lastTestUrl ?: "N/A"}")
        appendLine("lastTestStatus=${lastTestStatus ?: "N/A"}")
        appendLine("lastExitIp=${lastExitIp ?: "N/A"}")
        appendLine("lastError=${lastError ?: "N/A"}")
        appendLine("startedAt=${startedAt?.toDisplayTime() ?: "N/A"}")
        appendLine("stoppedAt=${stoppedAt?.toDisplayTime() ?: "N/A"}")
    }.trimEnd()
}

private fun Long.toDisplayTime(): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(this))
