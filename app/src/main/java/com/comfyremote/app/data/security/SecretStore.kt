package com.comfyremote.app.data.security

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Minimal credential vault so stores can be exercised without Android Keystore. */
interface SecretVault {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/**
 * Small AES/GCM wrapper backed by the Android Keystore. Secrets are stored as
 * `<key>_enc`; legacy plaintext values are migrated on first read so existing
 * installs keep working. If the Keystore is unavailable the class falls back to
 * the old plaintext key rather than losing the user's token.
 */
class SecretStore(private val prefs: SharedPreferences) : SecretVault {

    init {
        // Migrate legacy plaintext keys as soon as the container is built.
        LEGACY_KEYS.forEach { key -> runCatching { get(key) } }
    }

    override fun get(key: String): String? {
        val encrypted = prefs.getString(encKey(key), null)
        if (!encrypted.isNullOrBlank()) {
            val decrypted = runCatching { decrypt(encrypted) }.getOrNull()
            if (decrypted != null) return decrypted
            // Keep the ciphertext: a transient Keystore failure must not destroy the
            // saved token. A permanent key loss simply keeps returning null until the
            // user re-enters the value.
        }
        val legacy = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return null
        // put() removes the plaintext key only when encryption succeeded.
        // If Keystore is unavailable, put() intentionally keeps the plaintext
        // fallback; deleting it here would lose the token after the first read.
        put(key, legacy)
        return legacy
    }

    override fun put(key: String, value: String?) {
        val clean = value?.trim().orEmpty()
        if (clean.isBlank()) {
            prefs.edit().remove(encKey(key)).remove(key).apply()
            return
        }
        val encrypted = runCatching { encrypt(clean) }.getOrNull()
        prefs.edit().apply {
            if (encrypted != null) {
                putString(encKey(key), encrypted)
                remove(key)
            } else {
                // Rare fallback for devices with a broken Keystore.
                putString(key, clean)
                remove(encKey(key))
            }
        }.apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + body.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(body, 0, combined, iv.size, body.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val combined = Base64.decode(value, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, IV_SIZE)
        val body = combined.copyOfRange(IV_SIZE, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encKey(key: String) = "${key}_enc"

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "iris_secret_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private val LEGACY_KEYS = listOf("aiKey", "autoDlToken")
    }
}
