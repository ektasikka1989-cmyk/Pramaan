package com.example.arfas

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * User-supplied Meta Llama API key storage (Key format LLM|...).
 * Plaintext is never persisted. AES-256/GCM key material lives in AndroidKeyStore;
 * SharedPreferences stores ciphertext + IV only.
 */
class MetaKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("fixsight_meta_settings", Context.MODE_PRIVATE)

    fun getKey(): String {
        prefs.getString(LEGACY_PLAINTEXT_KEY, null)?.trim()?.takeIf { it.isNotBlank() }?.let { legacy ->
            try {
                saveKey(legacy)
            } catch (_: Throwable) {
                prefs.edit().remove(LEGACY_PLAINTEXT_KEY).apply()
                return legacy
            }
        }
        val cipherText = prefs.getString(KEY_META_CIPHER, null) ?: return ""
        val ivText = prefs.getString(KEY_META_IV, null) ?: return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)), Charsets.UTF_8).trim()
        } catch (_: Throwable) {
            ""
        }
    }

    fun saveKey(value: String) {
        val clean = value.trim()
        if (clean.isBlank()) { clearKey(); return }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_META_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_META_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .remove(LEGACY_PLAINTEXT_KEY)
            .apply()
    }

    fun clearKey() {
        prefs.edit().remove(KEY_META_CIPHER).remove(KEY_META_IV).remove(LEGACY_PLAINTEXT_KEY).apply()
    }

    fun hasKey(): Boolean = getKey().isNotBlank()

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "fixsight_meta_aes_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_META_CIPHER = "meta_api_ciphertext"
        private const val KEY_META_IV = "meta_api_iv"
        private const val LEGACY_PLAINTEXT_KEY = "meta_api_key"
    }
}
