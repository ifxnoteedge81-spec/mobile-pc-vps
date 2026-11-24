package com.example.security

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
 * Encrypts and stores API keys and OAuth tokens locally using Android Keystore AES-256-GCM.
 * Keys never leave the device except when sent directly to the user-selected AI provider over HTTPS.
 */
class SecureKeyStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveSecret(slot: SecretSlot, plainText: String) {
        val trimmed = plainText.trim()
        if (trimmed.isEmpty()) {
            deleteSecret(slot)
            return
        }
        val encrypted = encrypt(trimmed)
        prefs.edit().putString(slot.prefKey, encrypted).apply()
    }

    fun getSecret(slot: SecretSlot): String? {
        val stored = prefs.getString(slot.prefKey, null) ?: return null
        return runCatching { decrypt(stored) }.getOrNull()
    }

    fun hasSecret(slot: SecretSlot): Boolean = !getSecret(slot).isNullOrBlank()

    fun getMaskedPreview(slot: SecretSlot): String {
        val raw = getSecret(slot) ?: return "Not configured"
        return if (raw.length <= 8) "••••••••" else "${raw.take(4)}••••${raw.takeLast(4)}"
    }

    fun deleteSecret(slot: SecretSlot) {
        prefs.edit().remove(slot.prefKey).apply()
    }

    fun clearAllSecrets() {
        prefs.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val dataB64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
        return "$ivB64:$dataB64"
    }

    private fun decrypt(payload: String): String {
        val parts = payload.split(":")
        require(parts.size == 2) { "Invalid encrypted payload" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val cipherBytes = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    enum class SecretSlot(val prefKey: String, val label: String) {
        GEMINI_API_KEY("enc_gemini_api_key", "Google Gemini API Key"),
        OPENROUTER_API_KEY("enc_openrouter_api_key", "OpenRouter API Key"),
        YOUTUBE_OAUTH_TOKEN("enc_youtube_oauth_token", "YouTube Data API OAuth Token")
    }

    companion object {
        private const val PREFS_NAME = "personal_ai_secure_vault"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "PersonalAiMasterKeyV1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
