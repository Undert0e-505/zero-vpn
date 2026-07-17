package com.zerovpn.app.chat.retry

import org.json.JSONObject
import java.util.Collections
import java.util.IdentityHashMap

internal enum class LaunchProgress {
    PREPARING_REQUEST,
    SIGNING_REQUEST,
    REQUEST_READY,
    TRANSMISSION_STARTED,
    RESPONSE_RECEIVED,
}

data class LaunchFailureDiagnostics(
    val exceptionClass: String,
    val safeExceptionMessage: String,
    val rootCauseClass: String?,
    val safeRootCauseMessage: String?,
    val failingOperation: String,
    val failingComponent: String?,
    val requestConstructionCompleted: Boolean?,
    val requestSigningCompleted: Boolean?,
    val transmissionStarted: Boolean?,
    val responseHeadersReceived: Boolean?,
    val redactedRequestId: String?,
    val retryTokenAbbreviated: String,
    val sessionIdAbbreviated: String,
    val progress: String,
    val safeStackTrace: String,
) {
    fun safeCategory(): String = when {
        transmissionStarted == true && responseHeadersReceived != true -> "transmission-failure"
        transmissionStarted == true && responseHeadersReceived == true -> "ambiguous-response-processing"
        progress == LaunchProgress.SIGNING_REQUEST.name -> "signing-error"
        exceptionClass.contains("JSONException", ignoreCase = true) ||
            rootCauseClass?.contains("JSONException", ignoreCase = true) == true -> "json-construction-error"
        transmissionStarted == false -> "local-preparation-error"
        else -> "ambiguous-exception"
    }

    fun summaryLine(): String = buildString {
        append(safeCategory())
        append(" at ")
        append(failingOperation)
        append(" [")
        append(progress)
        append("]: ")
        append(exceptionClass)
        if (safeExceptionMessage.isNotBlank()) {
            append(" - ")
            append(safeExceptionMessage)
        }
    }.take(MAX_SUMMARY_LENGTH)

    fun fullDiagnosticBlock(): String = buildString {
        appendLine("Launch failure diagnostics")
        appendLine("exceptionClass=$exceptionClass")
        appendLine("safeExceptionMessage=$safeExceptionMessage")
        appendLine("rootCauseClass=${rootCauseClass ?: "N/A"}")
        appendLine("safeRootCauseMessage=${safeRootCauseMessage ?: "N/A"}")
        appendLine("failingOperation=$failingOperation")
        appendLine("failingComponent=${failingComponent ?: "N/A"}")
        appendLine("progress=$progress")
        appendLine("requestConstructionCompleted=${requestConstructionCompleted ?: "unknown"}")
        appendLine("requestSigningCompleted=${requestSigningCompleted ?: "unknown"}")
        appendLine("transmissionStarted=${transmissionStarted ?: "unknown"}")
        appendLine("responseHeadersReceived=${responseHeadersReceived ?: "unknown"}")
        appendLine("redactedRequestId=${redactedRequestId ?: "N/A"}")
        appendLine("retryTokenAbbreviated=$retryTokenAbbreviated")
        appendLine("sessionIdAbbreviated=$sessionIdAbbreviated")
        appendLine("redactedStackTrace:")
        append(safeStackTrace)
    }

    internal fun toJson(): JSONObject = JSONObject()
        .put("exceptionClass", exceptionClass)
        .put("safeExceptionMessage", safeExceptionMessage)
        .putNullable("rootCauseClass", rootCauseClass)
        .putNullable("safeRootCauseMessage", safeRootCauseMessage)
        .put("failingOperation", failingOperation)
        .putNullable("failingComponent", failingComponent)
        .putNullable("requestConstructionCompleted", requestConstructionCompleted)
        .putNullable("requestSigningCompleted", requestSigningCompleted)
        .putNullable("transmissionStarted", transmissionStarted)
        .putNullable("responseHeadersReceived", responseHeadersReceived)
        .putNullable("redactedRequestId", redactedRequestId)
        .put("retryTokenAbbreviated", retryTokenAbbreviated)
        .put("sessionIdAbbreviated", sessionIdAbbreviated)
        .put("progress", progress)
        .put("safeStackTrace", safeStackTrace)

    companion object {
        private const val MAX_SAFE_MESSAGE_LENGTH = 320
        private const val MAX_SAFE_STACK_LENGTH = 12_000
        private const val MAX_SUMMARY_LENGTH = 480

        internal fun capture(
            error: Throwable,
            progress: LaunchProgress? = null,
            failingOperation: String = "unknown-launch-operation",
            requestConstructionCompleted: Boolean? = null,
            requestSigningCompleted: Boolean? = null,
            transmissionStarted: Boolean? = null,
            responseHeadersReceived: Boolean? = null,
            redactedRequestId: String? = null,
            retryToken: String = "",
            sessionId: String = "",
        ): LaunchFailureDiagnostics {
            val rootCause = deepestCause(error)
            val component = error.stackTrace
                .firstOrNull { frame -> frame.className.startsWith("com.zerovpn.app.") }
                ?.let { frame ->
                    buildString {
                        append(frame.className.substringAfterLast('.'))
                        append('.')
                        append(frame.methodName)
                        if (frame.lineNumber > 0) append(":${frame.lineNumber}")
                    }
                }
            return LaunchFailureDiagnostics(
                exceptionClass = error.javaClass.simpleName.ifBlank { error.javaClass.name },
                safeExceptionMessage = sanitizeLaunchDiagnosticText(
                    error.message ?: "No exception message.",
                    MAX_SAFE_MESSAGE_LENGTH,
                    collapseLines = true,
                ),
                rootCauseClass = rootCause
                    .takeUnless { it === error }
                    ?.javaClass
                    ?.simpleName
                    ?.ifBlank { rootCause.javaClass.name },
                safeRootCauseMessage = rootCause
                    .takeUnless { it === error }
                    ?.message
                    ?.let {
                        sanitizeLaunchDiagnosticText(
                            it,
                            MAX_SAFE_MESSAGE_LENGTH,
                            collapseLines = true,
                        )
                    },
                failingOperation = sanitizeLaunchDiagnosticText(
                    failingOperation,
                    MAX_SAFE_MESSAGE_LENGTH,
                    collapseLines = true,
                ),
                failingComponent = component,
                requestConstructionCompleted = requestConstructionCompleted,
                requestSigningCompleted = requestSigningCompleted,
                transmissionStarted = transmissionStarted,
                responseHeadersReceived = responseHeadersReceived,
                redactedRequestId = redactedRequestId?.takeLast(12),
                retryTokenAbbreviated = abbreviateDiagnosticId(retryToken),
                sessionIdAbbreviated = abbreviateDiagnosticId(sessionId),
                progress = progress?.name ?: "UNKNOWN",
                safeStackTrace = sanitizeLaunchDiagnosticText(
                    error.stackTraceToString(),
                    MAX_SAFE_STACK_LENGTH,
                    collapseLines = false,
                ),
            )
        }

        internal fun fromJson(json: JSONObject): LaunchFailureDiagnostics = LaunchFailureDiagnostics(
            exceptionClass = json.optString("exceptionClass", "UnknownException"),
            safeExceptionMessage = json.optString("safeExceptionMessage"),
            rootCauseClass = json.optNullableDiagnosticString("rootCauseClass"),
            safeRootCauseMessage = json.optNullableDiagnosticString("safeRootCauseMessage"),
            failingOperation = json.optString("failingOperation", "unknown-launch-operation"),
            failingComponent = json.optNullableDiagnosticString("failingComponent"),
            requestConstructionCompleted = json.optNullableBoolean("requestConstructionCompleted"),
            requestSigningCompleted = json.optNullableBoolean("requestSigningCompleted"),
            transmissionStarted = json.optNullableBoolean("transmissionStarted"),
            responseHeadersReceived = json.optNullableBoolean("responseHeadersReceived"),
            redactedRequestId = json.optNullableDiagnosticString("redactedRequestId"),
            retryTokenAbbreviated = json.optString("retryTokenAbbreviated", "N/A"),
            sessionIdAbbreviated = json.optString("sessionIdAbbreviated", "N/A"),
            progress = json.optString("progress", "UNKNOWN"),
            safeStackTrace = json.optString("safeStackTrace"),
        )

        private fun deepestCause(error: Throwable): Throwable {
            val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            var current = error
            visited += current
            while (current.cause != null && visited.add(current.cause!!)) {
                current = current.cause!!
            }
            return current
        }
    }
}

internal fun sanitizeLaunchDiagnosticText(
    raw: String,
    maxLength: Int,
    collapseLines: Boolean,
): String {
    var safe = raw
    SECRET_PATTERNS.forEach { pattern ->
        safe = pattern.replace(safe) { match ->
            val prefix = if (match.groups.size > 1) match.groups[1]?.value.orEmpty() else ""
            prefix + "[REDACTED]"
        }
    }
    if (collapseLines) {
        safe = safe.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString(" ")
    }
    return safe.take(maxLength)
}

private fun abbreviateDiagnosticId(value: String): String = when {
    value.isBlank() -> "N/A"
    value.length <= 16 -> value
    else -> value.take(8) + "..." + value.takeLast(6)
}

private val SECRET_PATTERNS = listOf(
    Regex(
        "-----BEGIN [^-\\r\\n]*PRIVATE KEY-----.*?-----END [^-\\r\\n]*PRIVATE KEY-----",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    ),
    Regex("(?i)(authorization\\s*[:=]\\s*)[^\\r\\n]+"),
    Regex("(?i)(\\bbearer\\s+)[A-Za-z0-9._~+/=-]+"),
    Regex("(?i)(\\bsignature\\s+algorithm\\s*=\\s*)[^\\r\\n]+"),
    Regex("(?i)((?:security[_-]?token|access[_-]?token|refresh[_-]?token|private[_-]?key|signature)\\s*[:=]\\s*)[^,;\\s\\r\\n]+"),
    Regex("\\b[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}\\b"),
)

private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
    put(name, value ?: JSONObject.NULL)

private fun JSONObject.optNullableDiagnosticString(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf(String::isNotBlank)

private fun JSONObject.optNullableBoolean(name: String): Boolean? =
    if (!has(name) || isNull(name)) null else optBoolean(name)
