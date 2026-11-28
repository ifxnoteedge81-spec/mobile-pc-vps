package com.example.runtime

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import com.example.security.CommandSecurityGuard
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe monotonic ID generator so Compose LazyColumn/LazyRow `key` values
 * never collide even under sub-microsecond bursts (replacing System.nanoTime()).
 */
object MonotonicIdGenerator {
    private val counter = AtomicLong(1_000L)

    fun nextId(): Long = counter.getAndIncrement()
}

/**
 * Deterministic safety rules for AccessibilityService event throttling, coordinate clamping,
 * depth bounds, and bounds validation. Pure Kotlin so it is 100% unit-testable on JVM.
 */
object AccessibilitySafetyGuard {
    const val MAX_TRAVERSE_DEPTH = 24
    const val ACTIVE_EVENT_THROTTLE_MS = 220L
    const val IDLE_EVENT_THROTTLE_MS = 1600L

    fun shouldProcessAccessibilityEvent(
        pkg: String?,
        eventType: Int,
        isAgentBusy: Boolean,
        nowMs: Long,
        lastTraverseMs: Long
    ): Boolean {
        val cleanPkg = pkg?.trim().orEmpty()
        if (cleanPkg.isEmpty()) return false
        if (CommandSecurityGuard.isProtectedPackage(cleanPkg)) return false

        // When user is casually scrolling another app (e.g. Facebook Reels / Instagram / Chrome)
        // and no autonomous agent task is running, skip high-frequency scroll/content bursts so
        // recycled AccessibilityNodeInfo trees never collide or waste CPU.
        if (!isAgentBusy) {
            val isScrollOrNoise = eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            if (isScrollOrNoise) return false
            return (nowMs - lastTraverseMs) >= IDLE_EVENT_THROTTLE_MS
        }

        return (nowMs - lastTraverseMs) >= ACTIVE_EVENT_THROTTLE_MS
    }

    fun clampCoordinate(value: Float, maxExtent: Float): Float {
        if (!value.isFinite()) return 1f
        val safeMax = if (maxExtent.isFinite() && maxExtent >= 2f) maxExtent - 1f else Float.MAX_VALUE
        return value.coerceIn(1f, safeMax)
    }

    fun isVisibleOnScreen(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        screenW: Int,
        screenH: Int
    ): Boolean {
        if (screenW <= 0 || screenH <= 0) return false
        val w = right - left
        val h = bottom - top
        return w > 0 && h > 0 && right > 0 && left < screenW && bottom > 0 && top < screenH
    }
}

/**
 * Records last unexpected uncaught exception safely to app internal storage so diagnostics
 * are visible in About -> Crash Shield without silently discarding stack traces.
 */
object CrashDiagnosticRecorder {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appCtx = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val stack = throwable.stackTraceToString().take(3500)
                val report = "[$ts] Thread=${thread.name}\n${throwable::class.java.simpleName}: ${throwable.message}\n$stack"
                File(appCtx.filesDir, FILE_NAME).writeText(report)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun readLastCrash(context: Context): String? {
        return runCatching {
            val f = File(context.filesDir, FILE_NAME)
            if (f.exists() && f.length() > 0L) f.readText().take(3500) else null
        }.getOrNull()
    }

    fun clearLastCrash(context: Context) {
        runCatching {
            val f = File(context.filesDir, FILE_NAME)
            if (f.exists()) f.delete()
        }
    }
}
