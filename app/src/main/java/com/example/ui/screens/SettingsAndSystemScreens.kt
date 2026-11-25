package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.ai.AUTO_MODEL_ID
import com.example.ai.GeminiModelInfo
import com.example.ai.OpenRouterModelInfo
import com.example.resources.DeviceResourceSnapshot
import com.example.security.NetworkEvent
import com.example.security.PermissionLevel
import com.example.security.SecureKeyStore
import com.example.security.SecureKeyStore.SecretSlot
import com.example.storage.AiProviderType
import com.example.storage.AppSettings
import com.example.storage.PowerProfile
import com.example.storage.ProviderUsageEntity
import com.example.ui.components.GlassOsCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusBadge
import com.example.ui.components.TelemetryMeter
import com.example.ui.components.formatBytes
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.ThemeMode
import kotlin.math.roundToInt

@Composable
fun AiModelSettingsScreen(
    settings: AppSettings,
    geminiModels: List<GeminiModelInfo>,
    openRouterModels: List<OpenRouterModelInfo>,
    lastResolvedModel: String?,
    isFetchingModels: Boolean,
    usageHistory: List<ProviderUsageEntity>,
    getMaskedSecret: (SecretSlot) -> String,
    openRouterKeyMasked: String,
    youtubeTokenMasked: String,
    onSaveSecret: (SecretSlot, String) -> Unit,
    onDeleteSecret: (SecretSlot) -> Unit,
    onFetchGeminiModels: () -> Unit,
    onFetchOpenRouterModels: () -> Unit,
    onSaveProviderConfig: (AiProviderType, String, String, Boolean, Boolean, Boolean, Boolean) -> Unit,
    onChangeTheme: (ThemeMode) -> Unit
) {
    var openRouterKeyInput by remember { mutableStateOf("") }
    var ytTokenInput by remember { mutableStateOf("") }

    var provider by remember(settings.preferredProvider) { mutableStateOf(settings.preferredProvider) }
    var selectedGemini by remember(settings.geminiModel) { mutableStateOf(settings.geminiModel.ifBlank { AUTO_MODEL_ID }) }
    var selectedOpenRouter by remember(settings.openRouterModel) { mutableStateOf(settings.openRouterModel.ifBlank { AUTO_MODEL_ID }) }
    var streaming by remember(settings.streamingEnabled) { mutableStateOf(settings.streamingEnabled) }
    var autoFallback by remember(settings.autoFallbackEnabled) { mutableStateOf(settings.autoFallbackEnabled) }
    var preferFree by remember(settings.preferFreeModelsOnSimpleTasks) { mutableStateOf(settings.preferFreeModelsOnSimpleTasks) }
    var fullAutonomous by remember(settings.fullAutonomousMode) { mutableStateOf(settings.fullAutonomousMode) }
    var onlyFreeFilter by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("ai_model_settings_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionHeader(
                title = "AI Brain Gateway & API Keys",
                subtitle = "Direct HTTPS from Android to provider. Encrypted in Android Keystore.",
                icon = Icons.Filled.Key
            )
        }

        // 5 Rotating Gemini Key Cards (#1 - #5)
        item {
            val configuredCount = SecureKeyStore.geminiSlots.count { getMaskedSecret(it) != "Not configured" }
            GlassOsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Gemini API Key Pool (1–5 Auto-Rotate)", style = MaterialTheme.typography.titleSmall)
                    StatusBadge(
                        label = "$configuredCount/5 Active",
                        color = if (configuredCount == 0) AmberWarning else EmeraldPulse
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "৫টি আলাদা জিমেইলের Gemini Key যোগ করলে একটির লিমিট/429 এলে সাথে সাথে পরের Key (#1→#2→#3→#4→#5) রোটেট করবে এবং ৫টিই শেষ হলে অটো OpenRouter-এ সুইচ হবে।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                SecureKeyStore.geminiSlots.forEachIndexed { idx, slot ->
                    val slotNum = idx + 1
                    val masked = getMaskedSecret(slot)
                    var slotInput by remember(slot) { mutableStateOf("") }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Key #$slotNum", style = MaterialTheme.typography.labelLarge)
                        StatusBadge(
                            label = masked,
                            color = if (masked == "Not configured") AmberWarning else EmeraldPulse
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = slotInput,
                        onValueChange = { slotInput = it },
                        placeholder = { Text("Paste AIza... Gemini Key #$slotNum") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(if (slotNum == 1) "gemini_key_input" else "gemini_key_input_$slotNum")
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onSaveSecret(slot, slotInput); slotInput = "" },
                            modifier = Modifier.testTag(if (slotNum == 1) "save_gemini_key_button" else "save_gemini_key_button_$slotNum")
                        ) { Text("Save #$slotNum") }
                        OutlinedButton(onClick = { onDeleteSecret(slot) }) { Text("Delete") }
                    }
                    if (slotNum < 5) Spacer(Modifier.height(12.dp))
                }
            }
        }

        // OpenRouter Key Card (Auto-Fallback after 5 Gemini Keys)
        item {
            GlassOsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("OpenRouter API Key (Auto Fallback)", style = MaterialTheme.typography.titleSmall)
                    StatusBadge(label = openRouterKeyMasked, color = if (openRouterKeyMasked == "Not configured") AmberWarning else EmeraldPulse)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "৫টি Gemini Key-র টোকেন/লিমিট শেষ হলে বা ব্যর্থ হলে স্বয়ংক্রিয়ভাবে OpenRouter যুক্ত হয়ে কাজ চালাবে।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = openRouterKeyInput,
                    onValueChange = { openRouterKeyInput = it },
                    placeholder = { Text("Paste sk-or-... OpenRouter key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("openrouter_key_input")
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onSaveSecret(SecretSlot.OPENROUTER_API_KEY, openRouterKeyInput); openRouterKeyInput = "" },
                        modifier = Modifier.testTag("save_openrouter_key_button")
                    ) { Text("Encrypt & Save") }
                    OutlinedButton(onClick = { onDeleteSecret(SecretSlot.OPENROUTER_API_KEY) }) { Text("Delete") }
                }
            }
        }

        // Model Router Settings
        item {
            GlassOsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("AI Model Router & Auto Selection", style = MaterialTheme.typography.titleMedium)
                    if (!lastResolvedModel.isNullOrBlank()) {
                        StatusBadge(label = "Live: $lastResolvedModel", color = NeuralCyan)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Auto মোড থাকলে আপনার Gemini এবং OpenRouter API Key অনুযায়ী লাইভ সার্ভার থেকে সেরা উপযুক্ত মডেল অটোমেটিক সিলেক্ট হয়।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(AiProviderType.entries) { p ->
                        FilterChip(
                            selected = provider == p,
                            onClick = { provider = p },
                            label = { Text(p.displayName) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Gemini Model (API Driven)", style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = onFetchGeminiModels, enabled = !isFetchingModels) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (isFetchingModels) "Checking..." else "Discover")
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = selectedGemini == AUTO_MODEL_ID,
                            onClick = { selectedGemini = AUTO_MODEL_ID },
                            label = { Text("Auto (API অনুযায়ী সিলেক্ট)") }
                        )
                    }
                    items(geminiModels, key = { it.id }) { m ->
                        FilterChip(
                            selected = selectedGemini == m.id,
                            onClick = { selectedGemini = m.id },
                            label = { Text(m.id) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("OpenRouter Model (API Driven)", style = MaterialTheme.typography.labelLarge)
                    Row {
                        FilterChip(
                            selected = onlyFreeFilter,
                            onClick = { onlyFreeFilter = !onlyFreeFilter },
                            label = { Text("Free Only") }
                        )
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(onClick = onFetchOpenRouterModels, enabled = !isFetchingModels) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (isFetchingModels) "Loading..." else "Discover")
                        }
                    }
                }
                val visibleOrModels = openRouterModels.filter { !onlyFreeFilter || it.isFree }.take(25)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = selectedOpenRouter == AUTO_MODEL_ID,
                            onClick = { selectedOpenRouter = AUTO_MODEL_ID },
                            label = { Text("Auto (API অনুযায়ী সিলেক্ট)") }
                        )
                    }
                    items(visibleOrModels, key = { it.id }) { m ->
                        FilterChip(
                            selected = selectedOpenRouter == m.id,
                            onClick = { selectedOpenRouter = m.id },
                            label = { Text(if (m.isFree) "${m.name} [FREE]" else m.name) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                SwitchRow("Full Autonomous Mode (অটো-কাজ)", fullAutonomous) { fullAutonomous = it }
                SwitchRow("Response Streaming (SSE)", streaming) { streaming = it }
                SwitchRow("Automatic Provider Fallback (Gemini -> OR)", autoFallback) { autoFallback = it }
                SwitchRow("Prioritize Fast Models (Flash)", preferFree) { preferFree = it }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        onSaveProviderConfig(provider, selectedGemini, selectedOpenRouter, streaming, autoFallback, preferFree, fullAutonomous)
                    },
                    modifier = Modifier.fillMaxWidth().testTag("save_router_config_button")
                ) {
                    Text("Save Router Settings")
                }
            }
        }

        // YouTube OAuth Token (Optional) & Theme Mode
        item {
            GlassOsCard {
                Text("Optional YouTube Data API OAuth Token", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Token status: $youtubeTokenMasked. Used only when you confirm a Level 2 YouTube upload.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = ytTokenInput,
                    onValueChange = { ytTokenInput = it },
                    placeholder = { Text("Optional Bearer OAuth token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSaveSecret(SecretSlot.YOUTUBE_OAUTH_TOKEN, ytTokenInput); ytTokenInput = "" }) {
                        Text("Save Token")
                    }
                    TextButton(onClick = { onDeleteSecret(SecretSlot.YOUTUBE_OAUTH_TOKEN) }) { Text("Clear") }
                }
            }
        }

        item {
            GlassOsCard {
                Text("Appearance Theme", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { onChangeTheme(mode) },
                            label = { Text(mode.name) }
                        )
                    }
                }
            }
        }

        if (usageHistory.isNotEmpty()) {
            item {
                val totalTokens = usageHistory.sumOf { it.promptTokens + it.completionTokens }
                GlassOsCard {
                    Text("Local Token Usage Log ($totalTokens tokens across ${usageHistory.size} calls)", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

@Composable
fun ResourceManagerScreen(
    settings: AppSettings,
    resources: DeviceResourceSnapshot,
    presetsMb: List<Int>,
    storagePresetsMb: List<Int>,
    onRefresh: () -> Unit,
    onSaveResourceConfig: (Int, Int, Boolean, Boolean, PowerProfile, Int, Boolean) -> Unit,
    onClearDir: (String) -> Unit
) {
    var budgetMb by remember(settings.ramBudgetMb) { mutableIntStateOf(settings.ramBudgetMb) }
    var customBudgetInput by remember(settings.ramBudgetMb) { mutableStateOf(settings.ramBudgetMb.toString()) }
    var storageQuotaMb by remember(settings.storageQuotaMb) { mutableIntStateOf(settings.storageQuotaMb) }
    var customStorageInput by remember(settings.storageQuotaMb) { mutableStateOf(settings.storageQuotaMb.toString()) }
    var adaptiveScaling by remember(settings.adaptiveDeviceScaling) { mutableStateOf(settings.adaptiveDeviceScaling) }
    var lowResource by remember(settings.lowResourceMode) { mutableStateOf(settings.lowResourceMode) }
    var powerProfile by remember(settings.powerProfile) { mutableStateOf(settings.powerProfile) }
    var concurrency by remember(settings.maxConcurrency) { mutableFloatStateOf(settings.maxConcurrency.toFloat()) }
    var confirmClearTarget by remember { mutableStateOf<String?>(null) }
    var showRestartConfirm by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("resource_manager_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionHeader(
                title = "RAM & ROM Engine Manager",
                subtitle = "যেকোনো Android ফোনের ক্ষমতা অনুযায়ী কাস্টম RAM/ROM সেটআপ ও সেভ-রিস্টার্ট করুন।",
                icon = Icons.Filled.Memory,
                trailing = {
                    OutlinedButton(onClick = onRefresh) { Text("Refresh") }
                }
            )
        }

        resources.warningMessage?.let { warn ->
            item {
                GlassOsCard(borderColor = AmberWarning) {
                    Text(warn, style = MaterialTheme.typography.bodySmall, color = AmberWarning)
                }
            }
        }

        item {
            GlassOsCard(borderColor = NeuralCyan.copy(alpha = 0.6f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Detected Hardware Tier", style = MaterialTheme.typography.titleSmall)
                    StatusBadge(label = resources.deviceTierLabel, color = EmeraldPulse)
                }
                Spacer(Modifier.height(8.dp))
                TelemetryMeter(
                    label = "Device Physical RAM (${resources.availableRamMb} MB free / ${resources.totalPhysicalRamMb} MB total)",
                    valueText = resources.memoryPressureLabel,
                    progress = resources.usedRamPercent,
                    accentColor = NeuralCyan
                )
                Spacer(Modifier.height(8.dp))
                val romProgress = if (resources.storageQuotaMb > 0) {
                    (resources.virtualEnvUsedMb.toFloat() / resources.storageQuotaMb.toFloat()).coerceIn(0f, 1f)
                } else 0f
                TelemetryMeter(
                    label = "Workspace ROM Used (${"%.1f".format(resources.virtualEnvUsedMb)} MB / ${resources.storageQuotaMb} MB Quota)",
                    valueText = "${"%.1f".format(resources.storageFreeGb)} GB Device Free",
                    progress = romProgress,
                    accentColor = EmeraldPulse
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Heap: ${resources.appHeapUsedMb}/${resources.appHeapMaxMb} MB • CPU: ${resources.cpuCores} cores (${resources.primaryAbi}) • Adaptive Max Steps: ${resources.recommendedMaxAgentSteps}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            GlassOsCard {
                Text("Custom RAM Budget (MB)", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(presetsMb) { mb ->
                        val label = if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"
                        FilterChip(
                            selected = budgetMb == mb,
                            onClick = {
                                budgetMb = mb
                                customBudgetInput = mb.toString()
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customBudgetInput,
                    onValueChange = {
                        customBudgetInput = it.filter { c -> c.isDigit() }
                        customBudgetInput.toIntOrNull()?.let { v -> budgetMb = v }
                    },
                    label = { Text("Custom RAM Budget (MB)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("custom_ram_budget_input")
                )

                Spacer(Modifier.height(12.dp))
                Text("Custom ROM / Workspace Storage Quota (MB)", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(storagePresetsMb) { mb ->
                        val label = if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"
                        FilterChip(
                            selected = storageQuotaMb == mb,
                            onClick = {
                                storageQuotaMb = mb
                                customStorageInput = mb.toString()
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customStorageInput,
                    onValueChange = {
                        customStorageInput = it.filter { c -> c.isDigit() }
                        customStorageInput.toIntOrNull()?.let { v -> storageQuotaMb = v }
                    },
                    label = { Text("Custom ROM Quota (MB)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("custom_rom_quota_input")
                )

                Spacer(Modifier.height(10.dp))
                Text("CPU & Power Mode", style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(PowerProfile.entries) { p ->
                        FilterChip(
                            selected = powerProfile == p,
                            onClick = { powerProfile = p },
                            label = { Text(p.label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                SwitchRow("Hardware-Adaptive Auto-Scaling (যেকোনো মোবাইলে অটো অপ্টিমাইজ)", adaptiveScaling) {
                    adaptiveScaling = it
                }
                SwitchRow("Low Resource Mode (reduces steps & concurrency)", lowResource) { lowResource = it }
                Text(
                    "Execution Concurrency Limit: ${concurrency.roundToInt()} (Effective: ${resources.effectiveConcurrency} • Max AI Steps: ${resources.recommendedMaxAgentSteps})",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = concurrency,
                    onValueChange = { concurrency = it },
                    valueRange = 1f..4f,
                    steps = 2
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "সুরক্ষা গ্যারান্টি: রিস্টার্ট করলে আপনার API Key (#1-#5 + OpenRouter), স্কিল, কোড, CAD ডিজাইন ও চ্যাট মেমোরি ১০০% অক্ষত থাকবে।",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmeraldPulse
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onSaveResourceConfig(
                                budgetMb,
                                storageQuotaMb,
                                adaptiveScaling,
                                lowResource,
                                powerProfile,
                                concurrency.roundToInt(),
                                false
                            )
                        },
                        modifier = Modifier.weight(1f).testTag("save_resource_budget_button")
                    ) {
                        Text("Apply Live")
                    }
                    Button(
                        onClick = { showRestartConfirm = true },
                        modifier = Modifier.weight(1.25f).testTag("save_and_restart_resource_button")
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save & Restart")
                    }
                }
            }
        }

        item {
            Text("Storage Directories", style = MaterialTheme.typography.titleSmall)
        }
        items(resources.directoryStats) { stat ->
            GlassOsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stat.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${stat.fileCount} files • ${formatBytes(stat.bytesUsed)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (stat.clearable) {
                        OutlinedButton(onClick = { confirmClearTarget = stat.name }) {
                            Text("Clean")
                        }
                    } else {
                        StatusBadge(label = "Protected", color = EmeraldPulse)
                    }
                }
            }
        }
    }

    if (showRestartConfirm) {
        AlertDialog(
            onDismissRequest = { showRestartConfirm = false },
            title = { Text("সেভ করে রিস্টার্ট করবেন?") },
            text = {
                Text("নতুন RAM ($budgetMb MB) ও ROM ($storageQuotaMb MB) বাজেট কার্যকর করতে অ্যাপটি নিরাপদভাবে রিস্টার্ট হবে। আপনার কোনো API Key, কোড, CAD ফাইল বা মেমোরি মুছবে না।")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestartConfirm = false
                        onSaveResourceConfig(
                            budgetMb,
                            storageQuotaMb,
                            adaptiveScaling,
                            lowResource,
                            powerProfile,
                            concurrency.roundToInt(),
                            true
                        )
                    },
                    modifier = Modifier.testTag("confirm_safe_restart_button")
                ) { Text("হ্যাঁ, রিস্টার্ট করুন") }
            },
            dismissButton = {
                TextButton(onClick = { showRestartConfirm = false }) { Text("বাতিল") }
            }
        )
    }

    confirmClearTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmClearTarget = null },
            title = { Text("Clean $target?") },
            text = { Text("All files inside $target will be permanently deleted.") },
            confirmButton = {
                Button(onClick = { onClearDir(target); confirmClearTarget = null }) { Text("Clean") }
            },
            dismissButton = { TextButton(onClick = { confirmClearTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun PermissionsAndSecurityScreen(
    accessibilityConnected: Boolean,
    networkEvents: List<NetworkEvent>,
    onOpenAccessibilitySettings: () -> Unit,
    onClearAllData: () -> Unit
) {
    val context = LocalContext.current
    var showWipeConfirm by remember { mutableStateOf(false) }

    val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else true

    val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("permissions_security_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionHeader(
                title = "Permissions, Privacy & Security",
                subtitle = "Zero hidden servers. Local-first data & Android Keystore encryption.",
                icon = Icons.Filled.Security
            )
        }

        item {
            GlassOsCard {
                Text("System Permissions", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                PermissionActionRow(
                    title = "Accessibility Virtual Hands",
                    status = if (accessibilityConnected) "Enabled" else "Disabled",
                    isOk = accessibilityConnected,
                    actionLabel = "Settings",
                    onAction = onOpenAccessibilitySettings
                )
                Spacer(Modifier.height(8.dp))
                PermissionActionRow(
                    title = "Notifications (Scheduled Tasks)",
                    status = if (notifGranted) "Granted" else "Optional",
                    isOk = notifGranted,
                    actionLabel = "Grant",
                    onAction = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
                PermissionActionRow(
                    title = "Microphone (Voice Input)",
                    status = if (micGranted) "Granted" else "On Demand",
                    isOk = micGranted,
                    actionLabel = "Grant",
                    onAction = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                )
            }
        }

        item {
            GlassOsCard {
                Text("3-Tier Permission Policy", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                PermissionLevel.entries.forEach { lvl ->
                    Text("${lvl.title}: ${lvl.description}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        item {
            GlassOsCard(borderColor = CoralAlert.copy(alpha = 0.6f)) {
                Text("Data Control & Secure Wipe", style = MaterialTheme.typography.titleSmall, color = CoralAlert)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Erase all task history, agent memory, audit logs, and encrypted API keys from this device.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { showWipeConfirm = true },
                    colors = ButtonDefaults.buttonColors(containerColor = CoralAlert)
                ) {
                    Icon(Icons.Filled.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Wipe Local Vault & History")
                }
            }
        }

        item {
            Text("Outbound Network Visibility (${networkEvents.size})", style = MaterialTheme.typography.titleSmall)
        }
        if (networkEvents.isEmpty()) {
            item {
                GlassOsCard {
                    Text(
                        "No outbound network requests recorded in this session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(networkEvents.take(20), key = { it.id }) { ev ->
                GlassOsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(ev.destination, style = MaterialTheme.typography.titleSmall)
                        StatusBadge(label = ev.status, color = NeuralCyan)
                    }
                    Text("${ev.purpose} • ${formatBytes(ev.bytesTransferred)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (showWipeConfirm) {
        AlertDialog(
            onDismissRequest = { showWipeConfirm = false },
            title = { Text("Wipe All Local Data?") },
            text = { Text("This deletes all encrypted API keys, agent memory, and task logs immediately.") },
            confirmButton = {
                Button(onClick = { onClearAllData(); showWipeConfirm = false }) { Text("Confirm Wipe") }
            },
            dismissButton = { TextButton(onClick = { showWipeConfirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun AboutApplicationScreen() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("about_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionHeader(
                title = "Personal AI Computer v1.0.0",
                subtitle = "Local-First Autonomous Mobile Agent Operating Environment",
                icon = Icons.Filled.Info
            )
        }
        item {
            GlassOsCard {
                Text("Architecture & Honest Technical Boundaries", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "• AI Brain: Direct HTTPS to Google Gemini API & OpenRouter API using Android Keystore AES-256-GCM key storage. No proxy server or mandatory cloud backend.\n" +
                        "• Local Virtual Linux Environment: Runs POSIX /system/bin/sh inside sandboxed virtual_env/, native or PyLite on-device Python 3 engine, Android V8 WebView JavaScript/TypeScript engine, and a localhost:8080 HTTP dev server. Stock unrooted Android cannot run a full KVM VM or bundle desktop Playwright Chromium binaries.\n" +
                        "• Browser + Virtual Hands: Multi-tab Chromium WebView DOM automation adapter plus explicit Android AccessibilityService gesture & node inspection with strict banking/OTP/password safeguards.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PermissionActionRow(
    title: String,
    status: String,
    isOk: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(status, style = MaterialTheme.typography.labelSmall, color = if (isOk) EmeraldPulse else AmberWarning)
        }
        OutlinedButton(onClick = onAction) { Text(actionLabel) }
    }
}
