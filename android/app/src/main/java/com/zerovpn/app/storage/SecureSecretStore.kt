package com.zerovpn.app.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSecretStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun putSecret(key: String, value: String) {
        if (key.isBlank()) return
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = "${cipher.iv.base64()}:${ciphertext.base64()}"
        prefs.edit().putString(key, encoded).apply()
    }

    fun getSecret(key: String): String? {
        if (key.isBlank()) return null
        val encoded = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return null
        val parts = encoded.split(":", limit = 2)
        if (parts.size != 2) return null
        return runCatching {
            val iv = java.util.Base64.getDecoder().decode(parts[0])
            val ciphertext = java.util.Base64.getDecoder().decode(parts[1])
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    fun removeSecret(key: String) {
        if (key.isBlank()) return
        prefs.edit().remove(key).apply()
    }

    fun hasSecret(key: String): Boolean = getSecret(key) != null

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun ByteArray.base64(): String = java.util.Base64.getEncoder().encodeToString(this)

    companion object {
        private const val PREFS_NAME = "zerovpn_secure_secrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "zerovpn.local.secretstore.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        fun oracleSshPrivateKey(exitId: String): String = "oracle:$exitId:sshPrivateKey"
        fun oracleOwnerWireGuardConfig(exitId: String): String = "oracle:$exitId:ownerWireGuardConfig"
        fun inviteClientConfig(slotId: String): String = "invite:$slotId:clientConfig"
        fun sharedWireGuardConfig(sharedExitId: String): String = "shared:$sharedExitId:wireGuardConfig"
    }
}
