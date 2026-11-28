package com.example.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.PersonalAiApplication
import com.example.whatsapp.WhatsAppNotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pure JVM-testable decision logic for 24/7 background persistence across reboots,
 * app updates (MY_PACKAGE_REPLACED), OEM task removal, and Android 15+ FGS timeouts.
 */
object KeepAlivePolicy {
    fun shouldKeepBackgroundDaemonAlive(
        keepAliveEnabled: Boolean,
        waBotEnabled: Boolean,
        handsFreeVoiceEnabled: Boolean,
        hasPendingWork: Boolean
    ): Boolean = keepAliveEnabled || waBotEnabled || handsFreeVoiceEnabled || hasPendingWork

    fun shouldStartWhatsAppBridgeOnWake(waBotEnabled: Boolean): Boolean = waBotEnabled

    fun shouldRebindNotificationListener(
        waBotEnabled: Boolean,
        permissionGrantedInSettings: Boolean,
        isListenerBound: Boolean
    ): Boolean = waBotEnabled && permissionGrantedInSettings && !isListenerBound

    fun computeRestartBackoffMs(attempt: Int): Long {
        val clamped = attempt.coerceIn(0, 4)
        return (2_000L * (1L shl clamped)).coerceAtMost(32_000L)
    }
}

/**
 * Periodic & one-shot WorkManager watchdog that revives background components even if an OEM
 * battery killer terminates the foreground service or process after an app update / swipe-away.
 */
class KeepAliveWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? PersonalAiApplication ?: return Result.success()
        val container = app.container

        val settings = runCatching { container.settingsStore.settings.first() }.getOrNull()
        val pendingWa = runCatching { container.repository.getPendingWhatsAppSchedulesSnapshot() }
            .getOrDefault(emptyList())
        val hasEnabledRules = runCatching { container.repository.automationRules.first().any { it.enabled } }
            .getOrDefault(false)

        val keepAlive = settings?.backgroundKeepAliveEnabled ?: true
        val waEnabled = settings?.waBotEnabled ?: false
        val voiceEnabled = settings?.voiceHandsFreeEnabled ?: false

        val shouldRun = KeepAlivePolicy.shouldKeepBackgroundDaemonAlive(
            keepAliveEnabled = keepAlive,
            waBotEnabled = waEnabled,
            handsFreeVoiceEnabled = voiceEnabled,
            hasPendingWork = pendingWa.isNotEmpty() || hasEnabledRules
        )

        if (shouldRun) {
            val statusLabel = when {
                waEnabled -> "24/7 Active • WhatsApp AI Bot & Virtual Hands Ready"
                voiceEnabled -> "24/7 Active • Voice Guard & Automation Ready"
                else -> "24/7 Background Engine Active • Ready for Tasks"
            }
            // Safe best-effort start: Android 14+ may deny background dataSync FGS start after timeout;
            // WorkManager itself continues keeping WhatsApp schedules & listener rebind alive without crashing.
            AgentForegroundService.startOrRefresh(applicationContext, statusLabel)
        }

        val notifGranted = WhatsAppNotificationService.isPermissionEnabled(applicationContext)
        val listenerBound = WhatsAppNotificationService.isBound()
        if (KeepAlivePolicy.shouldRebindNotificationListener(waEnabled || keepAlive, notifGranted, listenerBound)) {
            WhatsAppNotificationService.requestRebindIfNeeded(applicationContext)
        }

        if (KeepAlivePolicy.shouldStartWhatsAppBridgeOnWake(waEnabled)) {
            withContext(Dispatchers.Main) {
                runCatching {
                    container.whatsAppWebBridge.ensureInitialized(loadIfNeeded = true)
                    container.whatsAppWebBridge.pulseKeepAlive()
                }
            }
        }

        if (pendingWa.isNotEmpty()) {
            runCatching {
                container.workScheduler.scheduleAllPendingWhatsApp(pendingWa)
                container.whatsAppBotEngine.sweepDueScheduledDispatches()
            }
        }

        runCatching { container.whatsAppBotEngine.refreshNotificationStatus() }
        return Result.success()
    }
}

/**
 * Restores WorkManager watchdog, NotificationListener binding, and background daemon
 * automatically after device boot or seamless in-place APK update (MY_PACKAGE_REPLACED).
 */
class BootAndKeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val relevant = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == ACTION_REVIVE_DAEMON
        if (!relevant) return

        val pendingResult = goAsync()
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val scheduler = AgentWorkScheduler(appCtx)
                scheduler.ensureKeepAliveWatchdogScheduled()
                scheduler.triggerImmediateKeepAliveSync(delaySeconds = 1L)

                val app = appCtx as? PersonalAiApplication
                val settings = app?.container?.settingsStore?.settings?.first()
                if (settings == null || settings.backgroundKeepAliveEnabled || settings.waBotEnabled) {
                    AgentForegroundService.startOrRefresh(
                        appCtx,
                        if (settings?.waBotEnabled == true) "WhatsApp AI Bot & Background Engine Restored"
                        else "Personal AI Computer Restored in Background"
                    )
                    WhatsAppNotificationService.requestRebindIfNeeded(appCtx)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_REVIVE_DAEMON = "com.example.scheduler.ACTION_REVIVE_DAEMON"
    }
}
