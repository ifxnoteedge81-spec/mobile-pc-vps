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
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

class AgentForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            runCatching { stopSelf() }
            return START_NOT_STICKY
        }

        runCatching {
            val label = intent?.getStringExtra(EXTRA_LABEL) ?: "Autonomous agent task running"
            ensureChannel()
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Personal AI Computer Active")
                .setContentText(label)
                .setOngoing(true)
                .build()

            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else 0
            ServiceCompat.startForeground(this, NOTIF_ID, notification, fgsType)
        }.onFailure {
            runCatching { stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Active AI Execution",
                    NotificationManager.IMPORTANCE_LOW
                )
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
