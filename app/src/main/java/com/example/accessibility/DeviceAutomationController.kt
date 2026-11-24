package com.example.accessibility

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.flow.StateFlow

data class InstalledAppSummary(
    val appName: String,
    val packageName: String,
    val versionName: String
)

/**
 * Separates Controller A (Internal Virtual Environment) from Controller B (Android Accessibility).
 */
class DeviceAutomationController(private val context: Context) {

    val accessibilityConnected: StateFlow<Boolean> = PersonalAiAccessibilityService.isConnected
    val activePackage: StateFlow<String> = PersonalAiAccessibilityService.activePackage

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun inspectScreen(): Result<List<ObservedUiNode>> {
        val svc = PersonalAiAccessibilityService.instance
            ?: return Result.failure(IllegalStateException("Accessibility Service is disabled. Enable 'AI Computer Virtual Hands' in Android Settings."))
        return svc.inspectActiveWindowNodes()
    }

    fun tap(x: Float, y: Float): Result<String> {
        val svc = PersonalAiAccessibilityService.instance
            ?: return Result.failure(IllegalStateException("Accessibility Service not enabled"))
        return if (svc.performTap(x, y)) Result.success("Tapped ($x, $y)")
        else Result.failure(IllegalStateException("Tap blocked or failed"))
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Result<String> {
        val svc = PersonalAiAccessibilityService.instance
            ?: return Result.failure(IllegalStateException("Accessibility Service not enabled"))
        return if (svc.performSwipe(x1, y1, x2, y2)) Result.success("Swiped ($x1,$y1)->($x2,$y2)")
        else Result.failure(IllegalStateException("Swipe blocked or failed"))
    }

    fun clickText(text: String): Result<String> {
        val svc = PersonalAiAccessibilityService.instance
            ?: return Result.failure(IllegalStateException("Accessibility Service not enabled"))
        return if (svc.clickNodeByText(text)) Result.success("Clicked UI node '$text'")
        else Result.failure(IllegalStateException("No clickable node matching '$text'"))
    }

    fun typeIntoField(query: String, text: String): Result<String> {
        val svc = PersonalAiAccessibilityService.instance
            ?: return Result.failure(IllegalStateException("Accessibility Service not enabled"))
        return if (svc.setTextByQuery(query, text)) Result.success("Entered text into '$query'")
        else Result.failure(IllegalStateException("No editable node matching '$query'"))
    }

    fun globalNav(action: String): Result<String> {
        val svc = PersonalAiAccessibilityService.instance
            ?: return Result.failure(IllegalStateException("Accessibility Service not enabled"))
        val ok = when (action.lowercase()) {
            "back" -> svc.performSystemBack()
            "home" -> svc.performSystemHome()
            "recents" -> svc.performSystemRecents()
            "notifications" -> svc.performSystemNotifications()
            else -> false
        }
        return if (ok) Result.success("Performed global action: $action")
        else Result.failure(IllegalStateException("Unknown or failed global action: $action"))
    }

    fun listLaunchableApps(): List<InstalledAppSummary> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolved = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        return resolved.map { ri ->
            val pkg = ri.activityInfo.packageName
            val label = ri.loadLabel(pm)?.toString() ?: pkg
            val ver = runCatching { pm.getPackageInfo(pkg, 0).versionName ?: "1.0" }.getOrDefault("1.0")
            InstalledAppSummary(label, pkg, ver)
        }.distinctBy { it.packageName }.sortedBy { it.appName.lowercase() }
    }

    fun launchApp(packageName: String): Result<String> = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("App '$packageName' cannot be launched directly.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        "Launched $packageName"
    }

    fun openAppDetailsSettings(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openExternalUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
