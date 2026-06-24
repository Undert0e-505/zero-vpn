package com.zerovpn.app.volunteer.tun2socks

import java.io.File

data class HevTun2SocksConfig(
    val socksHost: String,
    val socksPort: Int,
    val tunnelMtu: Int = 1500,
    val mappedDnsAddress: String = "198.18.0.2",
    val logLevel: String = "info",
) {
    fun writeTo(file: File): File {
        file.parentFile?.mkdirs()
        file.writeText(toYaml())
        return file
    }

    private fun toYaml(): String = """
        tunnel:
          mtu: $tunnelMtu

        socks5:
          port: $socksPort
          address: '$socksHost'

        mapdns:
          address: $mappedDnsAddress
          port: 53
          network: 100.64.0.0
          netmask: 255.192.0.0
          cache-size: 10000

        misc:
          task-stack-size: 86016
          connect-timeout: 10000
          tcp-read-write-timeout: 300000
          log-file: stderr
          log-level: $logLevel
    """.trimIndent()
}
