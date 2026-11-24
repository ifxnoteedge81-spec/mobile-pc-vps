package com.example.scheduler

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.storage.AutomationRuleEntity
import java.util.Calendar
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

    private fun computeInitialDelayMillis(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
        }
        if (target.before(now)) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return (target.timeInMillis - now.timeInMillis).coerceAtLeast(10_000L)
    }
}
