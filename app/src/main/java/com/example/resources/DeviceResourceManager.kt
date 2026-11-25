package com.example.resources

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.example.runtime.VirtualWorkspaceManager
import com.example.storage.AppSettings
import com.example.storage.PowerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

data class DirectoryStorageStat(
    val name: String,
    val path: String,
    val bytesUsed: Long,
    val fileCount: Int,
    val clearable: Boolean
)

data class DeviceResourceSnapshot(
    val totalPhysicalRamMb: Long = 0,
    val availableRamMb: Long = 0,
    val usedRamPercent: Float = 0f,
    val appHeapUsedMb: Long = 0,
    val appHeapMaxMb: Long = 0,
    val lowMemoryFlag: Boolean = false,
    val memoryPressureLabel: String = "Normal",
    val cpuCores: Int = 1,
    val primaryAbi: String = "arm64-v8a",
    val cpuLoadNote: String = "",
    val storageFreeGb: Double = 0.0,
    val storageTotalGb: Double = 0.0,
    val virtualEnvUsedMb: Double = 0.0,
    val storageQuotaMb: Int = 2048,
    val deviceTierLabel: String = "Standard Android",
    val recommendedMaxAgentSteps: Int = 24,
    val directoryStats: List<DirectoryStorageStat> = emptyList(),
    val effectiveConcurrency: Int = 2,
    val warningMessage: String? = null
)

class DeviceResourceManager(
    private val context: Context,
    private val workspaceManager: VirtualWorkspaceManager
) {
    private val _snapshot = MutableStateFlow(DeviceResourceSnapshot())
    val snapshot: StateFlow<DeviceResourceSnapshot> = _snapshot.asStateFlow()

    val presetRamBudgetsMb = listOf(512, 1024, 2048, 3072, 4096, 6144, 8192, 12288)
    val presetStorageQuotasMb = listOf(512, 1024, 2048, 4096, 8192, 16384)

    suspend fun refresh(settings: AppSettings): DeviceResourceSnapshot = withContext(Dispatchers.IO) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val totalRamMb = memInfo.totalMem / (1024L * 1024L)
        val availRamMb = memInfo.availMem / (1024L * 1024L)
        val usedPercent = if (totalRamMb > 0) ((totalRamMb - availRamMb).toFloat() / totalRamMb.toFloat()).coerceIn(0f, 1f) else 0f

        val runtime = Runtime.getRuntime()
        val heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        val heapMaxMb = runtime.maxMemory() / (1024L * 1024L)

        val pressure = when {
            memInfo.lowMemory || availRamMb < 350 -> "Critical"
            availRamMb < 750 || usedPercent > 0.85f -> "Elevated"
            else -> "Nominal"
        }

        val statFs = StatFs(context.filesDir.absolutePath)
        val freeBytes = statFs.availableBytes
        val totalBytes = statFs.totalBytes
        val freeMb = freeBytes / (1024L * 1024L)

        val dirStats = listOf(
            statDir("Workspace", workspaceManager.workspaceDir, clearable = false),
            statDir("Projects & CAD", workspaceManager.projectsDir, clearable = false),
            statDir("Scripts", workspaceManager.scriptsDir, clearable = false),
            statDir("Imported Skills", workspaceManager.skillsDir, clearable = false),
            statDir("Downloads", workspaceManager.downloadsDir, clearable = true),
            statDir("Agent Memory", workspaceManager.memoryDir, clearable = false),
            statDir("Temporary Files", workspaceManager.tmpDir, clearable = true),
            statDir("App Cache", context.cacheDir, clearable = true)
        )
        val virtualUsedMb = dirStats.filterNot { it.name == "App Cache" }
            .sumOf { it.bytesUsed } / (1024.0 * 1024.0)

        val cpuCores = runtime.availableProcessors()
        val deviceTier = when {
            totalRamMb >= 7500 && cpuCores >= 8 -> "Flagship (${totalRamMb / 1024}GB+ RAM • High-Speed)"
            totalRamMb >= 3800 -> "Mid-Range (${(totalRamMb / 1024.0).let { "%.1f".format(it) }}GB RAM • Balanced)"
            else -> "Compact (<4GB RAM • Eco-Adaptive)"
        }

        val recommendedConcurrency = computeEffectiveConcurrency(settings, availRamMb, memInfo.lowMemory)
        val recommendedSteps = computeRecommendedSteps(settings, totalRamMb, memInfo.lowMemory)

        val warning = when {
            memInfo.lowMemory -> "Android reports low system memory (${availRamMb} MB free). Concurrency reduced to $recommendedConcurrency."
            settings.ramBudgetMb > totalRamMb -> "Selected RAM budget (${settings.ramBudgetMb} MB) exceeds physical RAM (${totalRamMb} MB). Safeguards active."
            settings.storageQuotaMb > freeMb -> "Custom ROM quota (${settings.storageQuotaMb} MB) exceeds device free storage ($freeMb MB)."
            virtualUsedMb > settings.storageQuotaMb * 0.9 -> "Workspace storage (${"%.0f".format(virtualUsedMb)} MB) is nearing your ROM quota (${settings.storageQuotaMb} MB)."
            availRamMb < 450 -> "Available RAM is tight (${availRamMb} MB). Enable Low Resource Mode for heavy tasks."
            else -> null
        }

        val result = DeviceResourceSnapshot(
            totalPhysicalRamMb = totalRamMb,
            availableRamMb = availRamMb,
            usedRamPercent = usedPercent,
            appHeapUsedMb = heapUsedMb,
            appHeapMaxMb = heapMaxMb,
            lowMemoryFlag = memInfo.lowMemory,
            memoryPressureLabel = pressure,
            cpuCores = cpuCores,
            primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            cpuLoadNote = readCpuNote(),
            storageFreeGb = freeBytes / (1024.0 * 1024.0 * 1024.0),
            storageTotalGb = totalBytes / (1024.0 * 1024.0 * 1024.0),
            virtualEnvUsedMb = virtualUsedMb,
            storageQuotaMb = settings.storageQuotaMb,
            deviceTierLabel = deviceTier,
            recommendedMaxAgentSteps = recommendedSteps,
            directoryStats = dirStats,
            effectiveConcurrency = recommendedConcurrency,
            warningMessage = warning
        )
        _snapshot.value = result
        result
    }

    fun computeRecommendedSteps(
        settings: AppSettings,
        totalRamMb: Long,
        lowMemory: Boolean
    ): Int {
        if (lowMemory || settings.lowResourceMode || settings.powerProfile == PowerProfile.LOW_POWER) {
            return 14
        }
        val base = when (settings.powerProfile) {
            PowerProfile.LOW_POWER -> 14
            PowerProfile.BALANCED -> 24
            PowerProfile.PERFORMANCE -> 36
        }
        return if (!settings.adaptiveDeviceScaling) base
        else when {
            totalRamMb < 3200 -> minOf(base, 18)
            totalRamMb >= 7500 && settings.ramBudgetMb >= 3072 -> maxOf(base, 32)
            else -> base
        }
    }

    fun computeEffectiveConcurrency(
        settings: AppSettings,
        availRamMb: Long,
        lowMemory: Boolean
    ): Int {
        if (lowMemory || settings.lowResourceMode || settings.powerProfile == PowerProfile.LOW_POWER) {
            return 1
        }
        val budgetCap = when {
            settings.ramBudgetMb <= 512 -> 1
            settings.ramBudgetMb <= 1024 -> 2
            settings.ramBudgetMb <= 2048 -> 3
            else -> 4
        }
        val deviceCap = when {
            availRamMb < 500 -> 1
            availRamMb < 1200 -> 2
            else -> 4
        }
        val profileBonus = if (settings.powerProfile == PowerProfile.PERFORMANCE) 1 else 0
        return (settings.maxConcurrency + profileBonus).coerceAtMost(minOf(budgetCap, deviceCap)).coerceAtLeast(1)
    }

    fun clearDirectorySafely(targetName: String): Boolean {
        val dir = when (targetName) {
            "Temporary Files" -> workspaceManager.tmpDir
            "App Cache" -> context.cacheDir
            "Downloads" -> workspaceManager.downloadsDir
            else -> return false
        }
        dir.listFiles()?.forEach { it.deleteRecursively() }
        return true
    }

    private fun statDir(name: String, dir: File, clearable: Boolean): DirectoryStorageStat {
        var bytes = 0L
        var count = 0
        if (dir.exists()) {
            dir.walkTopDown().forEach { f ->
                if (f.isFile) {
                    bytes += f.length()
                    count++
                }
            }
        }
        return DirectoryStorageStat(
            name = name,
            path = dir.absolutePath,
            bytesUsed = bytes,
            fileCount = count,
            clearable = clearable
        )
    }

    private fun readCpuNote(): String {
        val socLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.DEVICE
        return runCatching {
            val hw = File("/proc/cpuinfo").takeIf { it.canRead() }
                ?.readLines()
                ?.firstOrNull { it.startsWith("Hardware") || it.startsWith("model name") }
                ?.substringAfter(":")
                ?.trim()
            if (!hw.isNullOrBlank()) "$hw (${Build.BOARD})" else "${Build.HARDWARE} • $socLabel"
        }.getOrElse { "${Build.HARDWARE} • $socLabel" }
    }
}
