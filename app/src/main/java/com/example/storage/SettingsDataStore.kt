package com.example.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ai.AUTO_MODEL_ID
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "personal_ai_settings")

enum class AiProviderType(val displayName: String) {
    AUTO("Smart Router (Auto)"),
    GEMINI("Google Gemini"),
    OPENROUTER("OpenRouter")
}

enum class PowerProfile(val label: String) {
    LOW_POWER("Low Power Mode"),
    BALANCED("Balanced"),
    PERFORMANCE("Performance Mode")
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val preferredProvider: AiProviderType = AiProviderType.AUTO,
    val geminiModel: String = AUTO_MODEL_ID,
    val openRouterModel: String = AUTO_MODEL_ID,
    val streamingEnabled: Boolean = true,
    val autoFallbackEnabled: Boolean = true,
    val preferFreeModelsOnSimpleTasks: Boolean = true,
    val fullAutonomousMode: Boolean = true,
    val ramBudgetMb: Int = 1024,
    val storageQuotaMb: Int = 2048,
    val adaptiveDeviceScaling: Boolean = true,
    val lowResourceMode: Boolean = false,
    val powerProfile: PowerProfile = PowerProfile.BALANCED,
    val maxConcurrency: Int = 2,
    val preferredLanguage: String = "Auto (Bangla & English)",
    val pendingRestartNotice: Boolean = false,
    val waBotEnabled: Boolean = false,
    val waDefaultAutoReplyDm: Boolean = true,
    val waDefaultAutoReplyGroup: Boolean = false,
    val waGlobalAntiLink: Boolean = false,
    val waGlobalAntiSticker: Boolean = false,
    val waGlobalAntiForward: Boolean = false,
    val waBotPersona: String = "You are a zero-latency, smart WhatsApp AI assistant. Understand prior conversation context and reply naturally in Bangla, Banglish, or English matching the user's topic. Keep replies concise and helpful."
)

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val PREFERRED_PROVIDER = stringPreferencesKey("preferred_provider")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val OPENROUTER_MODEL = stringPreferencesKey("openrouter_model")
        val STREAMING = booleanPreferencesKey("streaming_enabled")
        val AUTO_FALLBACK = booleanPreferencesKey("auto_fallback")
        val PREFER_FREE = booleanPreferencesKey("prefer_free_simple")
        val FULL_AUTONOMOUS = booleanPreferencesKey("full_autonomous_mode")
        val RAM_BUDGET_MB = intPreferencesKey("ram_budget_mb")
        val STORAGE_QUOTA_MB = intPreferencesKey("storage_quota_mb")
        val ADAPTIVE_SCALING = booleanPreferencesKey("adaptive_device_scaling")
        val LOW_RESOURCE_MODE = booleanPreferencesKey("low_resource_mode")
        val POWER_PROFILE = stringPreferencesKey("power_profile")
        val MAX_CONCURRENCY = intPreferencesKey("max_concurrency")
        val PREFERRED_LANGUAGE = stringPreferencesKey("preferred_language")
        val PENDING_RESTART_NOTICE = booleanPreferencesKey("pending_restart_notice")
        val WA_BOT_ENABLED = booleanPreferencesKey("wa_bot_enabled")
        val WA_DEFAULT_AUTO_REPLY_DM = booleanPreferencesKey("wa_default_auto_reply_dm")
        val WA_DEFAULT_AUTO_REPLY_GROUP = booleanPreferencesKey("wa_default_auto_reply_group")
        val WA_GLOBAL_ANTI_LINK = booleanPreferencesKey("wa_global_anti_link")
        val WA_GLOBAL_ANTI_STICKER = booleanPreferencesKey("wa_global_anti_sticker")
        val WA_GLOBAL_ANTI_FORWARD = booleanPreferencesKey("wa_global_anti_forward")
        val WA_BOT_PERSONA = stringPreferencesKey("wa_bot_persona")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = runCatching { ThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: "DARK") }.getOrDefault(ThemeMode.DARK),
            preferredProvider = runCatching { AiProviderType.valueOf(prefs[Keys.PREFERRED_PROVIDER] ?: "AUTO") }.getOrDefault(AiProviderType.AUTO),
            geminiModel = prefs[Keys.GEMINI_MODEL]?.takeIf { it.isNotBlank() } ?: AUTO_MODEL_ID,
            openRouterModel = prefs[Keys.OPENROUTER_MODEL]?.takeIf { it.isNotBlank() } ?: AUTO_MODEL_ID,
            streamingEnabled = prefs[Keys.STREAMING] ?: true,
            autoFallbackEnabled = prefs[Keys.AUTO_FALLBACK] ?: true,
            preferFreeModelsOnSimpleTasks = prefs[Keys.PREFER_FREE] ?: true,
            fullAutonomousMode = prefs[Keys.FULL_AUTONOMOUS] ?: true,
            ramBudgetMb = prefs[Keys.RAM_BUDGET_MB] ?: 1024,
            storageQuotaMb = prefs[Keys.STORAGE_QUOTA_MB] ?: 2048,
            adaptiveDeviceScaling = prefs[Keys.ADAPTIVE_SCALING] ?: true,
            lowResourceMode = prefs[Keys.LOW_RESOURCE_MODE] ?: false,
            powerProfile = runCatching { PowerProfile.valueOf(prefs[Keys.POWER_PROFILE] ?: "BALANCED") }.getOrDefault(PowerProfile.BALANCED),
            maxConcurrency = prefs[Keys.MAX_CONCURRENCY] ?: 2,
            preferredLanguage = prefs[Keys.PREFERRED_LANGUAGE] ?: "Auto (Bangla & English)",
            pendingRestartNotice = prefs[Keys.PENDING_RESTART_NOTICE] ?: false,
            waBotEnabled = prefs[Keys.WA_BOT_ENABLED] ?: false,
            waDefaultAutoReplyDm = prefs[Keys.WA_DEFAULT_AUTO_REPLY_DM] ?: true,
            waDefaultAutoReplyGroup = prefs[Keys.WA_DEFAULT_AUTO_REPLY_GROUP] ?: false,
            waGlobalAntiLink = prefs[Keys.WA_GLOBAL_ANTI_LINK] ?: false,
            waGlobalAntiSticker = prefs[Keys.WA_GLOBAL_ANTI_STICKER] ?: false,
            waGlobalAntiForward = prefs[Keys.WA_GLOBAL_ANTI_FORWARD] ?: false,
            waBotPersona = prefs[Keys.WA_BOT_PERSONA]?.takeIf { it.isNotBlank() }
                ?: "You are a zero-latency, smart WhatsApp AI assistant. Understand prior conversation context and reply naturally in Bangla, Banglish, or English matching the user's topic. Keep replies concise and helpful."
        )
    }

    suspend fun updateThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun updateProviderConfig(
        preferredProvider: AiProviderType,
        geminiModel: String,
        openRouterModel: String,
        streaming: Boolean,
        autoFallback: Boolean,
        preferFree: Boolean,
        fullAutonomousMode: Boolean = true
    ) {
        context.dataStore.edit {
            it[Keys.PREFERRED_PROVIDER] = preferredProvider.name
            it[Keys.GEMINI_MODEL] = geminiModel.trim()
            it[Keys.OPENROUTER_MODEL] = openRouterModel.trim()
            it[Keys.STREAMING] = streaming
            it[Keys.AUTO_FALLBACK] = autoFallback
            it[Keys.PREFER_FREE] = preferFree
            it[Keys.FULL_AUTONOMOUS] = fullAutonomousMode
        }
    }

    suspend fun updateResourceConfig(
        ramBudgetMb: Int,
        storageQuotaMb: Int = 2048,
        adaptiveDeviceScaling: Boolean = true,
        lowResourceMode: Boolean,
        powerProfile: PowerProfile,
        maxConcurrency: Int,
        markPendingRestart: Boolean = false
    ) {
        context.dataStore.edit {
            it[Keys.RAM_BUDGET_MB] = ramBudgetMb.coerceIn(256, 32768)
            it[Keys.STORAGE_QUOTA_MB] = storageQuotaMb.coerceIn(256, 65536)
            it[Keys.ADAPTIVE_SCALING] = adaptiveDeviceScaling
            it[Keys.LOW_RESOURCE_MODE] = lowResourceMode
            it[Keys.POWER_PROFILE] = powerProfile.name
            it[Keys.MAX_CONCURRENCY] = maxConcurrency.coerceIn(1, 6)
            if (markPendingRestart) {
                it[Keys.PENDING_RESTART_NOTICE] = true
            }
        }
    }

    suspend fun updateWhatsAppBotConfig(
        enabled: Boolean? = null,
        defaultAutoReplyDm: Boolean? = null,
        defaultAutoReplyGroup: Boolean? = null,
        globalAntiLink: Boolean? = null,
        globalAntiSticker: Boolean? = null,
        globalAntiForward: Boolean? = null,
        persona: String? = null
    ) {
        context.dataStore.edit { prefs ->
            enabled?.let { prefs[Keys.WA_BOT_ENABLED] = it }
            defaultAutoReplyDm?.let { prefs[Keys.WA_DEFAULT_AUTO_REPLY_DM] = it }
            defaultAutoReplyGroup?.let { prefs[Keys.WA_DEFAULT_AUTO_REPLY_GROUP] = it }
            globalAntiLink?.let { prefs[Keys.WA_GLOBAL_ANTI_LINK] = it }
            globalAntiSticker?.let { prefs[Keys.WA_GLOBAL_ANTI_STICKER] = it }
            globalAntiForward?.let { prefs[Keys.WA_GLOBAL_ANTI_FORWARD] = it }
            persona?.let { prefs[Keys.WA_BOT_PERSONA] = it.trim() }
        }
    }

    suspend fun clearRestartNotice() {
        context.dataStore.edit { it[Keys.PENDING_RESTART_NOTICE] = false }
    }
}
