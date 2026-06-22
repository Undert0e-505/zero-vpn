package com.zerovpn.app.volunteer.tun2socks

import java.io.File

data class HevTun2SocksConfig(
    val socksHost: String,
    val socksPort: Int,
    val tunnelMtu: Int = 1500,
    val tunnelIpv4: String = "10.111.0.2",
    val socksUdpMode: String = "tcp",
    val logLevel: String = "info",
) {
    fun writeTo(file: File): File {
        file.parentFile?.mkdirs()
        file.writeText(toYaml())
        return file
    }

    private fun toYaml(): String = """
        tunnel:
          name: tun0
          mtu: $tunnelMtu
          multi-queue: false
          ipv4: $tunnelIpv4

        socks5:
          port: $socksPort
          address: $socksHost
          udp: '$socksUdpMode'

        misc:
          task-stack-size: 86016
          connect-timeout: 10000
          tcp-read-write-timeout: 300000
          log-file: stderr
          log-level: $logLevel
    """.trimIndent()
}
