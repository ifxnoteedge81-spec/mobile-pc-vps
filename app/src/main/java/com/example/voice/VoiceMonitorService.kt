package com.example.voice

import android.Manifest
import android.app.Notification
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
import com.example.PersonalAiApplication
import com.example.R

class VoiceMonitorService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            runCatching { stopSelf() }
            return START_NOT_STICKY
        }

        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPermission) {
            runCatching { stopSelf() }
            return START_NOT_STICKY
        }

        val started = runCatching {
            ensureChannel()
            val notif = buildNotification()
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else 0
            ServiceCompat.startForeground(this, NOTIF_ID, notif, fgsType)
        }.isSuccess

        if (!started) {
            runCatching { stopSelf() }
            return START_NOT_STICKY
        }

        runCatching {
            val app = application as? PersonalAiApplication
            app?.container?.voiceAssistantController?.onForegroundServiceStarted()
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { stopSelf() }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_LIVE_AGENT", true)
        }
        val pi = PendingIntent.getActivity(
            this,
            5102,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("AI Computer Voice Guard Active")
            .setContentText("শুধুমাত্র আপনার ভয়েস শুনছে • Hands-Free Mode")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Voice Assistant Monitor",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "ai_voice_monitor_channel"
        const val NOTIF_ID = 4207
        const val ACTION_STOP = "com.example.voice.ACTION_STOP_MONITOR"

        fun start(context: Context) {
            runCatching {
                val hasMic = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasMic) return
                val intent = Intent(context, VoiceMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, VoiceMonitorService::class.java))
            }
        }
    }
}
