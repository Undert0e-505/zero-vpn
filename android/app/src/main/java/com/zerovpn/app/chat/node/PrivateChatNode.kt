package com.zerovpn.app.chat.node

import org.json.JSONObject
import java.net.URI

enum class PrivateChatInstallStatus {
    INSTALLING,
    HEALTHY,
    FAILED,
    REMOVING,
}

data class PrivateChatNodeState(
    val status: PrivateChatInstallStatus,
    val currentStage: String? = null,
    val lastError: String? = null,
    val nodeId: String? = null,
    val serverName: String? = null,
    val matrixPrivateUrl: String? = null,
    val tlsSpkiSha256: String? = null,
    val ownerMatrixUserId: String? = null,
    val ownerCredentialsSecretKey: String? = null,
    val installedAt: String? = null,
    val healthStatus: String? = null,
    val componentVersions: Map<String, String> = emptyMap(),
    val ownerLoginVerifiedAt: Long? = null,
    val lastUpdatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        fun installing(stage: String? = "PRIVATE_CHAT_PRECHECK") = PrivateChatNodeState(
            status = PrivateChatInstallStatus.INSTALLING,
            currentStage = stage,
        )
    }
}

data class PrivateChatNodeManifest(
    val nodeId: String,
    val serverName: String,
    val matrixPrivateUrl: String,
    val tlsSpkiSha256: String,
    val ownerMatrixUserId: String,
    val installedAt: String,
    val healthStatus: String,
    val componentVersions: Map<String, String>,
) {
    companion object {
        private val SERVER_NAME = Regex("^node-[0-9a-f]{12}\\.zerovpn$")
        private val SPKI_PIN = Regex("^sha256/[A-Za-z0-9+/]{43}=$")

        fun parse(raw: String): PrivateChatNodeManifest {
            val json = JSONObject(raw)
            require(json.optInt("schemaVersion", -1) == 1) { "Unsupported private-chat manifest schema." }
            val nodeId = json.getString("nodeId")
            runCatching { java.util.UUID.fromString(nodeId) }
                .getOrElse { throw IllegalArgumentException("Invalid private-chat node ID.") }
            val serverName = json.getString("serverName")
            require(SERVER_NAME.matches(serverName)) { "Invalid private-chat server name." }
            val matrixUrl = json.getString("matrixPrivateUrl")
            val uri = runCatching { URI(matrixUrl) }
                .getOrElse { throw IllegalArgumentException("Invalid private Matrix URL.") }
            require(
                uri.scheme == "https" &&
                    uri.host == "10.66.66.1" &&
                    uri.port in setOf(-1, 443) &&
                    uri.userInfo == null &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null &&
                    (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
            ) {
                "The Matrix URL is not the expected private WireGuard endpoint."
            }
            val tlsPin = json.getString("tlsSpkiSha256")
            require(SPKI_PIN.matches(tlsPin)) { "Invalid private-chat TLS fingerprint." }
            val ownerUserId = json.getString("ownerMatrixUserId")
            require(ownerUserId == "@owner:$serverName") { "Owner Matrix ID does not match the node." }
            val health = json.getJSONObject("health").getString("status")
            require(health in setOf("healthy", "degraded", "unhealthy")) { "Invalid private-chat health state." }
            val componentsJson = json.getJSONObject("components")
            val components = buildMap {
                componentsJson.keys().forEach { key ->
                    val value = componentsJson.optString(key).takeIf { it.isNotBlank() }
                    if (value != null) put(key, value)
                }
            }
            require(components.isNotEmpty()) { "Private-chat component versions are missing." }
            return PrivateChatNodeManifest(
                nodeId = nodeId,
                serverName = serverName,
                matrixPrivateUrl = matrixUrl,
                tlsSpkiSha256 = tlsPin,
                ownerMatrixUserId = ownerUserId,
                installedAt = json.getString("installedAt"),
                healthStatus = health,
                componentVersions = components,
            )
        }
    }
}

data class PrivateChatOwnerCredentials(
    val userId: String,
    val password: String,
    val matrixPrivateUrl: String,
    val tlsSpkiSha256: String,
) {
    fun toSecretJson(): String = JSONObject()
        .put("schemaVersion", 1)
        .put("userId", userId)
        .put("password", password)
        .put("matrixPrivateUrl", matrixPrivateUrl)
        .put("tlsSpkiSha256", tlsSpkiSha256)
        .toString()

    companion object {
        fun parse(raw: String, manifest: PrivateChatNodeManifest): PrivateChatOwnerCredentials {
            val json = JSONObject(raw)
            val credentials = PrivateChatOwnerCredentials(
                userId = json.getString("userId"),
                password = json.getString("password"),
                matrixPrivateUrl = json.getString("matrixPrivateUrl"),
                tlsSpkiSha256 = json.getString("tlsSpkiSha256"),
            )
            require(credentials.userId == manifest.ownerMatrixUserId) { "Owner credentials do not match the manifest." }
            require(credentials.password.length >= 32) { "Owner Matrix password is not strong enough." }
            require(credentials.matrixPrivateUrl == manifest.matrixPrivateUrl) { "Owner credentials use a different Matrix URL." }
            require(credentials.tlsSpkiSha256 == manifest.tlsSpkiSha256) { "Owner credentials use a different TLS pin." }
            return credentials
        }
    }
}

data class PrivateChatInstallResult(
    val manifest: PrivateChatNodeManifest,
    val ownerCredentials: PrivateChatOwnerCredentials,
)

data class PrivateChatRemoteEvent(
    val stage: String,
    val status: String,
    val message: String,
)

class PrivateChatProvisioningException(
    val failedStage: String,
    message: String,
) : Exception(message)
