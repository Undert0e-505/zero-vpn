package com.zerovpn.app.volunteer.tun2socks

data class HevNativeDiagnostics(
    val enabled: Boolean,
    val loadAttempted: Boolean,
    val loaded: Boolean,
    val libraryName: String,
    val abi: String,
    val state: HevNativeSmokeState,
    val lastLoadError: String? = null,
) {
    fun toDebugText(): String = buildString {
        appendLine("HEV Native")
        appendLine("enabled=$enabled")
        appendLine("loadAttempted=$loadAttempted")
        appendLine("loaded=$loaded")
        appendLine("libraryName=$libraryName")
        appendLine("abi=$abi")
        appendLine("state=$state")
        appendLine("lastLoadError=${lastLoadError ?: "N/A"}")
    }.trimEnd()
}
