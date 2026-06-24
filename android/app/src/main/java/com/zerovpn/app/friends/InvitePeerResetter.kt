package com.zerovpn.app.friends

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.wireguard.crypto.KeyPair
import com.zerovpn.app.vpn.ConfiguredExit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class InvitePeerResetter(
    private val context: Context,
) {
    suspend fun resetPeer(
        ownerExit: ConfiguredExit,
        slot: InviteSlot,
        sshPrivateKey: String,
        beforeServerMutation: (String) -> Unit = {},
    ): InvitePeerResetResult = withContext(Dispatchers.IO) {
        val oldPublicKey = slot.peerPublicKey?.takeIf { it.isNotBlank() }
            ?: return@withContext InvitePeerResetResult.Failed("This invite slot does not have an old peer public key.")
        val tunnelIp = slot.tunnelIp?.takeIf { it.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) }
            ?: return@withContext InvitePeerResetResult.Failed("This invite slot does not have a valid tunnel IP.")
        val username = ownerExit.sshUsername?.takeIf { it.isNotBlank() }
            ?: return@withContext InvitePeerResetResult.Failed("This exit is missing its SSH username.")
        val serverPublicKey = ownerExit.serverPublicKey?.takeIf { it.isNotBlank() }
            ?: parseWireGuardValue(ownerExit.wireGuardConfig, "Peer", "PublicKey")
            ?: return@withContext InvitePeerResetResult.Failed("This exit is missing its WireGuard server public key.")
        val host = ownerExit.publicIp.takeIf { it.isNotBlank() }
            ?: return@withContext InvitePeerResetResult.Failed("This exit is missing its public IP.")
        val keyPair = KeyPair()
        val newPrivateKey = keyPair.privateKey.toBase64()
        val newPublicKey = keyPair.publicKey.toBase64()
        val clientConfig = buildClientConfig(
            privateKey = newPrivateKey,
            tunnelIp = tunnelIp,
            serverPublicKey = serverPublicKey,
            endpointHost = ownerExit.endpointHost.takeIf { it.isNotBlank() } ?: ownerExit.publicIp,
            endpointPort = ownerExit.endpointPort.takeIf { it > 0 } ?: ownerExit.wireGuardPort,
        )
        runCatching { beforeServerMutation(clientConfig) }.getOrElse {
            return@withContext InvitePeerResetResult.Failed("Could not prepare local secure storage for the new invite.")
        }

        val keyFile = File(context.cacheDir, "zerovpn_invite_reset_${System.currentTimeMillis()}.key")
        var session: Session? = null
        try {
            keyFile.writeText(sshPrivateKey)
            val jsch = JSch()
            jsch.addIdentity(keyFile.absolutePath)
            session = jsch.getSession(username, host, 22).apply {
                setConfig("StrictHostKeyChecking", "no")
                setConfig("UserKnownHostsFile", "/dev/null")
                setConfig("PreferredAuthentications", "publickey")
                timeout = 15_000
                setServerAliveInterval(15_000)
                setServerAliveCountMax(2)
                connect(15_000)
            }
            val command = runCommand(session, resetCommand(oldPublicKey, newPublicKey, tunnelIp))
            if (command.exitCode != 0) {
                return@withContext InvitePeerResetResult.Failed(
                    command.stderr.ifBlank { "Could not reset the invite peer on the exit." },
                )
            }
            InvitePeerResetResult.Success(
                newPeerPublicKey = newPublicKey,
                tunnelIp = tunnelIp,
                clientConfig = clientConfig,
            )
        } catch (e: Exception) {
            InvitePeerResetResult.Failed(e.message ?: "Could not reset the invite peer on the exit.")
        } finally {
            session?.disconnect()
            runCatching { keyFile.delete() }
        }
    }

    private fun resetCommand(oldPublicKey: String, newPublicKey: String, tunnelIp: String): String =
        "OLD_PUBLIC_KEY=${oldPublicKey.shellQuote()} " +
            "NEW_PUBLIC_KEY=${newPublicKey.shellQuote()} " +
            "TUNNEL_IP=${tunnelIp.shellQuote()} bash -s << 'ZEROVPN_RESET_SCRIPT'\n" +
            RESET_SCRIPT +
            "\nZEROVPN_RESET_SCRIPT"

    private fun runCommand(session: Session, command: String): SshCommandResult {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val stdoutBytes = java.io.ByteArrayOutputStream()
        val stderrBytes = java.io.ByteArrayOutputStream()
        val stdout = channel.inputStream
        val stderr = channel.getErrStream()
        return try {
            channel.connect(30_000)
            val buffer = ByteArray(4096)
            while (!channel.isClosed || stdout.available() > 0 || stderr.available() > 0) {
                while (stdout.available() > 0) {
                    val read = stdout.read(buffer)
                    if (read > 0) stdoutBytes.write(buffer, 0, read)
                }
                while (stderr.available() > 0) {
                    val read = stderr.read(buffer)
                    if (read > 0) stderrBytes.write(buffer, 0, read)
                }
                if (channel.isClosed && stdout.available() == 0 && stderr.available() == 0) break
                Thread.sleep(50)
            }
            SshCommandResult(
                exitCode = channel.exitStatus,
                stdout = stdoutBytes.toString(),
                stderr = stderrBytes.toString(),
            )
        } finally {
            channel.disconnect()
        }
    }

    private fun buildClientConfig(
        privateKey: String,
        tunnelIp: String,
        serverPublicKey: String,
        endpointHost: String,
        endpointPort: Int,
    ): String =
        "[Interface]\nPrivateKey = $privateKey\n" +
            "Address = $tunnelIp/32\nDNS = 1.1.1.1\n\n" +
            "[Peer]\nPublicKey = $serverPublicKey\n" +
            "Endpoint = $endpointHost:$endpointPort\n" +
            "AllowedIPs = 0.0.0.0/0\nPersistentKeepalive = 25\n"

    private fun parseWireGuardValue(config: String, sectionName: String, key: String): String? {
        val start = config.indexOf("[$sectionName]")
        if (start < 0) return null
        val next = config.indexOf("\n[", start + sectionName.length + 2)
        val section = if (next >= 0) config.substring(start, next) else config.substring(start)
        return section.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(key, ignoreCase = true) && it.contains("=") }
            ?.substringAfter("=")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.shellQuote(): String = "'${replace("'", "'\"'\"'")}'"

    private data class SshCommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private companion object {
        val RESET_SCRIPT = """
set -euo pipefail
CONF=/etc/wireguard/wg0.conf
TMP_IN=${'$'}(mktemp)
TMP_OUT=${'$'}(mktemp)
cleanup() {
  rm -f "${'$'}TMP_IN" "${'$'}TMP_OUT"
}
trap cleanup EXIT

sudo test -f "${'$'}CONF"
sudo cat "${'$'}CONF" > "${'$'}TMP_IN"
python3 - "${'$'}TMP_IN" "${'$'}TMP_OUT" <<'PY'
import os
import re
import sys

old_key = os.environ["OLD_PUBLIC_KEY"]
new_key = os.environ["NEW_PUBLIC_KEY"]
tunnel_ip = os.environ["TUNNEL_IP"]
src, dst = sys.argv[1], sys.argv[2]
with open(src, "r", encoding="utf-8") as fh:
    lines = fh.read().splitlines()

blocks = []
current = []
for line in lines:
    if re.match(r"^\s*\[[^]]+\]\s*$", line) and current:
        blocks.append(current)
        current = [line]
    else:
        current.append(line)
if current:
    blocks.append(current)

kept = []
found_old = False
for block in blocks:
    header = block[0].strip().lower() if block else ""
    public_key = None
    if header == "[peer]":
        for line in block[1:]:
            if line.strip().lower().startswith("publickey") and "=" in line:
                public_key = line.split("=", 1)[1].strip()
                break
    if header == "[peer]" and public_key == old_key:
        found_old = True
        continue
    if header == "[peer]" and public_key == new_key:
        continue
    kept.append(block)

if not found_old:
    raise SystemExit("old invite peer was not found in wg0.conf")

new_block = [
    "",
    "[Peer]",
    f"PublicKey = {new_key}",
    f"AllowedIPs = {tunnel_ip}/32",
]
out_lines = []
for block in kept:
    if out_lines and out_lines[-1] != "":
        out_lines.append("")
    out_lines.extend(block)
out_lines.extend(new_block)
with open(dst, "w", encoding="utf-8") as fh:
    fh.write("\n".join(out_lines).rstrip() + "\n")
PY

sudo install -m 600 -o root -g root "${'$'}TMP_OUT" "${'$'}CONF"
sudo wg set wg0 peer "${'$'}OLD_PUBLIC_KEY" remove || true
sudo systemctl restart wg-quick@wg0
sudo wg set wg0 peer "${'$'}NEW_PUBLIC_KEY" allowed-ips "${'$'}TUNNEL_IP/32"
sudo wg show wg0 peers | grep -Fx "${'$'}NEW_PUBLIC_KEY" >/dev/null
if sudo wg show wg0 peers | grep -Fx "${'$'}OLD_PUBLIC_KEY" >/dev/null; then
  echo "old invite peer is still active after reset" >&2
  exit 1
fi
sudo wg show wg0 latest-handshakes >/dev/null
""".trimIndent()
    }
}

sealed interface InvitePeerResetResult {
    data class Success(
        val newPeerPublicKey: String,
        val tunnelIp: String,
        val clientConfig: String,
    ) : InvitePeerResetResult

    data class Failed(val message: String) : InvitePeerResetResult
}
