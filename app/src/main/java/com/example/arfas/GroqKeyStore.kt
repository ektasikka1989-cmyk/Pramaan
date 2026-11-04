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
 * Online AI keys: a Groq key ("gsk_…", Qwen3.8 27B) and a Meta Model API key ("LLM|…", Muse
 * Spark) are kept side by side, each encrypted with AES-256/GCM in AndroidKeyStore, and the user
 * chooses which engine answers online ([engine]). Saving a key stores it by its format.
 */
class GroqKeyStore(context: Context) {
    enum class Engine(val id: String) { GROQ("groq"), META("meta") }

    private val metaStore = MetaKeyStore(context)
    private val prefs = context.getSharedPreferences("fixsight_cloud_settings", Context.MODE_PRIVATE)

    /** Pramaan: Groq is disabled; only Meta's models are used online. A saved Groq key is ignored, never sent. */
    fun groqKey(): String = ""

    @Suppress("unused")
    private fun legacyGroqKey(): String {
        prefs.getString(LEGACY_PLAINTEXT_KEY, null)?.trim()?.takeIf { it.isNotBlank() }?.let { legacy ->
            runCatching { saveKey(legacy) }.onFailure { prefs.edit().remove(LEGACY_PLAINTEXT_KEY).apply(); return legacy.takeIf(::isGroqKey).orEmpty() }
        }
        val own = decrypt(prefs.getString(KEY_GROQ_CIPHER, null), prefs.getString(KEY_GROQ_IV, null))
        if (isGroqKey(own)) return own
        // Earlier versions wrote one key into both stores.
        return metaStore.getKey().takeIf(::isGroqKey).orEmpty()
    }

    /** The Meta Model API key, or "". */
    fun metaKey(): String = metaStore.getKey().takeIf { it.isNotBlank() && !isGroqKey(it) }.orEmpty()

    /** The engine chosen in Settings; without a choice, the one that has a key (Groq first). */
    fun engine(): Engine {
        if (GROQ_DISABLED) return Engine.META
        val chosen = Engine.values().firstOrNull { it.id == prefs.getString(KEY_ENGINE, null) }
        if (chosen != null) return chosen
        return if (groqKey().isBlank() && metaKey().isNotBlank()) Engine.META else Engine.GROQ
    }

    fun setEngine(engine: Engine) { prefs.edit().putString(KEY_ENGINE, (if (GROQ_DISABLED) Engine.META else engine).id).apply() }

    fun keyFor(engine: Engine): String = if (engine == Engine.GROQ) groqKey() else metaKey()

    /** The key of the chosen engine; "" when that engine has no key (online answers stay off). */
    fun getKey(): String = keyFor(engine())

    /** Stores [value] as the Groq key ("gsk_…") or the Meta key, and selects that engine. */
    fun saveKey(value: String) {
        val clean = value.trim()
        if (clean.isBlank()) return
        if (GROQ_DISABLED && isGroqKey(clean)) return   // Pramaan: Groq keys are not accepted
        if (isGroqKey(clean)) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
            prefs.edit()
                .putString(KEY_GROQ_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(KEY_GROQ_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .remove(LEGACY_PLAINTEXT_KEY)
                .apply()
            if (isGroqKey(metaStore.getKey())) metaStore.clearKey()
            setEngine(Engine.GROQ)
        } else {
            metaStore.saveKey(clean)
            setEngine(Engine.META)
        }
    }

    /** Removes the chosen engine's key. */
    fun clearKey(engine: Engine = engine()) {
        if (engine == Engine.GROQ) {
            prefs.edit().remove(KEY_GROQ_CIPHER).remove(KEY_GROQ_IV).remove(LEGACY_PLAINTEXT_KEY).apply()
            if (isGroqKey(metaStore.getKey())) metaStore.clearKey()
        } else metaStore.clearKey()
    }

    fun hasKey(): Boolean = getKey().isNotBlank()

    private fun isGroqKey(key: String): Boolean = key.trim().startsWith("gsk_")

    private fun decrypt(cipherText: String?, ivText: String?): String {
        if (cipherText == null || ivText == null) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)), Charsets.UTF_8).trim()
        } catch (_: Throwable) {
            ""
        }
    }

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
        /** Pramaan (hackathon build): Groq is switched off everywhere; Meta Muse Spark is the only online model. */
        const val GROQ_DISABLED = true
        private const val KEY_ALIAS = "fixsight_groq_aes_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_GROQ_CIPHER = "groq_api_ciphertext"
        private const val KEY_GROQ_IV = "groq_api_iv"
        private const val LEGACY_PLAINTEXT_KEY = "groq_api_key"
        private const val KEY_ENGINE = "online_engine"
    }
}
