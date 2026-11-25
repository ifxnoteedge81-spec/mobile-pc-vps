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
    val viewId: String = "",
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
            if (!CommandSecurityGuard.isProtectedPackage(pkg)) {
                rootInActiveWindow?.let { root ->
                    val collected = mutableListOf<ObservedUiNode>()
                    traverseNode(root, collected, 45)
                    _liveNodes.value = collected
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        _isConnected.value = false
    }

    fun getScreenSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    fun inspectActiveWindowNodes(maxNodes: Int = 90): Result<List<ObservedUiNode>> {
        val pkg = _activePackage.value
        if (CommandSecurityGuard.isProtectedPackage(pkg)) {
            return Result.failure(
                IllegalStateException("Security Policy: Screen inspection is blocked inside protected app ($pkg).")
            )
        }
        val root = rootInActiveWindow
            ?: return Result.failure(IllegalStateException("No active window root available. Open a screen first."))
        val collected = mutableListOf<ObservedUiNode>()
        traverseNode(root, collected, maxNodes)
        _liveNodes.value = collected
        return Result.success(collected)
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, out: MutableList<ObservedUiNode>, limit: Int) {
        if (node == null || out.size >= limit) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val rawText = if (node.isPassword) "[REDACTED_PASSWORD]" else node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !node.isPassword) {
            node.hintText?.toString().orEmpty()
        } else ""
        val viewId = node.viewIdResourceName?.substringAfterLast("/") ?: ""
        val displayLabel = rawText.ifBlank { hint }

        val visibleOnScreen = rect.width() > 0 && rect.height() > 0
        if (visibleOnScreen && (displayLabel.isNotBlank() || desc.isNotBlank() || node.isClickable || node.isEditable)) {
            out.add(
                ObservedUiNode(
                    text = displayLabel.take(100),
                    contentDesc = desc.take(100),
                    viewId = viewId.take(45),
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
        val safeX = x.coerceAtLeast(1f)
        val safeY = y.coerceAtLeast(1f)
        val path = Path().apply { moveTo(safeX, safeY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 65L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun performDoubleTap(x: Float, y: Float): Boolean {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val safeX = x.coerceAtLeast(1f)
        val safeY = y.coerceAtLeast(1f)
        val p1 = Path().apply { moveTo(safeX, safeY) }
        val p2 = Path().apply { moveTo(safeX, safeY) }
        val s1 = GestureDescription.StrokeDescription(p1, 0L, 45L)
        val s2 = GestureDescription.StrokeDescription(p2, 120L, 45L)
        return dispatchGesture(GestureDescription.Builder().addStroke(s1).addStroke(s2).build(), null, null)
    }

    fun performLongPress(x: Float, y: Float): Boolean {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val safeX = x.coerceAtLeast(1f)
        val safeY = y.coerceAtLeast(1f)
        val path = Path().apply { moveTo(safeX, safeY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 680L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 340L): Boolean {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val path = Path().apply {
            moveTo(x1.coerceAtLeast(1f), y1.coerceAtLeast(1f))
            lineTo(x2.coerceAtLeast(1f), y2.coerceAtLeast(1f))
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(100L, 1500L))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun performDirectionalScroll(direction: String): Boolean {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        return when (direction.lowercase()) {
            "down" -> performSwipe(cx, dm.heightPixels * 0.74f, cx, dm.heightPixels * 0.24f, 360L)
            "up" -> performSwipe(cx, dm.heightPixels * 0.26f, cx, dm.heightPixels * 0.76f, 360L)
            "left" -> performSwipe(dm.widthPixels * 0.82f, cy, dm.widthPixels * 0.18f, cy, 300L)
            "right" -> performSwipe(dm.widthPixels * 0.18f, cy, dm.widthPixels * 0.82f, cy, 300L)
            else -> performSwipe(cx, dm.heightPixels * 0.74f, cx, dm.heightPixels * 0.24f, 360L)
        }
    }

    fun setTextByQuery(query: String, text: String): Boolean {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val root = rootInActiveWindow ?: return false
        val target = findEditableOrMatchingNode(root, query.lowercase())
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return false
        if (target.isPassword) return false

        // Tap/focus first so Compose/WebView inputs activate properly
        val rect = Rect()
        target.getBoundsInScreen(rect)
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (rect.width() > 0 && rect.height() > 0) {
            performTap(rect.exactCenterX(), rect.exactCenterY())
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun setTextInFocusedNode(text: String): Boolean {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditableOrMatchingNode(root, "")
            ?: return false
        if (focused.isPassword) return false
        focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun performImeEnter(): Boolean {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditableOrMatchingNode(root, "")
        if (focused != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                return true
            }
        }
        // Fallback: try common submit/search/send/go buttons
        val submitLabels = listOf("Search", "Send", "Go", "Submit", "Next", "Done", "Enter")
        for (lbl in submitLabels) {
            if (clickNodeByText(lbl)) return true
        }
        return false
    }

    fun clickNodeByText(query: String): Boolean {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val root = rootInActiveWindow ?: return false
        val qLower = query.trim().lowercase()
        if (qLower.isEmpty()) return false

        // 1. Standard Accessibility text search -> clickable parent
        val matches = root.findAccessibilityNodeInfosByText(query)
        for (node in matches) {
            if (node.isPassword) continue
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable && !current.isPassword) {
                    if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                }
                current = current.parent
            }
        }

        // 2. Deep search across text, contentDescription, viewId, hintText -> clickable parent
        val altClickable = findClickableByAny(root, qLower)
        if (altClickable != null && altClickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        // 3. Coordinate tap fallback for Compose/WebView/Flutter nodes without clickable flag
        val anyMatchingLeaf = matches.firstOrNull { !it.isPassword } ?: findAnyNodeByQuery(root, qLower)
        if (anyMatchingLeaf != null && !anyMatchingLeaf.isPassword) {
            val rect = Rect()
            anyMatchingLeaf.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                return performTap(rect.exactCenterX(), rect.exactCenterY())
            }
        }
        return false
    }

    fun performSystemBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun performSystemHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun performSystemRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun performSystemNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun performSystemQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    fun performSystemScreenshot(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        else false

    private fun findClickableByAny(node: AccessibilityNodeInfo?, q: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (nodeMatchesQuery(node, q) && !node.isPassword) {
            var p: AccessibilityNodeInfo? = node
            while (p != null) {
                if (p.isClickable && !p.isPassword) return p
                p = p.parent
            }
        }
        for (i in 0 until node.childCount) {
            val found = findClickableByAny(node.getChild(i), q)
            if (found != null) return found
        }
        return null
    }

    private fun findAnyNodeByQuery(node: AccessibilityNodeInfo?, q: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (nodeMatchesQuery(node, q) && !node.isPassword) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) return node
        }
        for (i in 0 until node.childCount) {
            val found = findAnyNodeByQuery(node.getChild(i), q)
            if (found != null) return found
        }
        return null
    }

    private fun nodeMatchesQuery(node: AccessibilityNodeInfo, q: String): Boolean {
        val txt = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val vid = node.viewIdResourceName.orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString().orEmpty() else ""
        return "$txt $desc $vid $hint".lowercase().contains(q)
    }

    private fun findEditableOrMatchingNode(node: AccessibilityNodeInfo?, query: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && !node.isPassword) {
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString().orEmpty() else ""
            val combined = "${node.text} ${node.contentDescription} ${node.viewIdResourceName} $hint".lowercase()
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

        private val _liveNodes = MutableStateFlow<List<ObservedUiNode>>(emptyList())
        val liveNodes: StateFlow<List<ObservedUiNode>> = _liveNodes.asStateFlow()
    }
}
