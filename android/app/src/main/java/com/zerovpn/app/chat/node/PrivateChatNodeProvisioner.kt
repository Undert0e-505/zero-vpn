package com.zerovpn.app.chat.node

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.zerovpn.app.vpn.ConfiguredExit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class PrivateChatNodeProvisioner(
    context: Context,
) {
    private val appContext = context.applicationContext

    suspend fun install(
        exit: ConfiguredExit,
        sshPrivateKey: String,
        onEvent: (PrivateChatRemoteEvent) -> Unit,
    ): PrivateChatInstallResult = withSession(exit, sshPrivateKey) { session ->
        val remoteRoot = uploadBundle(session)
        try {
            val execution = runStreamingCommand(
                session = session,
                command = "sudo python3 '$remoteRoot/private-chat/installer/install.py' install " +
                    "--wireguard-interface wg0 --wireguard-address 10.66.66.1",
                onEvent = onEvent,
            )
            if (execution.exitCode != 0) {
                throw PrivateChatProvisioningException(
                    failedStage = execution.failedStage ?: "PRIVATE_CHAT_UNKNOWN",
                    message = execution.failureReason ?: "The Private Chat installer failed. Retry resumes from the saved stage.",
                )
            }
            val manifestCommand = runCommand(
                session,
                "sudo python3 /opt/zerovpn/private-chat/installer/install.py manifest",
            )
            if (manifestCommand.exitCode != 0) {
                throw PrivateChatProvisioningException(
                    "PRIVATE_CHAT_COMPLETE",
                    "The Private Chat manifest could not be retrieved.",
                )
            }
            val manifest = runCatching { PrivateChatNodeManifest.parse(manifestCommand.stdout.trim()) }
                .getOrElse {
                    throw PrivateChatProvisioningException(
                        "PRIVATE_CHAT_COMPLETE",
                        "The Private Chat manifest failed validation.",
                    )
                }
            val credentialsCommand = runCommand(
                session,
                "sudo cat /etc/zerovpn/private-chat/secrets/owner-matrix.json",
            )
            if (credentialsCommand.exitCode != 0) {
                throw PrivateChatProvisioningException(
                    "PRIVATE_CHAT_OWNER_ACCOUNT",
                    "The root-only owner Matrix credentials could not be retrieved.",
                )
            }
            val credentials = runCatching {
                PrivateChatOwnerCredentials.parse(credentialsCommand.stdout.trim(), manifest)
            }.getOrElse {
                throw PrivateChatProvisioningException(
                    "PRIVATE_CHAT_OWNER_ACCOUNT",
                    "The owner Matrix credentials failed validation.",
                )
            }
            PrivateChatInstallResult(manifest, credentials)
        } finally {
            runCatching { runCommand(session, "sudo rm -rf -- '$remoteRoot'") }
        }
    }

    suspend fun refreshHealth(
        exit: ConfiguredExit,
        sshPrivateKey: String,
    ): PrivateChatNodeManifest = withSession(exit, sshPrivateKey) { session ->
        val healthResult = runCommand(
            session,
            "sudo python3 /opt/zerovpn/private-chat/installer/install.py health --json",
        )
        val healthPayloadIsValid = runCatching {
            val json = JSONObject(healthResult.stdout.trim())
            json.getString("status") in setOf("healthy", "degraded", "unhealthy")
        }.getOrDefault(false)
        if (!healthPayloadIsValid) {
            throw PrivateChatProvisioningException(
                "PRIVATE_CHAT_HEALTH",
                "Private Chat did not return a valid health result.",
            )
        }
        val manifestResult = runCommand(
            session,
            "sudo python3 /opt/zerovpn/private-chat/installer/install.py manifest",
        )
        if (manifestResult.exitCode != 0) {
            throw PrivateChatProvisioningException(
                "PRIVATE_CHAT_HEALTH",
                "Private Chat health or manifest retrieval failed.",
            )
        }
        runCatching { PrivateChatNodeManifest.parse(manifestResult.stdout.trim()) }
            .getOrElse {
                throw PrivateChatProvisioningException(
                    "PRIVATE_CHAT_HEALTH",
                    "The refreshed Private Chat manifest failed validation.",
                )
            }
    }

    suspend fun remove(
        exit: ConfiguredExit,
        sshPrivateKey: String,
        onEvent: (PrivateChatRemoteEvent) -> Unit,
    ): Unit = withSession(exit, sshPrivateKey) { session ->
        val remoteRoot = uploadBundle(session)
        try {
            val execution = runStreamingCommand(
                session = session,
                command = "sudo python3 '$remoteRoot/private-chat/installer/install.py' remove --yes",
                onEvent = onEvent,
            )
            if (execution.exitCode != 0) {
                throw PrivateChatProvisioningException(
                    failedStage = execution.failedStage ?: "PRIVATE_CHAT_COMPLETE",
                    message = execution.failureReason ?: "Private Chat removal failed; WireGuard was retained.",
                )
            }
        } finally {
            runCatching { runCommand(session, "sudo rm -rf -- '$remoteRoot'") }
        }
    }

    private suspend fun <T> withSession(
        exit: ConfiguredExit,
        sshPrivateKey: String,
        block: (Session) -> T,
    ): T = withContext(Dispatchers.IO) {
        val host = exit.publicIp.takeIf { it.isNotBlank() }
            ?: throw PrivateChatProvisioningException("PRIVATE_CHAT_PRECHECK", "The Oracle VM IP is missing.")
        val username = exit.sshUsername?.takeIf { it.isNotBlank() }
            ?: throw PrivateChatProvisioningException("PRIVATE_CHAT_PRECHECK", "The Oracle SSH username is missing.")
        if (sshPrivateKey.isBlank()) {
            throw PrivateChatProvisioningException("PRIVATE_CHAT_PRECHECK", "The Oracle SSH key is missing.")
        }
        val keyFile = File(appContext.cacheDir, "zerovpn_private_chat_${UUID.randomUUID()}.key")
        var session: Session? = null
        try {
            keyFile.writeText(sshPrivateKey)
            val jsch = JSch()
            jsch.addIdentity(keyFile.absolutePath)
            session = jsch.getSession(username, host, 22).apply {
                setConfig("StrictHostKeyChecking", "no")
                setConfig("UserKnownHostsFile", "/dev/null")
                setConfig("PreferredAuthentications", "publickey")
                timeout = 20_000
                setServerAliveInterval(15_000)
                setServerAliveCountMax(4)
                connect(20_000)
            }
            block(session)
        } catch (error: PrivateChatProvisioningException) {
            throw error
        } catch (_: Exception) {
            throw PrivateChatProvisioningException(
                "PRIVATE_CHAT_PRECHECK",
                "Could not connect to the Oracle VM for Private Chat. The VPN profile was not changed.",
            )
        } finally {
            session?.disconnect()
            runCatching { keyFile.delete() }
        }
    }

    private fun uploadBundle(session: Session): String {
        val remoteRoot = "/tmp/zerovpn-private-chat-${UUID.randomUUID()}"
        val channel = session.openChannel("sftp") as ChannelSftp
        try {
            channel.connect(30_000)
            channel.mkdir(remoteRoot)
            uploadAssetDirectory(channel, "private-chat", "$remoteRoot/private-chat")
            return remoteRoot
        } catch (_: Exception) {
            throw PrivateChatProvisioningException(
                "PRIVATE_CHAT_PACKAGES",
                "Could not upload the Private Chat installer bundle.",
            )
        } finally {
            channel.disconnect()
        }
    }

    private fun uploadAssetDirectory(channel: ChannelSftp, assetPath: String, remotePath: String) {
        channel.mkdir(remotePath)
        val entries = appContext.assets.list(assetPath).orEmpty()
        entries.forEach { name ->
            val childAssetPath = "$assetPath/$name"
            val childRemotePath = "$remotePath/$name"
            val children = appContext.assets.list(childAssetPath).orEmpty()
            if (children.isNotEmpty()) {
                uploadAssetDirectory(channel, childAssetPath, childRemotePath)
            } else {
                appContext.assets.open(childAssetPath).use { input ->
                    channel.put(input, childRemotePath)
                }
            }
        }
    }

    private fun runStreamingCommand(
        session: Session,
        command: String,
        onEvent: (PrivateChatRemoteEvent) -> Unit,
    ): StreamingResult {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val stdout = channel.inputStream
        val stderr = channel.errStream
        val stdoutBuffer = ByteArrayOutputStream()
        val stderrBuffer = ByteArrayOutputStream()
        var pendingLine = ""
        var failedStage: String? = null
        var failureReason: String? = null
        return try {
            channel.connect(30_000)
            val bytes = ByteArray(4096)
            while (!channel.isClosed || stdout.available() > 0 || stderr.available() > 0) {
                while (stdout.available() > 0) {
                    val count = stdout.read(bytes)
                    if (count > 0) {
                        stdoutBuffer.write(bytes, 0, count)
                        pendingLine += String(bytes, 0, count, Charsets.UTF_8)
                        val lines = pendingLine.split("\n")
                        pendingLine = lines.last()
                        lines.dropLast(1).forEach { line ->
                            parseRemoteLine(line, onEvent)?.let { failure ->
                                failedStage = failure.first
                                failureReason = failure.second
                            }
                        }
                    }
                }
                while (stderr.available() > 0) {
                    val count = stderr.read(bytes)
                    if (count > 0) stderrBuffer.write(bytes, 0, count)
                }
                if (!channel.isClosed) Thread.sleep(50)
            }
            if (pendingLine.isNotBlank()) {
                parseRemoteLine(pendingLine, onEvent)?.let { failure ->
                    failedStage = failure.first
                    failureReason = failure.second
                }
            }
            StreamingResult(
                exitCode = channel.exitStatus,
                failedStage = failedStage,
                failureReason = failureReason,
            )
        } finally {
            channel.disconnect()
        }
    }

    private fun parseRemoteLine(
        line: String,
        onEvent: (PrivateChatRemoteEvent) -> Unit,
    ): Pair<String, String>? {
        val eventPrefix = "ZEROVPN_PRIVATE_CHAT_EVENT "
        val failurePrefix = "ZEROVPN_PRIVATE_CHAT_FAILURE "
        return when {
            line.startsWith(eventPrefix) -> {
                runCatching {
                    val json = JSONObject(line.removePrefix(eventPrefix))
                    onEvent(
                        PrivateChatRemoteEvent(
                            stage = json.getString("stage"),
                            status = json.getString("status"),
                            message = json.getString("message"),
                        )
                    )
                }
                null
            }

            line.startsWith(failurePrefix) -> runCatching {
                val json = JSONObject(line.removePrefix(failurePrefix))
                json.getString("stage") to json.getString("reason")
            }.getOrNull()

            else -> null
        }
    }

    private fun runCommand(session: Session, command: String): CommandResult {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val stdoutBytes = ByteArrayOutputStream()
        val stderrBytes = ByteArrayOutputStream()
        val stdout = channel.inputStream
        val stderr = channel.errStream
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
                if (!channel.isClosed) Thread.sleep(50)
            }
            CommandResult(
                exitCode = channel.exitStatus,
                stdout = stdoutBytes.toString(Charsets.UTF_8.name()),
            )
        } finally {
            channel.disconnect()
        }
    }

    private data class StreamingResult(
        val exitCode: Int,
        val failedStage: String?,
        val failureReason: String?,
    )

    private data class CommandResult(
        val exitCode: Int,
        val stdout: String,
    )
}
