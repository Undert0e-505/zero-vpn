package com.zerovpn.app.volunteer.vpn

data class VolunteerTunConfig(
    val sessionName: String = "ZeroVPN Volunteer Spike",
    val address: String = "10.111.0.2",
    val prefixLength: Int = 32,
    val route: String = "0.0.0.0",
    val routePrefixLength: Int = 0,
    val dnsServer: String = "198.18.0.2",
    val mtu: Int = 1500,
) {
    val addressDisplay: String = "$address/$prefixLength"
    val routeDisplay: String = "$route/$routePrefixLength"
}
