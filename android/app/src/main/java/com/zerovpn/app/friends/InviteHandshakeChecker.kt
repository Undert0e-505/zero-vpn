package com.zerovpn.app.friends

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.zerovpn.app.vpn.ConfiguredExit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class InviteHandshakeChecker(
    private val context: Context,
) {
    suspend fun queryLatestHandshakes(exit: ConfiguredExit): HandshakeQueryResult =
        withContext(Dispatchers.IO) {
            val host = exit.publicIp.takeIf { it.isNotBlank() }
                ?: return@withContext HandshakeQueryResult.MissingSshCredentials
            val username = exit.sshUsername?.takeIf { it.isNotBlank() }
                ?: return@withContext HandshakeQueryResult.MissingSshCredentials
            val privateKey = exit.sshPrivateKey?.takeIf { it.isNotBlank() }
                ?: return@withContext HandshakeQueryResult.MissingSshCredentials

            val keyFile = File(context.cacheDir, "zerovpn_friend_claim_${System.currentTimeMillis()}.key")
            var session: Session? = null
            try {
                keyFile.writeText(privateKey)
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
                val command = runCommand(session, "sudo wg show wg0 latest-handshakes")
                if (command.exitCode != 0) {
                    return@withContext HandshakeQueryResult.Failed(
                        command.stderr.ifBlank { "Could not query WireGuard latest handshakes." },
                    )
                }
                HandshakeQueryResult.Success(parseLatestHandshakes(command.stdout))
            } catch (e: Exception) {
                HandshakeQueryResult.Failed(e.message ?: "Could not query WireGuard latest handshakes.")
            } finally {
                session?.disconnect()
                runCatching { keyFile.delete() }
            }
        }

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

    private fun parseLatestHandshakes(output: String): Map<String, Long> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                val key = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val timestamp = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                key to timestamp
            }
            .toMap()

    private data class SshCommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}

sealed interface HandshakeQueryResult {
    data class Success(val latestHandshakes: Map<String, Long>) : HandshakeQueryResult
    data class Failed(val message: String) : HandshakeQueryResult
    data object MissingSshCredentials : HandshakeQueryResult
}
