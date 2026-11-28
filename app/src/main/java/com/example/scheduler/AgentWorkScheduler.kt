package com.example.scheduler

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.storage.AutomationRuleEntity
import com.example.storage.WhatsAppScheduledMessageEntity
import com.example.whatsapp.WaChatKeyHelper
import com.example.whatsapp.WhatsAppScheduleWorker
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit

class AgentWorkScheduler(private val context: Context) {

    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    fun scheduleRule(rule: AutomationRuleEntity) {
        val data = Data.Builder()
            .putLong(ScheduledTaskWorker.KEY_RULE_ID, rule.id)
            .putString(ScheduledTaskWorker.KEY_PROMPT, rule.prompt)
            .build()

        val uniqueName = "ai_rule_${rule.id}"
        if (!rule.enabled) {
            workManager.cancelUniqueWork(uniqueName)
            return
        }

        val initialDelayMs = computeInitialDelayMillis(rule.hourOfDay, rule.minuteOfHour)

        when (rule.scheduleType.uppercase()) {
            "ONE_TIME" -> {
                val req = OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
                    .setInputData(data)
                    .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                    .build()
                workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, req)
            }
            "WEEKLY" -> {
                val req = PeriodicWorkRequestBuilder<ScheduledTaskWorker>(7, TimeUnit.DAYS)
                    .setInputData(data)
                    .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                    .build()
                workManager.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.UPDATE, req)
            }
            "MONTHLY" -> {
                val req = PeriodicWorkRequestBuilder<ScheduledTaskWorker>(30, TimeUnit.DAYS)
                    .setInputData(data)
                    .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                    .build()
                workManager.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.UPDATE, req)
            }
            else -> { // DAILY or CONDITIONAL
                val req = PeriodicWorkRequestBuilder<ScheduledTaskWorker>(24, TimeUnit.HOURS)
                    .setInputData(data)
                    .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                    .build()
                workManager.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.UPDATE, req)
            }
        }
    }

    fun runNow(rule: AutomationRuleEntity) {
        val data = Data.Builder()
            .putLong(ScheduledTaskWorker.KEY_RULE_ID, rule.id)
            .putString(ScheduledTaskWorker.KEY_PROMPT, rule.prompt)
            .build()
        val req = OneTimeWorkRequestBuilder<ScheduledTaskWorker>().setInputData(data).build()
        workManager.enqueue(req)
    }

    fun cancelRule(ruleId: Long) {
        workManager.cancelUniqueWork("ai_rule_$ruleId")
    }

    fun scheduleWhatsAppDispatch(
        scheduleId: Long,
        scheduledAtMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        if (scheduleId <= 0L) return
        val delayMs = (scheduledAtMillis - nowMillis).coerceAtLeast(1_500L)
        val data = Data.Builder()
            .putLong(WhatsAppScheduleWorker.KEY_SCHEDULE_ID, scheduleId)
            .build()
        val req = OneTimeWorkRequestBuilder<WhatsAppScheduleWorker>()
            .setInputData(data)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork("wa_sched_$scheduleId", ExistingWorkPolicy.REPLACE, req)
    }

    fun runWhatsAppScheduleNow(scheduleId: Long) {
        if (scheduleId <= 0L) return
        val data = Data.Builder()
            .putLong(WhatsAppScheduleWorker.KEY_SCHEDULE_ID, scheduleId)
            .build()
        val req = OneTimeWorkRequestBuilder<WhatsAppScheduleWorker>()
            .setInputData(data)
            .build()
        workManager.enqueueUniqueWork("wa_sched_$scheduleId", ExistingWorkPolicy.REPLACE, req)
    }

    fun cancelWhatsAppDispatch(scheduleId: Long) {
        workManager.cancelUniqueWork("wa_sched_$scheduleId")
    }

    fun scheduleAllPendingWhatsApp(items: List<WhatsAppScheduledMessageEntity>) {
        items.filter { it.status == "PENDING" }.forEach { item ->
            scheduleWhatsAppDispatch(item.id, item.scheduledAtMillis)
        }
    }

    private fun computeInitialDelayMillis(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.before(now)) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return (target.timeInMillis - now.timeInMillis).coerceAtLeast(10_000L)
    }

    companion object {
        fun computeTargetTimestampMillis(
            year: Int,
            month1To12: Int,
            dayOfMonth: Int,
            hour: Int,
            minute: Int
        ): Long {
            val cal = Calendar.getInstance().apply {
                set(Calendar.YEAR, year.coerceIn(2024, 2100))
                set(Calendar.MONTH, (month1To12 - 1).coerceIn(0, 11))
                set(Calendar.DAY_OF_MONTH, dayOfMonth.coerceIn(1, 31))
                set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
                set(Calendar.MINUTE, minute.coerceIn(0, 59))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return cal.timeInMillis
        }

        fun buildMultiDayScheduleEntities(
            targetDisplay: String,
            isGroup: Boolean,
            defaultMessageTemplate: String,
            useAiCompose: Boolean,
            startYear: Int,
            startMonth1To12: Int,
            startDayOfMonth: Int,
            hour: Int,
            minute: Int,
            totalDays: Int,
            dailyMessages: List<String> = emptyList(),
            campaignId: String = UUID.randomUUID().toString().take(8)
        ): List<WhatsAppScheduledMessageEntity> {
            val clampedDays = totalDays.coerceIn(1, 30)
            val cleanTarget = WaChatKeyHelper.fallbackDisplayName(targetDisplay)
            val chatKey = WaChatKeyHelper.normalize(cleanTarget, isGroup)
            val baseCal = Calendar.getInstance().apply {
                set(Calendar.YEAR, startYear.coerceIn(2024, 2100))
                set(Calendar.MONTH, (startMonth1To12 - 1).coerceIn(0, 11))
                set(Calendar.DAY_OF_MONTH, startDayOfMonth.coerceIn(1, 31))
                set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
                set(Calendar.MINUTE, minute.coerceIn(0, 59))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val cleanedDaily = dailyMessages.map { it.trim() }.filter { it.isNotEmpty() }
            return (1..clampedDays).map { dayNum ->
                val dayCal = (baseCal.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, dayNum - 1)
                }
                val perDayText = cleanedDaily.getOrNull(dayNum - 1) ?: defaultMessageTemplate.trim()
                WhatsAppScheduledMessageEntity(
                    chatKey = chatKey,
                    targetDisplay = cleanTarget,
                    isGroup = isGroup,
                    messageTemplate = perDayText,
                    useAiCompose = useAiCompose,
                    scheduledAtMillis = dayCal.timeInMillis,
                    campaignId = campaignId,
                    dayIndex = dayNum,
                    totalDays = clampedDays,
                    status = "PENDING",
                    lastResult = "Scheduled Day $dayNum/$clampedDays"
                )
            }
        }
    }
}
