package com.example.accessibility

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.example.security.CommandSecurityGuard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.net.URLEncoder

data class InstalledAppSummary(
    val appName: String,
    val packageName: String,
    val versionName: String
)

/**
 * Controller B: Authorized Android Accessibility Virtual Hands, Play Store Intents, Clipboard & App Launcher.
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

    fun getScreenSizeLabel(): String {
        val svc = PersonalAiAccessibilityService.instance
        if (svc != null) {
            val (w, h) = svc.getScreenSize()
            return "${w}x${h}"
        }
        val dm = context.resources.displayMetrics
        return "${dm.widthPixels}x${dm.heightPixels}"
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

    suspend fun scrollToText(query: String, maxScrolls: Int = 5): Result<String> {
        val svc = PersonalAiAccessibilityService.instance
            ?: return Result.failure(IllegalStateException("Accessibility Service not enabled"))
        val q = query.trim().lowercase()
        repeat(maxScrolls.coerceIn(1, 8)) { attempt ->
            val nodes = svc.inspectActiveWindowNodes().getOrElse { emptyList() }
            val match = nodes.firstOrNull {
                it.text.lowercase().contains(q) ||
                    it.contentDesc.lowercase().contains(q) ||
                    it.viewId.lowercase().contains(q)
            }
            if (match != null) {
                return Result.success("Found '$query' at (${match.centerX.toInt()},${match.centerY.toInt()}) after $attempt scroll(s)")
            }
            svc.performDirectionalScroll("down")
            delay(260)
        }
        return Result.failure(IllegalStateException("Text '$query' not found after scrolling $maxScrolls times"))
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

    fun pressEnter(): Result<String> {
        val svc = PersonalAiAccessibilityService.instance
            ?: return Result.failure(IllegalStateException("Accessibility Service not enabled"))
        return if (svc.performImeEnter()) Result.success("Submitted / pressed Enter")
        else Result.failure(IllegalStateException("Could not trigger Enter/Submit on current screen"))
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
        } ?: error("App '$q' is not installed on this device.")
        val intent = context.packageManager.getLaunchIntentForPackage(match.packageName)
            ?: error("App '${match.appName}' has no launchable activity.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        "Launched ${match.appName} (${match.packageName})"
    }

    /**
     * Play-Policy compliant app installation via Google Play Store Intent.
     */
    fun openPlayStoreForApp(appNameOrPackage: String): Result<String> = runCatching {
        val q = appNameOrPackage.trim()
        require(q.isNotBlank()) { "App name or package cannot be empty." }
        val isPackageId = Regex("""^[a-zA-Z0-9_]+(\.[a-zA-Z0-9_]+){1,}$""").matches(q)
        val marketUri = if (isPackageId) {
            Uri.parse("market://details?id=$q")
        } else {
            Uri.parse("market://search?q=${URLEncoder.encode(q, "UTF-8")}&c=apps")
        }
        val marketIntent = Intent(Intent.ACTION_VIEW, marketUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching {
            context.startActivity(marketIntent)
            true
        }.getOrDefault(false)

        if (!launched) {
            val webUri = if (isPackageId) {
                Uri.parse("https://play.google.com/store/apps/details?id=$q")
            } else {
                Uri.parse("https://play.google.com/store/search?q=${URLEncoder.encode(q, "UTF-8")}&c=apps")
            }
            context.startActivity(Intent(Intent.ACTION_VIEW, webUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        "Opened Google Play Store for '$q'. You can now inspect screen or tap Install via Virtual Hands."
    }

    fun getClipboardText(): Result<String> = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (CommandSecurityGuard.containsSensitiveCredential(text)) {
            "[REDACTED: Clipboard appears to contain sensitive credential]"
        } else {
            text.ifBlank { "(Clipboard is empty)" }
        }
    }

    fun setClipboardText(text: String): Result<String> = runCatching {
        if (CommandSecurityGuard.containsSensitiveCredential(text)) {
            error("Security Policy: Cannot write sensitive credential text to clipboard.")
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AI Computer", text))
        "Copied ${text.length} chars to clipboard"
    }

    fun sendShareOrIntent(actionType: String, target: String, body: String = ""): Result<String> = runCatching {
        val typeLower = actionType.lowercase().trim()
        val intent = when (typeLower) {
            "share_file" -> {
                val baseDir = java.io.File(context.filesDir, "virtual_env").canonicalFile
                val relClean = target.trim().trimStart('/')
                val file = java.io.File(baseDir, relClean).canonicalFile
                require(file.path.startsWith(baseDir.path) && file.exists() && file.isFile) {
                    "Workspace file not found: $target"
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val mime = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(file.extension.lowercase())
                    ?: when (file.extension.lowercase()) {
                        "jpg", "jpeg" -> "image/jpeg"
                        "png" -> "image/png"
                        "webp" -> "image/webp"
                        "mp4" -> "video/mp4"
                        "pdf" -> "application/pdf"
                        else -> "*/*"
                    }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val targetHint = body.trim()
                val matchedPkg = if (targetHint.isNotBlank()) {
                    listLaunchableApps().firstOrNull {
                        it.packageName.equals(targetHint, ignoreCase = true) ||
                            it.appName.contains(targetHint, ignoreCase = true) ||
                            it.packageName.contains(targetHint, ignoreCase = true)
                    }?.packageName
                } else null
                if (!matchedPkg.isNullOrBlank()) {
                    send.setPackage(matchedPkg)
                    send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    send
                } else {
                    if (targetHint.isNotBlank()) send.putExtra(Intent.EXTRA_TEXT, targetHint)
                    Intent.createChooser(send, "Share ${file.name}").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
            }
            "share" -> Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, body.ifBlank { target })
                },
                "Share via AI Computer"
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            "dial" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:${target.trim()}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            "email" -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${target.trim()}")).apply {
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            "url" -> Intent(Intent.ACTION_VIEW, Uri.parse(target.trim())).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            else -> error("Unsupported intent action '$actionType'. Use: share_file, share, dial, email, url")
        }
        context.startActivity(intent)
        "Dispatched Android $actionType intent for '$target'"
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
