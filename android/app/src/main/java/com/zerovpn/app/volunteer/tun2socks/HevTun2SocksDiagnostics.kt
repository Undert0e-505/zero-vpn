package com.zerovpn.app.volunteer.tun2socks

data class HevTun2SocksDiagnostics(
    val state: String = "Idle",
    val configPath: String? = null,
    val socksTarget: String = "127.0.0.1:9050",
    val running: Boolean = false,
    val txPackets: Long? = null,
    val txBytes: Long? = null,
    val rxPackets: Long? = null,
    val rxBytes: Long? = null,
    val lastError: String? = null,
) {
    fun toDebugText(): String = buildString {
        appendLine("hevState=$state")
        appendLine("hevRunning=$running")
        appendLine("hevConfigPath=${configPath ?: "N/A"}")
        appendLine("socksTarget=$socksTarget")
        appendLine("hevTxPackets=${txPackets ?: "N/A"}")
        appendLine("hevTxBytes=${txBytes ?: "N/A"}")
        appendLine("hevRxPackets=${rxPackets ?: "N/A"}")
        appendLine("hevRxBytes=${rxBytes ?: "N/A"}")
        appendLine("hevLastError=${lastError ?: "N/A"}")
    }.trimEnd()
}
