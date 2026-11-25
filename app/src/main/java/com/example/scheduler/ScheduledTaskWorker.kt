package com.example.scheduler

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.PersonalAiApplication
import com.example.R

class ScheduledTaskWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ruleId = inputData.getLong(KEY_RULE_ID, -1L)
        val app = applicationContext as PersonalAiApplication
        val repo = app.container.repository
        val rule = if (ruleId > 0) repo.getAutomationRule(ruleId) else null

        if (rule != null && !rule.enabled) {
            return Result.success()
        }

        val prompt = rule?.prompt ?: inputData.getString(KEY_PROMPT) ?: return Result.failure()
        val title = rule?.title ?: "Scheduled AI Task"

        runCatching { setForeground(createForegroundInfo(title, prompt)) }

        val completed = runCatching {
            app.container.agentEngine.executeCommandAndWait(prompt)
        }.getOrDefault(false)

        if (rule != null) {
            repo.saveAutomationRule(
                rule.copy(
                    lastRunAt = System.currentTimeMillis(),
                    lastStatus = if (completed) "Completed via WorkManager" else "Failed / Retrying"
                )
            )
        }

        postNotification(
            title = if (completed) "$title (সম্পন্ন)" else "$title (ব্যর্থ)",
            message = if (completed) "Scheduled workflow completed: ${prompt.take(90)}" else "Task failed to complete cleanly; may retry."
        )
        return if (completed) Result.success() else Result.retry()
    }

    private fun createForegroundInfo(title: String, prompt: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Scheduled Automations",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Running: $title")
            .setContentText(prompt.take(80))
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(FOREGROUND_NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(FOREGROUND_NOTIF_ID, notif)
        }
    }

    private fun postNotification(title: String, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Scheduled Automations",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(ch)
        }
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(System.currentTimeMillis().toInt(), notif)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val KEY_RULE_ID = "rule_id"
        const val KEY_PROMPT = "prompt"
        const val CHANNEL_ID = "ai_automation_channel"
        const val FOREGROUND_NOTIF_ID = 4088
    }
}
