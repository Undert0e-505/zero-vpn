package com.zerovpn.app.friends

import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import java.security.MessageDigest

data class ParsedWireGuardInvite(
    val rawConfig: String,
    val privateKey: String,
    val clientPublicKey: String?,
    val address: String,
    val peerPublicKey: String,
    val endpoint: String,
    val allowedIps: String,
    val dns: String? = null,
    val persistentKeepalive: String? = null,
    val presharedKey: String? = null,
) {
    val endpointHost: String = endpoint.substringBeforeLast(":").removePrefix("[").removeSuffix("]")
        .takeIf { it.isNotBlank() } ?: endpoint
    val endpointPort: Int = endpoint.substringAfterLast(":", "51820").toIntOrNull() ?: 51820
    val configHash: String = rawConfig.sha256()
}

object WireGuardInviteParser {
    fun parse(raw: String): Result<ParsedWireGuardInvite> = runCatching {
        val normalized = raw.trim().replace("\r\n", "\n")
        val sections = parseSections(normalized)
        val iface = sections["Interface"] ?: error("Missing [Interface]")
        val peer = sections["Peer"] ?: error("Missing [Peer]")
        ParsedWireGuardInvite(
            rawConfig = normalized,
            privateKey = iface.required("PrivateKey").also { privateKey ->
                Key.fromBase64(privateKey)
            },
            clientPublicKey = runCatching {
                KeyPair(Key.fromBase64(iface.required("PrivateKey"))).publicKey.toBase64()
            }.getOrNull(),
            address = iface.required("Address"),
            peerPublicKey = peer.required("PublicKey"),
            endpoint = peer.required("Endpoint"),
            allowedIps = peer.required("AllowedIPs"),
            dns = iface["DNS"],
            persistentKeepalive = peer["PersistentKeepalive"],
            presharedKey = peer["PresharedKey"],
        )
    }

    private fun parseSections(raw: String): Map<String, Map<String, String>> {
        val sections = linkedMapOf<String, MutableMap<String, String>>()
        var current: String? = null
        raw.lineSequence().forEach { original ->
            val line = original.trim()
            if (line.isBlank() || line.startsWith("#") || line.startsWith(";")) return@forEach
            if (line.startsWith("[") && line.endsWith("]")) {
                current = line.removePrefix("[").removeSuffix("]").trim()
                sections.getOrPut(current.orEmpty()) { linkedMapOf() }
                return@forEach
            }
            val section = current ?: return@forEach
            val key = line.substringBefore("=", "").trim()
            val value = line.substringAfter("=", "").trim()
            if (key.isNotBlank() && value.isNotBlank()) {
                sections.getOrPut(section) { linkedMapOf() }[key] = value
            }
        }
        return sections
    }

    private fun Map<String, String>.required(key: String): String =
        this[key]?.takeIf { it.isNotBlank() } ?: error("Missing $key")
}

fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
