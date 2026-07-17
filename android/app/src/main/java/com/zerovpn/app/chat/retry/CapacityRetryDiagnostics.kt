package com.zerovpn.app.chat.retry

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Clock
import java.time.Instant

data class CapacityRetryDiagnosticEntry(
    val sessionId: String,
    val timestampUtc: String,
    val message: String,
    val failureDiagnostics: LaunchFailureDiagnostics? = null,
)

class CapacityRetryDiagnosticLog(
    private val prefs: SharedPreferences,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun append(sessionId: String, message: String) {
        appendEntry(sessionId, message, failureDiagnostics = null)
    }

    fun appendFailure(sessionId: String, diagnostics: LaunchFailureDiagnostics) {
        appendEntry(
            sessionId = sessionId,
            message = "Detailed launch diagnostics captured: ${diagnostics.summaryLine()}",
            failureDiagnostics = diagnostics,
        )
    }

    private fun appendEntry(
        sessionId: String,
        message: String,
        failureDiagnostics: LaunchFailureDiagnostics?,
    ) = synchronized(DIAGNOSTIC_LOCK) {
        if (sessionId.isBlank()) return@synchronized
        val safeMessage = sanitizeLaunchDiagnosticText(
            raw = message,
            maxLength = MAX_MESSAGE_LENGTH,
            collapseLines = true,
        )
        if (safeMessage.isBlank()) return@synchronized
        val updated = (allEntriesLocked() + CapacityRetryDiagnosticEntry(
            sessionId = sessionId,
            timestampUtc = Instant.now(clock).toString(),
            message = safeMessage,
            failureDiagnostics = failureDiagnostics,
        )).takeLast(MAX_ENTRIES)
        prefs.edit().putString(KEY_ENTRIES, entriesToJson(updated)).commit()
    }

    fun entries(sessionId: String? = null, limit: Int = MAX_ENTRIES): List<CapacityRetryDiagnosticEntry> =
        synchronized(DIAGNOSTIC_LOCK) {
            allEntriesLocked()
                .filter { sessionId == null || it.sessionId == sessionId }
                .takeLast(limit.coerceAtLeast(0))
        }

    private fun allEntriesLocked(): List<CapacityRetryDiagnosticEntry> =
        entriesFromJson(prefs.getString(KEY_ENTRIES, null))

    companion object {
        const val KEY_ENTRIES = "private_chat_capacity_retry_worker_diagnostics_json"
        private const val MAX_ENTRIES = 200
        private const val MAX_MESSAGE_LENGTH = 520
        private val DIAGNOSTIC_LOCK = Any()

        fun fromContext(context: Context): CapacityRetryDiagnosticLog =
            CapacityRetryDiagnosticLog(
                context.applicationContext.getSharedPreferences(
                    CapacityRetryRepository.PREFS_NAME,
                    Context.MODE_PRIVATE,
                ),
            )
    }
}

private fun entriesToJson(entries: List<CapacityRetryDiagnosticEntry>): String =
    JSONArray().also { array ->
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("sessionId", entry.sessionId)
                    .put("timestampUtc", entry.timestampUtc)
                    .put("message", entry.message)
                    .put(
                        "failureDiagnostics",
                        entry.failureDiagnostics?.toJson() ?: JSONObject.NULL,
                    ),
            )
        }
    }.toString()

private fun entriesFromJson(raw: String?): List<CapacityRetryDiagnosticEntry> = runCatching {
    if (raw.isNullOrBlank()) return emptyList()
    val array = JSONArray(raw)
    buildList {
        for (index in 0 until array.length()) {
            val json = array.getJSONObject(index)
            val sessionId = json.optString("sessionId").takeIf(String::isNotBlank) ?: continue
            val timestampUtc = json.optString("timestampUtc").takeIf(String::isNotBlank) ?: continue
            val message = json.optString("message").takeIf(String::isNotBlank) ?: continue
            val failureDiagnostics = json.optJSONObject("failureDiagnostics")
                ?.let(LaunchFailureDiagnostics::fromJson)
            add(CapacityRetryDiagnosticEntry(sessionId, timestampUtc, message, failureDiagnostics))
        }
    }
}.getOrDefault(emptyList())
