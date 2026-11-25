package com.example.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
    val editable: Boolean,
    val scrollable: Boolean = false,
    val checked: Boolean = false
)

class PersonalAiAccessibilityService : AccessibilityService() {

    @Volatile
    private var lastEventTraverseAtMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (!pkg.isNullOrBlank()) {
            _activePackage.value = pkg
            val now = System.currentTimeMillis()
            if (now - lastEventTraverseAtMs >= 200L && !CommandSecurityGuard.isProtectedPackage(pkg)) {
                lastEventTraverseAtMs = now
                val collected = collectAcrossVisibleWindows(maxNodes = 70)
                if (collected.isNotEmpty()) {
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

    fun inspectActiveWindowNodes(maxNodes: Int = 120): Result<List<ObservedUiNode>> {
        val pkg = _activePackage.value
        if (CommandSecurityGuard.isProtectedPackage(pkg)) {
            return Result.failure(
                IllegalStateException("Security Policy: Screen inspection is blocked inside protected app ($pkg).")
            )
        }
        val collected = collectAcrossVisibleWindows(maxNodes)
        if (collected.isEmpty() && rootInActiveWindow == null && windows.isNullOrEmpty()) {
            return Result.failure(IllegalStateException("No active window root available. Open a screen first."))
        }
        _liveNodes.value = collected
        return Result.success(collected)
    }

    private fun collectAcrossVisibleWindows(maxNodes: Int): List<ObservedUiNode> {
        val collected = mutableListOf<ObservedUiNode>()
        val seenKeys = HashSet<String>(maxNodes * 2)
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        val windowList = runCatching { windows }.getOrNull().orEmpty()
        val orderedRoots = mutableListOf<AccessibilityNodeInfo>()

        if (windowList.isNotEmpty()) {
            val prioritized = windowList
                .filter { w ->
                    w.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                        w.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                }
                .sortedWith(
                    compareByDescending<AccessibilityWindowInfo> { it.isActive }
                        .thenByDescending { it.isFocused }
                        .thenByDescending { it.layer }
                )
            prioritized.forEach { w ->
                w.root?.let { orderedRoots.add(it) }
            }
        }

        val activeRoot = rootInActiveWindow
        if (activeRoot != null && orderedRoots.none { it == activeRoot }) {
            orderedRoots.add(0, activeRoot)
        }

        for (root in orderedRoots) {
            if (collected.size >= maxNodes) break
            traverseNode(root, collected, seenKeys, maxNodes, screenW, screenH)
        }
        return collected
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo?,
        out: MutableList<ObservedUiNode>,
        seenKeys: MutableSet<String>,
        limit: Int,
        screenW: Int,
        screenH: Int
    ) {
        if (node == null || out.size >= limit) return
        if (!node.isVisibleToUser) return

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val visibleOnScreen = rect.width() > 0 && rect.height() > 0 &&
            rect.right > 0 && rect.left < screenW &&
            rect.bottom > 0 && rect.top < screenH

        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString().orEmpty().trim()
        } else ""
        val rawText = if (node.isPassword) {
            val state = if (node.text.isNullOrEmpty()) "[PASSWORD_EMPTY]" else "[PASSWORD_FILLED]"
            if (hint.isNotBlank()) "$hint $state" else state
        } else {
            node.text?.toString().orEmpty().trim()
        }
        val desc = node.contentDescription?.toString().orEmpty().trim()
        val viewId = node.viewIdResourceName?.substringAfterLast("/") ?: ""
        val displayLabel = rawText.ifBlank { hint }

        if (visibleOnScreen && (displayLabel.isNotBlank() || desc.isNotBlank() || node.isClickable || node.isEditable)) {
            val dedupKey = "${displayLabel.take(40)}|${desc.take(40)}|${rect.left},${rect.top},${rect.right},${rect.bottom}|${node.isClickable}|${node.isEditable}"
            if (seenKeys.add(dedupKey)) {
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
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                        checked = @Suppress("DEPRECATION") node.isChecked || node.isSelected
                    )
                )
            }
        }
        for (i in 0 until node.childCount) {
            if (out.size >= limit) break
            traverseNode(node.getChild(i), out, seenKeys, limit, screenW, screenH)
        }
    }

    fun performTap(x: Float, y: Float): Boolean {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val safeX = x.coerceAtLeast(1f)
        val safeY = y.coerceAtLeast(1f)
        val path = Path().apply { moveTo(safeX, safeY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 45L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun performDoubleTap(x: Float, y: Float): Boolean {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val safeX = x.coerceAtLeast(1f)
        val safeY = y.coerceAtLeast(1f)
        val p1 = Path().apply { moveTo(safeX, safeY) }
        val p2 = Path().apply { moveTo(safeX, safeY) }
        val s1 = GestureDescription.StrokeDescription(p1, 0L, 35L)
        val s2 = GestureDescription.StrokeDescription(p2, 95L, 35L)
        return dispatchGesture(GestureDescription.Builder().addStroke(s1).addStroke(s2).build(), null, null)
    }

    fun performLongPress(x: Float, y: Float): Boolean {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val safeX = x.coerceAtLeast(1f)
        val safeY = y.coerceAtLeast(1f)
        val path = Path().apply { moveTo(safeX, safeY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 580L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 260L): Boolean {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val path = Path().apply {
            moveTo(x1.coerceAtLeast(1f), y1.coerceAtLeast(1f))
            lineTo(x2.coerceAtLeast(1f), y2.coerceAtLeast(1f))
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(90L, 1200L))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun performDirectionalScroll(direction: String): Boolean {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        return when (direction.lowercase()) {
            "down" -> performSwipe(cx, dm.heightPixels * 0.74f, cx, dm.heightPixels * 0.24f, 260L)
            "up" -> performSwipe(cx, dm.heightPixels * 0.26f, cx, dm.heightPixels * 0.76f, 260L)
            "left" -> performSwipe(dm.widthPixels * 0.82f, cy, dm.widthPixels * 0.18f, cy, 240L)
            "right" -> performSwipe(dm.widthPixels * 0.18f, cy, dm.widthPixels * 0.82f, cy, 240L)
            else -> performSwipe(cx, dm.heightPixels * 0.74f, cx, dm.heightPixels * 0.24f, 260L)
        }
    }

    fun setTextByQuery(query: String, text: String): Boolean {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val qLower = query.trim().lowercase()
        val roots = getCandidateRoots()
        if (roots.isEmpty()) return false

        var target: AccessibilityNodeInfo? = null
        for (root in roots) {
            target = findEditableOrMatchingNode(root, qLower)
            if (target != null) break
        }
        if (target == null) {
            for (root in roots) {
                target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (target != null) break
            }
        }
        if (target == null) return false

        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return true
        }
        // Fallback: click/tap to activate Compose/WebView input, then set text
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val rect = Rect()
        target.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            performTap(rect.exactCenterX(), rect.exactCenterY())
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun setTextInFocusedNode(text: String): Boolean {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val roots = getCandidateRoots()
        var focused: AccessibilityNodeInfo? = null
        for (root in roots) {
            focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) break
        }
        if (focused == null) {
            for (root in roots) {
                focused = findEditableOrMatchingNode(root, "")
                if (focused != null) break
            }
        }
        if (focused == null) return false
        focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun performImeEnter(): Boolean {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val roots = getCandidateRoots()
        for (root in roots) {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findEditableOrMatchingNode(root, "")
            if (focused != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                    return true
                }
            }
        }
        val submitLabels = listOf("Search", "Send", "Go", "Submit", "Next", "Done", "Enter")
        for (lbl in submitLabels) {
            if (clickNodeByText(lbl)) return true
        }
        return false
    }

    fun clickNodeByText(query: String): Boolean {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val qLower = query.trim().lowercase()
        if (qLower.isEmpty()) return false
        val roots = getCandidateRoots()
        if (roots.isEmpty()) return false

        for (root in roots) {
            // 1. Standard Accessibility text search -> clickable parent
            val matches = root.findAccessibilityNodeInfosByText(query)
            for (node in matches) {
                var current: AccessibilityNodeInfo? = node
                while (current != null) {
                    if (current.isClickable) {
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

            // 3. Coordinate tap fallback for Compose/WebView/Flutter/TikTok nodes without clickable flag
            val anyMatchingLeaf = matches.firstOrNull() ?: findAnyNodeByQuery(root, qLower)
            if (anyMatchingLeaf != null) {
                val rect = Rect()
                anyMatchingLeaf.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    return performTap(rect.exactCenterX(), rect.exactCenterY())
                }
            }
        }
        return false
    }

    fun performSystemBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun performSystemHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun performSystemRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun performSystemNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun performSystemQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    private fun getCandidateRoots(): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { list.add(it) }
        val winList = runCatching { windows }.getOrNull().orEmpty()
        winList.sortedByDescending { it.isActive || it.isFocused }.forEach { w ->
            val r = w.root
            if (r != null && list.none { it == r }) list.add(r)
        }
        return list
    }

    private fun findClickableByAny(node: AccessibilityNodeInfo?, q: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (nodeMatchesQuery(node, q)) {
            var p: AccessibilityNodeInfo? = node
            while (p != null) {
                if (p.isClickable) return p
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
        if (nodeMatchesQuery(node, q)) {
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
        if (node.isEditable) {
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
