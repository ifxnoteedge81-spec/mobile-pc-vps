package com.example.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
    val geminiModel: String = "gemini-2.5-flash",
    val openRouterModel: String = "google/gemini-2.0-flash-exp:free",
    val streamingEnabled: Boolean = true,
    val autoFallbackEnabled: Boolean = true,
    val preferFreeModelsOnSimpleTasks: Boolean = true,
    val ramBudgetMb: Int = 1024,
    val lowResourceMode: Boolean = false,
    val powerProfile: PowerProfile = PowerProfile.BALANCED,
    val maxConcurrency: Int = 2,
    val preferredLanguage: String = "Auto (Bangla & English)"
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
        val RAM_BUDGET_MB = intPreferencesKey("ram_budget_mb")
        val LOW_RESOURCE_MODE = booleanPreferencesKey("low_resource_mode")
        val POWER_PROFILE = stringPreferencesKey("power_profile")
        val MAX_CONCURRENCY = intPreferencesKey("max_concurrency")
        val PREFERRED_LANGUAGE = stringPreferencesKey("preferred_language")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = runCatching { ThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: "DARK") }.getOrDefault(ThemeMode.DARK),
            preferredProvider = runCatching { AiProviderType.valueOf(prefs[Keys.PREFERRED_PROVIDER] ?: "AUTO") }.getOrDefault(AiProviderType.AUTO),
            geminiModel = prefs[Keys.GEMINI_MODEL] ?: "gemini-2.5-flash",
            openRouterModel = prefs[Keys.OPENROUTER_MODEL] ?: "google/gemini-2.0-flash-exp:free",
            streamingEnabled = prefs[Keys.STREAMING] ?: true,
            autoFallbackEnabled = prefs[Keys.AUTO_FALLBACK] ?: true,
            preferFreeModelsOnSimpleTasks = prefs[Keys.PREFER_FREE] ?: true,
            ramBudgetMb = prefs[Keys.RAM_BUDGET_MB] ?: 1024,
            lowResourceMode = prefs[Keys.LOW_RESOURCE_MODE] ?: false,
            powerProfile = runCatching { PowerProfile.valueOf(prefs[Keys.POWER_PROFILE] ?: "BALANCED") }.getOrDefault(PowerProfile.BALANCED),
            maxConcurrency = prefs[Keys.MAX_CONCURRENCY] ?: 2,
            preferredLanguage = prefs[Keys.PREFERRED_LANGUAGE] ?: "Auto (Bangla & English)"
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
        preferFree: Boolean
    ) {
        context.dataStore.edit {
            it[Keys.PREFERRED_PROVIDER] = preferredProvider.name
            it[Keys.GEMINI_MODEL] = geminiModel.trim()
            it[Keys.OPENROUTER_MODEL] = openRouterModel.trim()
            it[Keys.STREAMING] = streaming
            it[Keys.AUTO_FALLBACK] = autoFallback
            it[Keys.PREFER_FREE] = preferFree
        }
    }

    suspend fun updateResourceConfig(
        ramBudgetMb: Int,
        lowResourceMode: Boolean,
        powerProfile: PowerProfile,
        maxConcurrency: Int
    ) {
        context.dataStore.edit {
            it[Keys.RAM_BUDGET_MB] = ramBudgetMb.coerceIn(256, 16384)
            it[Keys.LOW_RESOURCE_MODE] = lowResourceMode
            it[Keys.POWER_PROFILE] = powerProfile.name
            it[Keys.MAX_CONCURRENCY] = maxConcurrency.coerceIn(1, 6)
        }
    }
}
