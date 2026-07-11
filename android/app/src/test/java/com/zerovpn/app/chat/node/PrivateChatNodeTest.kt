package com.zerovpn.app.chat.node

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateChatNodeTest {
    @Test
    fun `manifest parser imports only validated non-secret diagnostics`() {
        val manifest = PrivateChatNodeManifest.parse(validManifest().toString())

        assertEquals("node-123456781234.zerovpn", manifest.serverName)
        assertEquals("https://10.66.66.1", manifest.matrixPrivateUrl)
        assertEquals(PrivateChatSelfTestStatus.PASS, manifest.lastSelfTestStatus)
        assertTrue(manifest.healthChecks.postgresqlProcessOk == true)
        assertTrue(manifest.healthChecks.chatOnlyPolicyChainsReady == true)
        assertFalse(manifest.healthChecks.chatOnlyPeerRulesActive == true)
        assertEquals(
            PrivateChatStageStatus.COMPLETE,
            manifest.stageStates.getValue("PRIVATE_CHAT_TLS").status,
        )
    }

    @Test
    fun `manifest parser rejects a secret-shaped field and network mismatch`() {
        val secretManifest = validManifest().put("owner", JSONObject().put("accessToken", "must-not-import"))
        assertThrows(IllegalArgumentException::class.java) {
            PrivateChatNodeManifest.parse(secretManifest.toString())
        }

        val wrongNetwork = validManifest()
        wrongNetwork.getJSONObject("network").put("wireguardAddress", "10.77.0.1")
        assertThrows(IllegalArgumentException::class.java) {
            PrivateChatNodeManifest.parse(wrongNetwork.toString())
        }
    }

    @Test
    fun `private chat diagnostic redaction removes common secret forms`() {
        val redacted = redactPrivateChatDiagnostic(
            """password=json-secret Bearer abc.def.ghi https://user:url-secret@example.invalid """ +
                "-----BEGIN PRIVATE KEY----- key-material -----END PRIVATE KEY-----",
        )

        assertFalse(redacted.contains("json-secret"))
        assertFalse(redacted.contains("abc.def.ghi"))
        assertFalse(redacted.contains("url-secret"))
        assertFalse(redacted.contains("key-material"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    private fun validManifest(): JSONObject {
        val stages = JSONObject()
        PRIVATE_CHAT_STAGE_ORDER.forEach { stage ->
            stages.put(
                stage,
                JSONObject()
                    .put("status", "complete")
                    .put("attempts", 1)
                    .put("startedAt", "2026-07-11T00:00:00Z")
                    .put("completedAt", "2026-07-11T00:00:01Z")
                    .put("lastError", JSONObject.NULL)
                    .put("probeSatisfied", false),
            )
        }
        val checks = JSONObject()
            .put("postgresqlProcess", JSONObject().put("ok", true))
            .put("postgresqlConnection", JSONObject().put("ok", true))
            .put("synapseProcess", JSONObject().put("ok", true))
            .put("synapseLoopbackListener", JSONObject().put("ok", true))
            .put("matrixVersions", JSONObject().put("ok", true))
            .put("privateTls", JSONObject().put("ok", true))
            .put("ownerAccount", JSONObject().put("ok", true))
            .put(
                "privateChatFirewall",
                JSONObject()
                    .put("ok", true)
                    .put("serviceActive", true)
                    .put("privatePolicyActive", true),
            )
            .put(
                "chatOnlyPeerRules",
                JSONObject()
                    .put("ok", true)
                    .put("policyChainsReady", true)
                    .put("active", false),
            )
            .put(
                "encryptionSelfTest",
                JSONObject()
                    .put("ok", true)
                    .put("status", "pass")
                    .put("checkedAt", "2026-07-11T00:00:02Z"),
            )
        return JSONObject()
            .put("schemaVersion", 1)
            .put("installerVersion", "0.1.0")
            .put("nodeId", "12345678-1234-4234-8234-1234567890ab")
            .put("serverName", "node-123456781234.zerovpn")
            .put("matrixPrivateUrl", "https://10.66.66.1")
            .put("tlsSpkiSha256", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            .put("ownerMatrixUserId", "@owner:node-123456781234.zerovpn")
            .put("installedAt", "2026-07-11T00:00:00Z")
            .put("components", JSONObject().put("synapse", "1.156.0").put("installer", "0.1.0"))
            .put(
                "network",
                JSONObject()
                    .put("wireguardInterface", "wg0")
                    .put("wireguardAddress", "10.66.66.1")
                    .put("matrixPort", 443)
                    .put("synapseLoopbackPort", 8008)
                    .put("federationEnabled", false),
            )
            .put(
                "health",
                JSONObject()
                    .put("status", "healthy")
                    .put("checkedAt", "2026-07-11T00:00:02Z")
                    .put("checks", checks),
            )
            .put(
                "installation",
                JSONObject()
                    .put("currentStage", "PRIVATE_CHAT_COMPLETE")
                    .put("stages", stages)
                    .put(
                        "lastSelfTest",
                        JSONObject()
                            .put("status", "pass")
                            .put("checkedAt", "2026-07-11T00:00:02Z"),
                    ),
            )
    }
}
