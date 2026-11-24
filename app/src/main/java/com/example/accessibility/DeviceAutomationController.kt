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
 * Controller B: Authorized Android Accessibility Virtual Hands & Launcher intents.
 */
class DeviceAutomationController(private val context: Context) {

    val accessibilityConnected: StateFlow<Boolean> = PersonalAiAccessibilityService.isConnected
    val activePackage: StateFlow<String> = PersonalAiAccessibilityService.activePackage
    val liveNodes: StateFlow<List<ObservedUiNode>> = PersonalAiAccessibilityService.liveNodes

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

    fun doubleTap(x: Float, y: Float): Result<String> {
        val svc = PersonalAiAccessibilityService.instance
            ?: return Result.failure(IllegalStateException("Accessibility Service not enabled"))
        return if (svc.performDoubleTap(x, y)) Result.success("Double-tapped ($x, $y)")
        else Result.failure(IllegalStateException("Double tap blocked or failed"))
    }

    fun longPress(x: Float, y: Float): Result<String> {
        val svc = PersonalAiAccessibilityService.instance
            ?: return Result.failure(IllegalStateException("Accessibility Service not enabled"))
        return if (svc.performLongPress(x, y)) Result.success("Long-pressed ($x, $y)")
        else Result.failure(IllegalStateException("Long press blocked or failed"))
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Result<String> {
        val svc = PersonalAiAccessibilityService.instance
            ?: return Result.failure(IllegalStateException("Accessibility Service not enabled"))
        return if (svc.performSwipe(x1, y1, x2, y2)) Result.success("Swiped ($x1,$y1)->($x2,$y2)")
        else Result.failure(IllegalStateException("Swipe blocked or failed"))
    }

    fun scroll(direction: String): Result<String> {
        val svc = PersonalAiAccessibilityService.instance
            ?: return Result.failure(IllegalStateException("Accessibility Service not enabled"))
        return if (svc.performDirectionalScroll(direction)) Result.success("Scrolled screen $direction")
        else Result.failure(IllegalStateException("Scroll blocked or failed"))
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
        val ok = if (query.isBlank()) svc.setTextInFocusedNode(text) else svc.setTextByQuery(query, text)
        return if (ok) Result.success("Entered text into '${query.ifBlank { "focused field" }}'")
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
            "quick_settings" -> svc.performSystemQuickSettings()
            "screenshot" -> svc.performSystemScreenshot()
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
            val label = ri.loadLabel(pm).toString()
            val ver = runCatching { pm.getPackageInfo(pkg, 0).versionName ?: "1.0" }.getOrDefault("1.0")
            InstalledAppSummary(label, pkg, ver)
        }.distinctBy { it.packageName }.sortedBy { it.appName.lowercase() }
    }

    fun launchApp(packageOrName: String): Result<String> = runCatching {
        val q = packageOrName.trim()
        val directIntent = context.packageManager.getLaunchIntentForPackage(q)
        if (directIntent != null) {
            directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(directIntent)
            return@runCatching "Launched $q"
        }
        val apps = listLaunchableApps()
        val match = apps.firstOrNull {
            it.appName.equals(q, ignoreCase = true) ||
                it.appName.contains(q, ignoreCase = true) ||
                it.packageName.contains(q, ignoreCase = true)
        } ?: error("App '$q' not found or cannot be launched.")
        val intent = context.packageManager.getLaunchIntentForPackage(match.packageName)
            ?: error("App '${match.appName}' has no launchable activity.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        "Launched ${match.appName} (${match.packageName})"
    }

    fun openActiveOrLastApp(): Result<String> {
        val pkg = _lastExternalOrActivePkg()
        return if (pkg.isNullOrBlank() || pkg == context.packageName) {
            Result.failure(IllegalStateException("No external app is currently active"))
        } else {
            launchApp(pkg)
        }
    }

    private fun _lastExternalOrActivePkg(): String? {
        val pkg = activePackage.value
        return if (pkg == "None" || pkg.isBlank()) null else pkg
    }

    fun openAppDetailsSettings(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openExternalUrl(url: String): Result<String> = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        "Opened $url in external browser"
    }
}
