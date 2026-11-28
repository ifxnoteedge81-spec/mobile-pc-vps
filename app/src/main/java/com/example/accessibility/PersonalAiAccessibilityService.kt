package com.example.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.runtime.AccessibilitySafetyGuard
import com.example.security.CommandSecurityGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.IdentityHashMap

enum class AccessibilityHealthState(val label: String) {
    CONNECTED("CONNECTED"),
    ENABLED_BUT_UNBOUND("NEEDS RE-TOGGLE"),
    DISABLED("DISABLED")
}

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

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        runCatching {
            super.onServiceConnected()
            instance = this
            _isConnected.value = true
            _healthState.value = AccessibilityHealthState.CONNECTED
            com.example.scheduler.AgentForegroundService.startOrRefresh(
                this,
                "Virtual Hands Accessibility Active • 24/7 Ready"
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            val ev = event ?: return
            val pkg = ev.packageName?.toString()
            if (!pkg.isNullOrBlank()) {
                _activePackage.value = pkg
                val now = System.currentTimeMillis()
                if (AccessibilitySafetyGuard.shouldProcessAccessibilityEvent(
                        pkg = pkg,
                        eventType = ev.eventType,
                        isAgentBusy = isAgentBusyFlag,
                        nowMs = now,
                        lastTraverseMs = lastEventTraverseAtMs
                    )
                ) {
                    lastEventTraverseAtMs = now
                    val collected = collectAcrossVisibleWindows(maxNodes = 70)
                    if (collected.isNotEmpty()) {
                        _liveNodes.value = collected
                    }
                }
            }
        } catch (_: Throwable) {
            // Never allow transient OS AccessibilityNodeInfo recycling or binder exceptions to crash the app
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        runCatching { super.onDestroy() }
        if (instance === this) instance = null
        _isConnected.value = false
        refreshHealthState(this)
    }

    fun getScreenSize(): Pair<Int, Int> {
        return runCatching {
            val dm = resources.displayMetrics
            dm.widthPixels.coerceAtLeast(1) to dm.heightPixels.coerceAtLeast(1)
        }.getOrDefault(1080 to 2400)
    }

    fun inspectActiveWindowNodes(maxNodes: Int = 120): Result<List<ObservedUiNode>> {
        return try {
            val pkg = _activePackage.value
            if (CommandSecurityGuard.isProtectedPackage(pkg)) {
                return Result.failure(
                    IllegalStateException("Security Policy: Screen inspection is blocked inside protected app ($pkg).")
                )
            }
            val collected = collectAcrossVisibleWindows(maxNodes)
            val hasAnyRoot = runCatching { rootInActiveWindow != null || !windows.isNullOrEmpty() }.getOrDefault(false)
            if (collected.isEmpty() && !hasAnyRoot) {
                return Result.failure(IllegalStateException("No active window root available. Open a screen first."))
            }
            _liveNodes.value = collected
            Result.success(collected)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun collectAcrossVisibleWindows(maxNodes: Int): List<ObservedUiNode> {
        return runCatching {
            val collected = mutableListOf<ObservedUiNode>()
            val seenKeys = HashSet<String>(maxNodes * 2)
            val visitedNodes = Collections.newSetFromMap(IdentityHashMap<AccessibilityNodeInfo, Boolean>())
            val (screenW, screenH) = getScreenSize()

            val windowList = runCatching { windows }.getOrNull().orEmpty()
            val orderedRoots = mutableListOf<AccessibilityNodeInfo>()

            if (windowList.isNotEmpty()) {
                val prioritized = runCatching {
                    windowList
                        .filter { w ->
                            w.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                                w.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                        }
                        .sortedWith(
                            compareByDescending<AccessibilityWindowInfo> { it.isActive }
                                .thenByDescending { it.isFocused }
                                .thenByDescending { it.layer }
                        )
                }.getOrDefault(emptyList())
                prioritized.forEach { w ->
                    runCatching { w.root }?.getOrNull()?.let { orderedRoots.add(it) }
                }
            }

            val activeRoot = runCatching { rootInActiveWindow }.getOrNull()
            if (activeRoot != null && orderedRoots.none { it == activeRoot }) {
                orderedRoots.add(0, activeRoot)
            }

            for (root in orderedRoots) {
                if (collected.size >= maxNodes) break
                traverseNode(
                    node = root,
                    out = collected,
                    seenKeys = seenKeys,
                    visitedNodes = visitedNodes,
                    limit = maxNodes,
                    screenW = screenW,
                    screenH = screenH,
                    depth = 0
                )
            }
            collected
        }.getOrDefault(emptyList())
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo?,
        out: MutableList<ObservedUiNode>,
        seenKeys: MutableSet<String>,
        visitedNodes: MutableSet<AccessibilityNodeInfo>,
        limit: Int,
        screenW: Int,
        screenH: Int,
        depth: Int
    ) {
        if (node == null || out.size >= limit || depth >= AccessibilitySafetyGuard.MAX_TRAVERSE_DEPTH) return
        if (!visitedNodes.add(node)) return
        try {
            // Do NOT return early when container/wrapper node reports isVisibleToUser == false;
            // many Compose, WebView, Facebook & OEM containers hide parent flags while children are visible.
            val isSelfVisible = runCatching { node.isVisibleToUser }.getOrDefault(true)

            val rect = Rect()
            node.getBoundsInScreen(rect)
            val visibleOnScreen = AccessibilitySafetyGuard.isVisibleOnScreen(
                left = rect.left,
                top = rect.top,
                right = rect.right,
                bottom = rect.bottom,
                screenW = screenW,
                screenH = screenH
            )

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

            if (isSelfVisible && visibleOnScreen && (displayLabel.isNotBlank() || desc.isNotBlank() || node.isClickable || node.isEditable)) {
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
            val count = node.childCount
            for (i in 0 until count) {
                if (out.size >= limit) break
                val child = runCatching { node.getChild(i) }.getOrNull()
                if (child != null) {
                    traverseNode(child, out, seenKeys, visitedNodes, limit, screenW, screenH, depth + 1)
                }
            }
        } catch (_: Throwable) {
            // Skip stale/recycled nodes safely
        }
    }

    private fun dispatchSafeGesture(description: GestureDescription, tag: String): Boolean {
        val cb = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                _lastGestureDiagnostic.value = "$tag completed"
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                _lastGestureDiagnostic.value = "$tag cancelled by OS/screen"
            }
        }
        return runCatching {
            dispatchGesture(description, cb, mainHandler)
        }.getOrDefault(false)
    }

    fun performTap(x: Float, y: Float): Boolean = runCatching {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val (w, h) = getScreenSize()
        val safeX = AccessibilitySafetyGuard.clampCoordinate(x, w.toFloat())
        val safeY = AccessibilitySafetyGuard.clampCoordinate(y, h.toFloat())
        val path = Path().apply { moveTo(safeX, safeY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 45L)
        dispatchSafeGesture(GestureDescription.Builder().addStroke(stroke).build(), "tap(${safeX.toInt()},${safeY.toInt()})")
    }.getOrDefault(false)

    fun performDoubleTap(x: Float, y: Float): Boolean = runCatching {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val (w, h) = getScreenSize()
        val safeX = AccessibilitySafetyGuard.clampCoordinate(x, w.toFloat())
        val safeY = AccessibilitySafetyGuard.clampCoordinate(y, h.toFloat())
        val p1 = Path().apply { moveTo(safeX, safeY) }
        val p2 = Path().apply { moveTo(safeX, safeY) }
        val s1 = GestureDescription.StrokeDescription(p1, 0L, 35L)
        val s2 = GestureDescription.StrokeDescription(p2, 95L, 35L)
        dispatchSafeGesture(GestureDescription.Builder().addStroke(s1).addStroke(s2).build(), "double_tap")
    }.getOrDefault(false)

    fun performLongPress(x: Float, y: Float): Boolean = runCatching {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val (w, h) = getScreenSize()
        val safeX = AccessibilitySafetyGuard.clampCoordinate(x, w.toFloat())
        val safeY = AccessibilitySafetyGuard.clampCoordinate(y, h.toFloat())
        val path = Path().apply { moveTo(safeX, safeY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 580L)
        dispatchSafeGesture(GestureDescription.Builder().addStroke(stroke).build(), "long_press")
    }.getOrDefault(false)

    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 260L): Boolean = runCatching {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val (w, h) = getScreenSize()
        val sx1 = AccessibilitySafetyGuard.clampCoordinate(x1, w.toFloat())
        val sy1 = AccessibilitySafetyGuard.clampCoordinate(y1, h.toFloat())
        val sx2 = AccessibilitySafetyGuard.clampCoordinate(x2, w.toFloat())
        val sy2 = AccessibilitySafetyGuard.clampCoordinate(y2, h.toFloat())
        val path = Path().apply {
            moveTo(sx1, sy1)
            lineTo(sx2, sy2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(90L, 1200L))
        dispatchSafeGesture(GestureDescription.Builder().addStroke(stroke).build(), "swipe")
    }.getOrDefault(false)

    fun performDirectionalScroll(direction: String): Boolean = runCatching {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val (w, h) = getScreenSize()
        val cx = w / 2f
        val cy = h / 2f
        when (direction.lowercase()) {
            "down" -> performSwipe(cx, h * 0.74f, cx, h * 0.24f, 260L)
            "up" -> performSwipe(cx, h * 0.26f, cx, h * 0.76f, 260L)
            "left" -> performSwipe(w * 0.82f, cy, w * 0.18f, cy, 240L)
            "right" -> performSwipe(w * 0.18f, cy, w * 0.82f, cy, 240L)
            else -> performSwipe(cx, h * 0.74f, cx, h * 0.24f, 260L)
        }
    }.getOrDefault(false)

    fun setTextByQuery(query: String, text: String): Boolean = runCatching {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val qLower = query.trim().lowercase()
        val roots = getCandidateRoots()
        if (roots.isEmpty()) return false

        var target: AccessibilityNodeInfo? = null
        for (root in roots) {
            val visited = Collections.newSetFromMap(IdentityHashMap<AccessibilityNodeInfo, Boolean>())
            target = findEditableOrMatchingNode(root, qLower, visited, 0)
            if (target != null) break
        }
        if (target == null) {
            for (root in roots) {
                target = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
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
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val rect = Rect()
        target.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            performTap(rect.exactCenterX(), rect.exactCenterY())
        }
        target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }.getOrDefault(false)

    fun setTextInFocusedNode(text: String): Boolean = runCatching {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val roots = getCandidateRoots()
        var focused: AccessibilityNodeInfo? = null
        for (root in roots) {
            focused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            if (focused != null) break
        }
        if (focused == null) {
            for (root in roots) {
                val visited = Collections.newSetFromMap(IdentityHashMap<AccessibilityNodeInfo, Boolean>())
                focused = findEditableOrMatchingNode(root, "", visited, 0)
                if (focused != null) break
            }
        }
        if (focused == null) return false
        focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }.getOrDefault(false)

    fun performImeEnter(): Boolean = runCatching {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val roots = getCandidateRoots()
        for (root in roots) {
            val visited = Collections.newSetFromMap(IdentityHashMap<AccessibilityNodeInfo, Boolean>())
            val focused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
                ?: findEditableOrMatchingNode(root, "", visited, 0)
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
        false
    }.getOrDefault(false)

    fun clickNodeByText(query: String): Boolean = runCatching {
        if (CommandSecurityGuard.isProtectedPackage(_activePackage.value)) return false
        val qLower = query.trim().lowercase()
        if (qLower.isEmpty()) return false
        val roots = getCandidateRoots()
        if (roots.isEmpty()) return false

        for (root in roots) {
            val matches = runCatching { root.findAccessibilityNodeInfosByText(query) }.getOrNull().orEmpty()
            for (node in matches) {
                var current: AccessibilityNodeInfo? = node
                var hops = 0
                while (current != null && hops < 12) {
                    if (runCatching { current.isClickable }.getOrDefault(false)) {
                        if (runCatching { current.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
                            return true
                        }
                    }
                    current = runCatching { current.parent }.getOrNull()
                    hops++
                }
            }

            val visitedClickable = Collections.newSetFromMap(IdentityHashMap<AccessibilityNodeInfo, Boolean>())
            val altClickable = findClickableByAny(root, qLower, visitedClickable, 0)
            if (altClickable != null && runCatching { altClickable.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
                return true
            }

            val visitedAny = Collections.newSetFromMap(IdentityHashMap<AccessibilityNodeInfo, Boolean>())
            val anyMatchingLeaf = matches.firstOrNull() ?: findAnyNodeByQuery(root, qLower, visitedAny, 0)
            if (anyMatchingLeaf != null) {
                val rect = Rect()
                anyMatchingLeaf.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    return performTap(rect.exactCenterX(), rect.exactCenterY())
                }
            }
        }
        false
    }.getOrDefault(false)

    fun performSystemBack(): Boolean = runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }.getOrDefault(false)
    fun performSystemHome(): Boolean = runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }.getOrDefault(false)
    fun performSystemRecents(): Boolean = runCatching { performGlobalAction(GLOBAL_ACTION_RECENTS) }.getOrDefault(false)
    fun performSystemNotifications(): Boolean = runCatching { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) }.getOrDefault(false)
    fun performSystemQuickSettings(): Boolean = runCatching { performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) }.getOrDefault(false)

    private fun getCandidateRoots(): List<AccessibilityNodeInfo> {
        return runCatching {
            val list = mutableListOf<AccessibilityNodeInfo>()
            runCatching { rootInActiveWindow }.getOrNull()?.let { list.add(it) }
            val winList = runCatching { windows }.getOrNull().orEmpty()
            winList.sortedByDescending { it.isActive || it.isFocused }.forEach { w ->
                val r = runCatching { w.root }.getOrNull()
                if (r != null && list.none { it == r }) list.add(r)
            }
            list
        }.getOrDefault(emptyList())
    }

    private fun findClickableByAny(
        node: AccessibilityNodeInfo?,
        q: String,
        visited: MutableSet<AccessibilityNodeInfo>,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (node == null || depth >= AccessibilitySafetyGuard.MAX_TRAVERSE_DEPTH) return null
        if (!visited.add(node)) return null
        return try {
            if (nodeMatchesQuery(node, q)) {
                var p: AccessibilityNodeInfo? = node
                var hops = 0
                while (p != null && hops < 12) {
                    if (p.isClickable) return p
                    p = p.parent
                    hops++
                }
            }
            val count = node.childCount
            for (i in 0 until count) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                val found = findClickableByAny(child, q, visited, depth + 1)
                if (found != null) return found
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun findAnyNodeByQuery(
        node: AccessibilityNodeInfo?,
        q: String,
        visited: MutableSet<AccessibilityNodeInfo>,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (node == null || depth >= AccessibilitySafetyGuard.MAX_TRAVERSE_DEPTH) return null
        if (!visited.add(node)) return null
        return try {
            if (nodeMatchesQuery(node, q)) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) return node
            }
            val count = node.childCount
            for (i in 0 until count) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                val found = findAnyNodeByQuery(child, q, visited, depth + 1)
                if (found != null) return found
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun nodeMatchesQuery(node: AccessibilityNodeInfo, q: String): Boolean {
        return runCatching {
            val txt = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val vid = node.viewIdResourceName.orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString().orEmpty() else ""
            "$txt $desc $vid $hint".lowercase().contains(q)
        }.getOrDefault(false)
    }

    private fun findEditableOrMatchingNode(
        node: AccessibilityNodeInfo?,
        query: String,
        visited: MutableSet<AccessibilityNodeInfo>,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (node == null || depth >= AccessibilitySafetyGuard.MAX_TRAVERSE_DEPTH) return null
        if (!visited.add(node)) return null
        return try {
            if (node.isEditable) {
                val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString().orEmpty() else ""
                val combined = "${node.text} ${node.contentDescription} ${node.viewIdResourceName} $hint".lowercase()
                if (query.isBlank() || combined.contains(query)) return node
            }
            val count = node.childCount
            for (i in 0 until count) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                val found = findEditableOrMatchingNode(child, query, visited, depth + 1)
                if (found != null) return found
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        @Volatile
        var instance: PersonalAiAccessibilityService? = null
            private set

        @Volatile
        var isAgentBusyFlag: Boolean = false

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _healthState = MutableStateFlow(AccessibilityHealthState.DISABLED)
        val healthState: StateFlow<AccessibilityHealthState> = _healthState.asStateFlow()

        private val _activePackage = MutableStateFlow("None")
        val activePackage: StateFlow<String> = _activePackage.asStateFlow()

        private val _liveNodes = MutableStateFlow<List<ObservedUiNode>>(emptyList())
        val liveNodes: StateFlow<List<ObservedUiNode>> = _liveNodes.asStateFlow()

        private val _lastGestureDiagnostic = MutableStateFlow("")
        val lastGestureDiagnostic: StateFlow<String> = _lastGestureDiagnostic.asStateFlow()

        fun isEnabledInSystemSettings(context: Context): Boolean {
            return runCatching {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty()
                val flat = ComponentName(context, PersonalAiAccessibilityService::class.java).flattenToString()
                val shortFlat = ComponentName(context, PersonalAiAccessibilityService::class.java).flattenToShortString()
                enabled.contains(flat, ignoreCase = true) ||
                    enabled.contains(shortFlat, ignoreCase = true) ||
                    enabled.contains(PersonalAiAccessibilityService::class.java.simpleName)
            }.getOrDefault(false)
        }

        fun refreshHealthState(context: Context): AccessibilityHealthState {
            val bound = instance != null
            _isConnected.value = bound
            val next = when {
                bound -> AccessibilityHealthState.CONNECTED
                isEnabledInSystemSettings(context) -> AccessibilityHealthState.ENABLED_BUT_UNBOUND
                else -> AccessibilityHealthState.DISABLED
            }
            _healthState.value = next
            return next
        }
    }
}
