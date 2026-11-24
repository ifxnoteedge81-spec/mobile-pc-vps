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

    val presetRamBudgetsMb = listOf(512, 1024, 2048, 3072, 4096, 6144, 8192)

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

        val dirStats = listOf(
            statDir("Workspace", workspaceManager.workspaceDir, clearable = false),
            statDir("Projects", workspaceManager.projectsDir, clearable = false),
            statDir("Scripts", workspaceManager.scriptsDir, clearable = false),
            statDir("Downloads", workspaceManager.downloadsDir, clearable = true),
            statDir("Agent Memory", workspaceManager.memoryDir, clearable = false),
            statDir("Temporary Files", workspaceManager.tmpDir, clearable = true),
            statDir("App Cache", context.cacheDir, clearable = true)
        )

        val recommendedConcurrency = computeEffectiveConcurrency(settings, availRamMb, memInfo.lowMemory)
        val warning = when {
            memInfo.lowMemory -> "Android reports low system memory (${availRamMb} MB free). Concurrency reduced to $recommendedConcurrency."
            settings.ramBudgetMb > totalRamMb -> "Selected budget (${settings.ramBudgetMb} MB) exceeds physical RAM (${totalRamMb} MB). Execution limits clamped safely."
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
            cpuCores = runtime.availableProcessors(),
            primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            cpuLoadNote = readCpuNote(),
            storageFreeGb = freeBytes / (1024.0 * 1024.0 * 1024.0),
            storageTotalGb = totalBytes / (1024.0 * 1024.0 * 1024.0),
            directoryStats = dirStats,
            effectiveConcurrency = recommendedConcurrency,
            warningMessage = warning
        )
        _snapshot.value = result
        result
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
