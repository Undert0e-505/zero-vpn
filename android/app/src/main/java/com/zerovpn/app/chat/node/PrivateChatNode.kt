package com.zerovpn.app.chat.node

import org.json.JSONObject
import java.net.URI

enum class PrivateChatInstallStatus {
    INSTALLING,
    HEALTHY,
    FAILED,
    REMOVING,
}

enum class PrivateChatStageStatus {
    PENDING,
    RUNNING,
    COMPLETE,
    FAILED,
}

enum class PrivateChatSelfTestStatus {
    PASS,
    FAIL,
    NOT_RUN,
}

data class PrivateChatStageState(
    val status: PrivateChatStageStatus = PrivateChatStageStatus.PENDING,
    val attempts: Int = 0,
    val startedAt: String? = null,
    val completedAt: String? = null,
    val lastError: String? = null,
    val probeSatisfied: Boolean = false,
)

data class PrivateChatHealthChecks(
    val postgresqlProcessOk: Boolean? = null,
    val postgresqlConnectionOk: Boolean? = null,
    val synapseProcessOk: Boolean? = null,
    val synapseLoopbackListenerOk: Boolean? = null,
    val tlsEndpointOk: Boolean? = null,
    val matrixVersionsOk: Boolean? = null,
    val ownerAccountExists: Boolean? = null,
    val firewallServiceActive: Boolean? = null,
    val privateFirewallPolicyActive: Boolean? = null,
    val chatOnlyPolicyChainsReady: Boolean? = null,
    val chatOnlyPeerRulesActive: Boolean? = null,
)

val PRIVATE_CHAT_STAGE_ORDER: List<String> = listOf(
    "PRIVATE_CHAT_PRECHECK",
    "PRIVATE_CHAT_PACKAGES",
    "PRIVATE_CHAT_POSTGRES",
    "PRIVATE_CHAT_SYNAPSE",
    "PRIVATE_CHAT_TLS",
    "PRIVATE_CHAT_FIREWALL",
    "PRIVATE_CHAT_OWNER_ACCOUNT",
    "PRIVATE_CHAT_HEALTH",
    "PRIVATE_CHAT_ENCRYPTION_SELF_TEST",
    "PRIVATE_CHAT_COMPLETE",
)

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
    val healthCheckedAt: String? = null,
    val healthChecks: PrivateChatHealthChecks = PrivateChatHealthChecks(),
    val stageStates: Map<String, PrivateChatStageState> = emptyMap(),
    val lastSelfTestStatus: PrivateChatSelfTestStatus = PrivateChatSelfTestStatus.NOT_RUN,
    val lastSelfTestCheckedAt: String? = null,
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
    val healthCheckedAt: String?,
    val healthChecks: PrivateChatHealthChecks,
    val stageStates: Map<String, PrivateChatStageState>,
    val lastSelfTestStatus: PrivateChatSelfTestStatus,
    val lastSelfTestCheckedAt: String?,
    val componentVersions: Map<String, String>,
) {
    companion object {
        private val SERVER_NAME = Regex("^node-[0-9a-f]{12}\\.zerovpn$")
        private val SPKI_PIN = Regex("^sha256/[A-Za-z0-9+/]{43}=$")
        private val COMPONENT_NAME = Regex("^[A-Za-z0-9_.-]{1,64}$")
        private val SECRET_KEY = Regex(
            "password|passwd|credential|secret|private.?key|access.?token|refresh.?token|authorization|bearer",
            RegexOption.IGNORE_CASE,
        )

        fun parse(raw: String): PrivateChatNodeManifest {
            require(raw.length <= 256_000) { "Private-chat manifest is unexpectedly large." }
            val json = JSONObject(raw)
            require(!containsSecretShapedKey(json)) { "Private-chat manifest contains a prohibited secret-shaped field." }
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
            val network = json.getJSONObject("network")
            require(network.getString("wireguardAddress") == uri.host) {
                "Private Matrix URL does not match the WireGuard manifest address."
            }
            require(network.optString("wireguardInterface").matches(Regex("^[A-Za-z0-9_.-]{1,15}$"))) {
                "Invalid private-chat WireGuard interface."
            }
            require(network.optInt("matrixPort", -1) == 443 && network.optInt("synapseLoopbackPort", -1) == 8008) {
                "Invalid private-chat listener ports."
            }
            require(!network.optBoolean("federationEnabled", true)) { "Private-chat federation must be disabled." }

            val healthJson = json.getJSONObject("health")
            val health = healthJson.getString("status")
            require(health in setOf("healthy", "degraded", "unhealthy")) { "Invalid private-chat health state." }
            val checks = healthJson.getJSONObject("checks")
            val privateFirewall = checks.optJSONObject("privateChatFirewall")
            val chatOnlyRules = checks.optJSONObject("chatOnlyPeerRules")
            val healthChecks = PrivateChatHealthChecks(
                postgresqlProcessOk = checks.optionalBoolean("postgresqlProcess", "ok"),
                postgresqlConnectionOk = checks.optionalBoolean("postgresqlConnection", "ok"),
                synapseProcessOk = checks.optionalBoolean("synapseProcess", "ok"),
                synapseLoopbackListenerOk = checks.optionalBoolean("synapseLoopbackListener", "ok"),
                tlsEndpointOk = checks.optionalBoolean("privateTls", "ok"),
                matrixVersionsOk = checks.optionalBoolean("matrixVersions", "ok"),
                ownerAccountExists = checks.optionalBoolean("ownerAccount", "ok"),
                firewallServiceActive = privateFirewall.optionalBoolean("serviceActive"),
                privateFirewallPolicyActive = privateFirewall.optionalBoolean("privatePolicyActive"),
                chatOnlyPolicyChainsReady = chatOnlyRules.optionalBoolean("policyChainsReady"),
                chatOnlyPeerRulesActive = chatOnlyRules.optionalBoolean("active"),
            )

            val installation = json.getJSONObject("installation")
            val stageJson = installation.getJSONObject("stages")
            val stages = buildMap {
                PRIVATE_CHAT_STAGE_ORDER.forEach { stage ->
                    val record = stageJson.getJSONObject(stage)
                    val status = when (record.getString("status")) {
                        "pending" -> PrivateChatStageStatus.PENDING
                        "running" -> PrivateChatStageStatus.RUNNING
                        "complete" -> PrivateChatStageStatus.COMPLETE
                        "failed" -> PrivateChatStageStatus.FAILED
                        else -> throw IllegalArgumentException("Invalid private-chat stage status.")
                    }
                    put(
                        stage,
                        PrivateChatStageState(
                            status = status,
                            attempts = record.optInt("attempts", 0).coerceAtLeast(0),
                            startedAt = record.optNullableString("startedAt"),
                            completedAt = record.optNullableString("completedAt"),
                            lastError = record.optNullableString("lastError")
                                ?.let(::redactPrivateChatDiagnostic),
                            probeSatisfied = record.optBoolean("probeSatisfied", false),
                        ),
                    )
                }
            }
            val selfTest = installation.getJSONObject("lastSelfTest")
            val selfTestStatus = when (selfTest.getString("status")) {
                "pass" -> PrivateChatSelfTestStatus.PASS
                "fail" -> PrivateChatSelfTestStatus.FAIL
                "not-run" -> PrivateChatSelfTestStatus.NOT_RUN
                else -> throw IllegalArgumentException("Invalid private-chat self-test status.")
            }
            val componentsJson = json.getJSONObject("components")
            val components = buildMap {
                componentsJson.keys().forEach { key ->
                    require(COMPONENT_NAME.matches(key)) { "Invalid private-chat component name." }
                    val value = componentsJson.optString(key)
                        .takeIf { it.isNotBlank() && it.length <= 200 && !it.contains('\n') && !it.contains('\r') }
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
                healthCheckedAt = healthJson.optNullableString("checkedAt"),
                healthChecks = healthChecks,
                stageStates = stages,
                lastSelfTestStatus = selfTestStatus,
                lastSelfTestCheckedAt = selfTest.optNullableString("checkedAt"),
                componentVersions = components,
            )
        }

        private fun containsSecretShapedKey(value: Any?): Boolean = when (value) {
            is JSONObject -> value.keys().asSequence().any { key ->
                SECRET_KEY.containsMatchIn(key) || containsSecretShapedKey(value.opt(key))
            }
            is org.json.JSONArray -> (0 until value.length()).any { containsSecretShapedKey(value.opt(it)) }
            else -> false
        }

        private fun JSONObject.optionalBoolean(objectName: String, fieldName: String): Boolean? =
            optJSONObject(objectName).optionalBoolean(fieldName)

        private fun JSONObject?.optionalBoolean(fieldName: String): Boolean? =
            this?.takeIf { it.has(fieldName) && !it.isNull(fieldName) }?.optBoolean(fieldName)

        private fun JSONObject.optNullableString(name: String): String? =
            if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() }?.take(600) else null
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
            require(raw.length <= 16_384) { "Owner Matrix credential envelope is unexpectedly large." }
            val json = JSONObject(raw)
            require(json.optInt("schemaVersion", -1) == 1) { "Unsupported owner credential schema." }
            val credentials = PrivateChatOwnerCredentials(
                userId = json.getString("userId"),
                password = json.getString("password"),
                matrixPrivateUrl = json.getString("matrixPrivateUrl"),
                tlsSpkiSha256 = json.getString("tlsSpkiSha256"),
            )
            require(credentials.userId == manifest.ownerMatrixUserId) { "Owner credentials do not match the manifest." }
            require(credentials.password.length in 32..512) { "Owner Matrix password is not strong enough." }
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
    val durationMillis: Long? = null,
)

class PrivateChatProvisioningException(
    val failedStage: String,
    message: String,
) : Exception(redactPrivateChatDiagnostic(message))

private val PRIVATE_CHAT_PRIVATE_KEY = Regex(
    "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val PRIVATE_CHAT_BEARER = Regex("\\bBearer\\s+[A-Za-z0-9._~+/=-]+", RegexOption.IGNORE_CASE)
private val PRIVATE_CHAT_URL_USERINFO = Regex("(https?://)([^/@\\s]+)@", RegexOption.IGNORE_CASE)
private val PRIVATE_CHAT_ASSIGNMENT = Regex(
    "([\\\"']?(?:password|passwd|credential|secret|token|authorization|private[_ -]?key)[\\\"']?\\s*[:=]\\s*)" +
        "(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)",
    RegexOption.IGNORE_CASE,
)

internal fun redactPrivateChatDiagnostic(value: String?, limit: Int = 600): String {
    var text = value.orEmpty().replace('\u0000', ' ')
    text = PRIVATE_CHAT_PRIVATE_KEY.replace(text, "[REDACTED PRIVATE KEY]")
    text = PRIVATE_CHAT_BEARER.replace(text, "Bearer [REDACTED]")
    text = PRIVATE_CHAT_URL_USERINFO.replace(text, "${'$'}1[REDACTED]@")
    text = PRIVATE_CHAT_ASSIGNMENT.replace(text) { match -> "${match.groupValues[1]}[REDACTED]" }
    text = text.split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ")
    return text.ifBlank { "Private Chat failed without a diagnostic." }.take(limit)
}
