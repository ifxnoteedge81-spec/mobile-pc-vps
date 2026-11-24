package com.example.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.security.CommandSecurityGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ObservedUiNode(
    val text: String,
    val contentDesc: String,
    val className: String,
    val bounds: String,
    val centerX: Float,
    val centerY: Float,
    val clickable: Boolean,
    val editable: Boolean
)

class PersonalAiAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (!pkg.isNullOrBlank()) {
            _activePackage.value = pkg
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        _isConnected.value = false
    }

    fun inspectActiveWindowNodes(maxNodes: Int = 40): Result<List<ObservedUiNode>> {
        val pkg = _activePackage.value
        if (CommandSecurityGuard.isProtectedPackage(pkg)) {
            return Result.failure(
                IllegalStateException("Security Policy: Screen inspection is blocked inside protected app ($pkg).")
            )
        }
        val root = rootInActiveWindow
            ?: return Result.failure(IllegalStateException("No active window root available."))
        val collected = mutableListOf<ObservedUiNode>()
        traverseNode(root, collected, maxNodes)
        return Result.success(collected)
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, out: MutableList<ObservedUiNode>, limit: Int) {
        if (node == null || out.size >= limit) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val rawText = if (node.isPassword) "[REDACTED_PASSWORD]" else node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()

        if (rawText.isNotBlank() || desc.isNotBlank() || node.isClickable || node.isEditable) {
            out.add(
                ObservedUiNode(
                    text = rawText.take(80),
                    contentDesc = desc.take(80),
                    className = node.className?.toString()?.substringAfterLast(".") ?: "View",
                    bounds = "${rect.left},${rect.top},${rect.right},${rect.bottom}",
                    centerX = rect.exactCenterX(),
                    centerY = rect.exactCenterY(),
                    clickable = node.isClickable,
                    editable = node.isEditable
                )
            )
        }
        for (i in 0 until node.childCount) {
            if (out.size >= limit) break
            traverseNode(node.getChild(i), out, limit)
        }
    }

    fun performTap(x: Float, y: Float): Boolean {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun performDoubleTap(x: Float, y: Float): Boolean {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val p1 = Path().apply { moveTo(x, y) }
        val p2 = Path().apply { moveTo(x, y) }
        val s1 = GestureDescription.StrokeDescription(p1, 0L, 45L)
        val s2 = GestureDescription.StrokeDescription(p2, 120L, 45L)
        return dispatchGesture(GestureDescription.Builder().addStroke(s1).addStroke(s2).build(), null, null)
    }

    fun performLongPress(x: Float, y: Float): Boolean {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 650L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 350L): Boolean {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(100L, 1500L))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun setTextByQuery(query: String, text: String): Boolean {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val root = rootInActiveWindow ?: return false
        val target = findEditableOrMatchingNode(root, query.lowercase()) ?: return false
        if (target.isPassword) return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun clickNodeByText(text: String): Boolean {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val root = rootInActiveWindow ?: return false
        val matches = root.findAccessibilityNodeInfosByText(text)
        for (node in matches) {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable && !current.isPassword) {
                    return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                current = current.parent
            }
        }
        return false
    }

    fun performSystemBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun performSystemHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun performSystemRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun performSystemNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    private fun findEditableOrMatchingNode(node: AccessibilityNodeInfo?, query: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && !node.isPassword) {
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString().orEmpty() else ""
            val combined = "${node.text} ${node.contentDescription} $hint".lowercase()
            if (query.isBlank() || combined.contains(query)) return node
        }
        for (i in 0 until node.childCount) {
            val found = findEditableOrMatchingNode(node.getChild(i), query)
            if (found != null) return found
        }
        return null
    }

    companion object {
        @Volatile
        var instance: PersonalAiAccessibilityService? = null
            private set

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _activePackage = MutableStateFlow("None")
        val activePackage: StateFlow<String> = _activePackage.asStateFlow()
    }
}
