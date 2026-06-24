package com.zerovpn.app.friends

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class FriendsRepository(
    private val prefs: SharedPreferences,
) {
    fun listInviteSlots(): List<InviteSlot> = loadInviteSlots()

    fun getInviteSlotsForOwnerExit(ownerExitId: String): List<InviteSlot> =
        loadInviteSlots()
            .filter { it.ownerExitId == ownerExitId }
            .sortedBy { it.slotIndex }

    fun getPendingInviteSlotsForOwnerExit(ownerExitId: String): List<InviteSlot> =
        getInviteSlotsForOwnerExit(ownerExitId)
            .filter { it.state == InviteSlotState.PENDING_CLAIM }

    fun upsertInviteSlot(slot: InviteSlot): List<InviteSlot> {
        val slots = loadInviteSlots()
            .filterNot { it.slotId == slot.slotId } + slot.copy(updatedAt = System.currentTimeMillis())
        saveInviteSlots(slots)
        return slots.sortedWith(inviteSlotSort)
    }

    fun renameInviteSlot(slotId: String, name: String?): List<InviteSlot> =
        updateInviteSlot(slotId) {
            it.copy(
                displayName = name?.trim()?.takeIf { value -> value.isNotBlank() },
                updatedAt = System.currentTimeMillis(),
            )
        }

    fun updateInviteSlotState(slotId: String, state: InviteSlotState): List<InviteSlot> =
        updateInviteSlot(slotId) {
            it.copy(state = state, updatedAt = System.currentTimeMillis())
        }

    fun markInviteSlotPending(slotId: String, qrShownAt: Long): List<InviteSlot> =
        updateInviteSlot(slotId) {
            it.copy(
                state = InviteSlotState.PENDING_CLAIM,
                qrShownAt = it.qrShownAt ?: qrShownAt,
                updatedAt = System.currentTimeMillis(),
            )
        }

    fun markInviteSlotClaimed(
        slotId: String,
        firstHandshakeAt: Long,
        lastHandshakeAt: Long,
    ): List<InviteSlot> =
        updateInviteSlot(slotId) {
            it.copy(
                state = InviteSlotState.CLAIMED,
                firstHandshakeAt = it.firstHandshakeAt ?: firstHandshakeAt,
                lastHandshakeAt = lastHandshakeAt,
                encryptedClientConfig = null,
                encryptedClientPrivateKey = null,
                updatedAt = System.currentTimeMillis(),
            )
        }

    fun clearBurnedPrivateMaterial(slotId: String): List<InviteSlot> =
        updateInviteSlot(slotId) {
            it.copy(
                encryptedClientConfig = null,
                encryptedClientPrivateKey = null,
                updatedAt = System.currentTimeMillis(),
            )
        }

    fun updateInviteSlotLastHandshake(slotId: String, lastHandshakeAt: Long): List<InviteSlot> =
        updateInviteSlot(slotId) {
            it.copy(
                lastHandshakeAt = lastHandshakeAt,
                updatedAt = System.currentTimeMillis(),
            )
        }

    fun markInviteSlotRevoked(slotId: String, revokedAt: Long): List<InviteSlot> =
        updateInviteSlot(slotId) {
            it.copy(
                state = InviteSlotState.REVOKED,
                revokedAt = revokedAt,
                updatedAt = System.currentTimeMillis(),
            )
        }

    fun listSharedExits(): List<SharedExitProfile> =
        loadSharedExits().sortedBy { it.importedAt }

    fun addSharedExit(profile: SharedExitProfile): List<SharedExitProfile> {
        val profiles = loadSharedExits().filterNot { it.id == profile.id } + profile
        saveSharedExits(profiles)
        return profiles.sortedBy { it.importedAt }
    }

    fun renameSharedExit(profileId: String, name: String): List<SharedExitProfile> =
        updateSharedExit(profileId) {
            it.copy(
                displayName = name.trim().ifBlank { it.displayName },
                updatedAt = System.currentTimeMillis(),
                renamedAt = System.currentTimeMillis(),
            )
        }

    fun removeSharedExit(profileId: String): List<SharedExitProfile> {
        val profiles = loadSharedExits().filterNot { it.id == profileId }
        saveSharedExits(profiles)
        return profiles.sortedBy { it.importedAt }
    }

    private fun updateInviteSlot(slotId: String, transform: (InviteSlot) -> InviteSlot): List<InviteSlot> {
        val slots = loadInviteSlots().map { slot ->
            if (slot.slotId == slotId) transform(slot) else slot
        }
        saveInviteSlots(slots)
        return slots.sortedWith(inviteSlotSort)
    }

    private fun updateSharedExit(
        profileId: String,
        transform: (SharedExitProfile) -> SharedExitProfile,
    ): List<SharedExitProfile> {
        val profiles = loadSharedExits().map { profile ->
            if (profile.id == profileId) transform(profile) else profile
        }
        saveSharedExits(profiles)
        return profiles.sortedBy { it.importedAt }
    }

    private fun loadInviteSlots(): List<InviteSlot> {
        val raw = prefs.getString(KEY_INVITE_SLOTS, null)?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    add(array.getJSONObject(i).toInviteSlot())
                }
            }.sortedWith(inviteSlotSort)
        }.getOrDefault(emptyList())
    }

    private fun saveInviteSlots(slots: List<InviteSlot>) {
        val array = JSONArray()
        slots.sortedWith(inviteSlotSort).forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_INVITE_SLOTS, array.toString()).apply()
    }

    private fun loadSharedExits(): List<SharedExitProfile> {
        val raw = prefs.getString(KEY_SHARED_EXITS, null)?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    add(array.getJSONObject(i).toSharedExitProfile())
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveSharedExits(profiles: List<SharedExitProfile>) {
        val array = JSONArray()
        profiles.sortedBy { it.importedAt }.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_SHARED_EXITS, array.toString()).apply()
    }

    private fun InviteSlot.toJson(): JSONObject = JSONObject()
        .put("slotId", slotId)
        .put("ownerExitId", ownerExitId)
        .put("slotIndex", slotIndex)
        .put("displayName", displayName)
        .put("state", state.name)
        .put("tunnelIp", tunnelIp)
        .put("peerPublicKey", peerPublicKey)
        .put("encryptedClientConfig", encryptedClientConfig)
        .put("encryptedClientPrivateKey", encryptedClientPrivateKey)
        .put("qrShownAt", qrShownAt)
        .put("firstHandshakeAt", firstHandshakeAt)
        .put("lastHandshakeAt", lastHandshakeAt)
        .put("revokedAt", revokedAt)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    private fun JSONObject.toInviteSlot(): InviteSlot = InviteSlot(
        slotId = getString("slotId"),
        ownerExitId = getString("ownerExitId"),
        slotIndex = optInt("slotIndex", 0),
        displayName = optNullableString("displayName"),
        state = runCatching {
            InviteSlotState.valueOf(optString("state", InviteSlotState.UNUSED.name))
        }.getOrDefault(InviteSlotState.UNUSED),
        tunnelIp = optNullableString("tunnelIp"),
        peerPublicKey = optNullableString("peerPublicKey"),
        encryptedClientConfig = optNullableString("encryptedClientConfig"),
        encryptedClientPrivateKey = optNullableString("encryptedClientPrivateKey"),
        qrShownAt = optLongOrNull("qrShownAt"),
        firstHandshakeAt = optLongOrNull("firstHandshakeAt"),
        lastHandshakeAt = optLongOrNull("lastHandshakeAt"),
        revokedAt = optLongOrNull("revokedAt"),
        createdAt = optLong("createdAt", System.currentTimeMillis()),
        updatedAt = optLong("updatedAt", System.currentTimeMillis()),
    )

    private fun SharedExitProfile.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("displayName", displayName)
        .put("source", source.name)
        .put("providerType", providerType.name)
        .put("encryptedWireGuardConfig", encryptedWireGuardConfig)
        .put("endpointHost", endpointHost)
        .put("endpointIp", endpointIp)
        .put("importedAt", importedAt)
        .put("updatedAt", updatedAt)
        .put("lastConnectedAt", lastConnectedAt)
        .put("renamedAt", renamedAt)

    private fun JSONObject.toSharedExitProfile(): SharedExitProfile = SharedExitProfile(
        id = getString("id"),
        displayName = optString("displayName").takeIf { it.isNotBlank() } ?: "Shared Exit",
        source = runCatching {
            SharedExitSource.valueOf(optString("source", SharedExitSource.IMPORTED_QR.name))
        }.getOrDefault(SharedExitSource.IMPORTED_QR),
        providerType = runCatching {
            SharedExitProviderType.valueOf(optString("providerType", SharedExitProviderType.SHARED_WIREGUARD.name))
        }.getOrDefault(SharedExitProviderType.SHARED_WIREGUARD),
        encryptedWireGuardConfig = optNullableString("encryptedWireGuardConfig"),
        endpointHost = optNullableString("endpointHost"),
        endpointIp = optNullableString("endpointIp"),
        importedAt = optLong("importedAt", System.currentTimeMillis()),
        updatedAt = optLong("updatedAt", optLong("importedAt", System.currentTimeMillis())),
        lastConnectedAt = optLongOrNull("lastConnectedAt"),
        renamedAt = optLongOrNull("renamedAt"),
    )

    private fun JSONObject.optNullableString(name: String): String? =
        if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (has(name) && !isNull(name)) optLong(name) else null

    companion object {
        const val KEY_INVITE_SLOTS = "friends_invite_slots_json"
        const val KEY_SHARED_EXITS = "friends_shared_exits_json"

        private val inviteSlotSort = compareBy<InviteSlot>({ it.ownerExitId }, { it.slotIndex }, { it.slotId })
    }
}
