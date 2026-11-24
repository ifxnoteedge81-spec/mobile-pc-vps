package com.example.scheduler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.R

class AgentForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "Autonomous agent task running"
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Personal AI Computer Active")
            .setContentText(label)
            .setOngoing(true)
            .build()

        startForeground(NOTIF_ID, notification)
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
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

    companion object {
        const val CHANNEL_ID = "ai_foreground_exec"
        const val NOTIF_ID = 4041
        const val ACTION_STOP = "com.example.STOP_AGENT_FGS"
        const val EXTRA_LABEL = "extra_label"
    }
}
