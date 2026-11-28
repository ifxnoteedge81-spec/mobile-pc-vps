package com.example.whatsapp

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

class WhatsAppScheduleWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val scheduleId = inputData.getLong(KEY_SCHEDULE_ID, -1L)
        val app = applicationContext as PersonalAiApplication
        val repo = app.container.repository
        val engine = app.container.whatsAppBotEngine

        val item = if (scheduleId > 0L) repo.getWhatsAppScheduleById(scheduleId) else null
        if (item != null && item.status != "PENDING") {
            return Result.success()
        }

        val targetTitle = item?.targetDisplay ?: "WhatsApp Schedule"
        runCatching {
            setForeground(createForegroundInfo(targetTitle))
        }

        val primaryResult = if (scheduleId > 0L) {
            engine.executeScheduledDispatch(scheduleId)
        } else {
            kotlin.Result.success("Sweep triggered")
        }

        // Also sweep any earlier due pending items if device was asleep
        runCatching { engine.sweepDueScheduledDispatches() }

        val updated = if (scheduleId > 0L) repo.getWhatsAppScheduleById(scheduleId) else null
        if (primaryResult.isSuccess) {
            postNotification(
                title = "WhatsApp শিডিউল সম্পন্ন • $targetTitle",
                message = updated?.lastResult?.take(120)?.ifBlank { "মেসেজ সফলভাবে পাঠানো হয়েছে।" }
                    ?: "মেসেজ সফলভাবে পাঠানো হয়েছে।"
            )
            return Result.success()
        }

        val errMsg = primaryResult.exceptionOrNull()?.message ?: "Send failed"
        postNotification(
            title = "WhatsApp শিডিউল সতর্কতা • $targetTitle",
            message = "পাঠানো যায়নি: ${errMsg.take(100)} (WhatsApp Link / BG Access যাচাই করুন)"
        )
        // Do not retry infinitely if already marked FAILED after execution, unless transient
        return if (runAttemptCount < 2 && updated?.status != "CANCELLED") {
            if (updated != null && updated.status == "FAILED") {
                repo.updateWhatsAppSchedule(updated.copy(status = "PENDING", lastResult = "Retrying: ${errMsg.take(120)}"))
            }
            Result.retry()
        } else {
            Result.failure()
        }
    }

    private fun createForegroundInfo(targetTitle: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "WhatsApp Scheduled Campaigns",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("WhatsApp মেসেজ পাঠানো হচ্ছে")
            .setContentText("Target: $targetTitle")
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
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "WhatsApp Scheduled Campaigns",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify((System.currentTimeMillis() % 100_000).toInt() + 5200, notif)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val KEY_SCHEDULE_ID = "wa_schedule_id"
        const val CHANNEL_ID = "wa_schedule_channel"
        const val FOREGROUND_NOTIF_ID = 4092
    }
}
