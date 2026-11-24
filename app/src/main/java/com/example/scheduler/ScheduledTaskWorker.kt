package com.example.scheduler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
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

        app.container.agentEngine.submitCommand(prompt)

        if (rule != null) {
            repo.saveAutomationRule(
                rule.copy(
                    lastRunAt = System.currentTimeMillis(),
                    lastStatus = "Triggered via WorkManager"
                )
            )
        }

        postNotification(title, "Executed scheduled workflow: ${prompt.take(90)}")
        return Result.success()
    }

    private fun postNotification(title: String, message: String) {
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
        nm.notify(System.currentTimeMillis().toInt(), notif)
    }

    companion object {
        const val KEY_RULE_ID = "rule_id"
        const val KEY_PROMPT = "prompt"
        const val CHANNEL_ID = "ai_automation_channel"
    }
}
