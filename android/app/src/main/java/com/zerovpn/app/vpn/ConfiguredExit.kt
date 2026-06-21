package com.zerovpn.app.vpn

data class ConfiguredExit(
    val id: String,
    val name: String,
    val publicIp: String,
    val wireGuardPort: Int,
    val region: String,
    val wireGuardConfig: String,
    val serverPublicKey: String? = null,
    val serverPeerPublicKey: String? = null,
    val clientPublicKey: String? = null,
)
