package com.example.scheduler

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.PersonalAiApplication
import com.example.R
import com.example.whatsapp.WhatsAppNotificationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sticky 24/7 Foreground Service for autonomous tasks, WhatsApp background monitoring,
 * and OEM background survival. Handles Android 15+ FGS timeouts cleanly via onTimeout()
 * without throwing ForegroundServiceDidNotStopInTimeException.
 */
class AgentForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var stopRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            releaseWakeLock()
            _isRunning.value = false
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            runCatching { stopSelf() }
            return START_NOT_STICKY
        }

        stopRequested = false
        val started = runCatching {
            val label = intent?.getStringExtra(EXTRA_LABEL)
                ?: "24/7 Active • WhatsApp Bot & Virtual Hands Ready"
            ensureChannel()

            val openIntent = Intent(this, MainActivity::class.java).apply {
                this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPi = PendingIntent.getActivity(
                this,
                NOTIF_ID,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val stopIntent = Intent(this, AgentForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPi = PendingIntent.getService(
                this,
                NOTIF_ID + 10,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("AI Computer Running in Background")
                .setContentText(label)
                .setContentIntent(openPi)
                .addAction(0, "Pause Daemon", stopPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()

            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else 0
            ServiceCompat.startForeground(this, NOTIF_ID, notification, fgsType)
            true
        }.getOrDefault(false)

        if (!started) {
            _isRunning.value = false
            runCatching { stopSelf() }
            return START_NOT_STICKY
        }

        _isRunning.value = true
        acquireRollingWakeLock()

        runCatching {
            val app = application as? PersonalAiApplication
            app?.container?.workScheduler?.ensureKeepAliveWatchdogScheduled()
            WhatsAppNotificationService.requestRebindIfNeeded(this)
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Mandatory on Android 15+ when 6-hour dataSync background budget expires:
        // stop cleanly within seconds and hand over to WorkManager watchdog to avoid OS crash.
        releaseWakeLock()
        _isRunning.value = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { AgentWorkScheduler(applicationContext).triggerImmediateKeepAliveSync(delaySeconds = 3L) }
        runCatching { stopSelf() }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!stopRequested) {
            runCatching {
                AgentWorkScheduler(applicationContext).triggerImmediateKeepAliveSync(delaySeconds = 2L)
            }
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        _isRunning.value = false
        super.onDestroy()
    }

    private fun acquireRollingWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PersonalAiComputer:KeepAliveLock").apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.let { wl ->
                if (!wl.isHeld) {
                    wl.acquire(10 * 60_000L) // Rolling 10-min wake lock renewed by background keeper
                }
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
    }

    private fun ensureChannel() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "24/7 AI Background Engine",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "ai_foreground_exec"
        const val INTERVENTION_CHANNEL_ID = "ai_human_intervention"
        const val NOTIF_ID = 4041
        const val INTERVENTION_NOTIF_ID = 4042
        const val ACTION_STOP = "com.example.STOP_AGENT_FGS"
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_OPEN_AGENT_LIVE = "extra_open_agent_live"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun startOrRefresh(context: Context, label: String? = null): Boolean {
            return runCatching {
                val intent = Intent(context, AgentForegroundService::class.java).apply {
                    if (!label.isNullOrBlank()) putExtra(EXTRA_LABEL, label)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            }.getOrDefault(false)
        }

        fun stopDaemon(context: Context) {
            runCatching {
                val stopIntent = Intent(context, AgentForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(stopIntent)
            }.onFailure {
                runCatching { context.stopService(Intent(context, AgentForegroundService::class.java)) }
            }
            _isRunning.value = false
        }

        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            return runCatching {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(context.packageName)
            }.getOrDefault(false)
        }

        fun openBatteryOptimizationSettings(context: Context): Result<String> {
            return runCatching {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Opened Battery Optimization settings — set AI Computer to Unrestricted / Don't optimize."
            }.recoverCatching {
                openAppInfoSettings(context).getOrThrow()
            }
        }

        fun openAppInfoSettings(context: Context): Result<String> = runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opened App Info — tap ⋮ (top right) > Allow restricted settings, and set Battery > Unrestricted."
        }

        fun postInterventionNotification(context: Context, challengeType: String, reason: String) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) return
                }
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val ch = NotificationChannel(
                        INTERVENTION_CHANNEL_ID,
                        "AI Live Screen Alerts (OTP / CAPTCHA)",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Alerts when autonomous background tasks need you to solve OTP or CAPTCHA on the Live Screen."
                    }
                    nm.createNotificationChannel(ch)
                }

                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EXTRA_OPEN_AGENT_LIVE, true)
                }
                val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val pi = PendingIntent.getActivity(context, INTERVENTION_NOTIF_ID, launchIntent, pendingFlags)

                val notif = NotificationCompat.Builder(context, INTERVENTION_CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("সল্ভ প্রয়োজন: $challengeType")
                    .setContentText("$reason লাইভ স্ক্রিন ওপেন করতে ট্যাপ করুন।")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build()

                nm.notify(INTERVENTION_NOTIF_ID, notif)
            }
        }

        fun clearInterventionNotification(context: Context) {
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(INTERVENTION_NOTIF_ID)
            }
        }
    }
}
